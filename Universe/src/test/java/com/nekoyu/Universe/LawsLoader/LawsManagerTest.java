package com.nekoyu.Universe.LawsLoader;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

public class LawsManagerTest {
    @TempDir Path temp;
    LawsManager manager;
    final List<CountDownLatch> releases = new ArrayList<>();
    public static class Control {
        public static final Map<String, Runnable> actions = new ConcurrentHashMap<>();
        public static final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        public static final Set<String> refusePrepare = ConcurrentHashMap.newKeySet();
        public static final Map<String, CompletableFuture<Void>> signals = new ConcurrentHashMap<>();
        public static void fire(String name, String stage, Class<?> type) {
            if (Thread.currentThread().getContextClassLoader() != type.getClassLoader()) throw new AssertionError("Wrong context loader: " + stage);
            if (type.getClassLoader() == Control.class.getClassLoader()) throw new AssertionError("Fixture did not load from plugin JAR");
            String key = name + ":" + stage;
            calls.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
            actions.getOrDefault(key, () -> {}).run();
        }
    }
    @BeforeEach void reset() { Control.actions.clear(); Control.calls.clear(); Control.signals.clear(); Control.refusePrepare.clear(); }
    @AfterEach void cleanup() throws Exception {
        releases.forEach(CountDownLatch::countDown);
        if (manager != null) manager.stopLawsAsync().get(5, TimeUnit.SECONDS);
    }
    void jar(String name, String metadata) throws Exception { jar(name, name, metadata, ""); }
    void jar(String file, String name, String metadata, String extra) throws Exception {
        Path dir = Files.createDirectories(temp.resolve("compile-" + file));
        String cls = "Fixture" + file;
        String source = "package fixture; import com.nekoyu.Universe.LawsLoader.Law; import com.nekoyu.Universe.LawsLoader.LawsManagerTest.Control; "
            + "public class " + cls + " extends Law { "
            + "static { Control.fire(\"" + name + "\", \"static\", " + cls + ".class); } "
            + "public " + cls + "() { Control.fire(\"" + name + "\", \"constructor\", getClass()); } "
            + "public boolean prepare() { Control.fire(\"" + name + "\", \"prepare\", getClass()); return !Control.refusePrepare.contains(\"" + name + "\"); } "
            + "public void run() { Control.fire(\"" + name + "\", \"run\", getClass()); } "
            + "public void stop() { Control.fire(\"" + name + "\", \"stop\", getClass()); } "
            + "public java.util.concurrent.CompletionStage<Void> readiness() { return Control.signals.get(\"" + name + "\"); } " + extra + "}";
        Path java = dir.resolve(cls + ".java"); Files.writeString(java, source);
        int result = ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "17", "-classpath", System.getProperty("java.class.path"), "-d", dir.toString(), java.toString());
        assertEquals(0, result);
        Path laws = Files.createDirectories(temp.resolve("laws"));
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(laws.resolve(file + ".jar")))) {
            out.putNextEntry(new JarEntry("law.yml"));
            out.write(("name: " + name + "\nmain: fixture." + cls + "\n" + metadata).getBytes(StandardCharsets.UTF_8)); out.closeEntry();
            out.putNextEntry(new JarEntry("fixture/" + cls + ".class")); out.write(Files.readAllBytes(dir.resolve("fixture/" + cls + ".class"))); out.closeEntry();
        }
    }
    CompletableFuture<LawsManager.StartupReport> start(long millis) {
        Duration d = Duration.ofMillis(millis);
        manager = new LawsManager(temp.resolve("laws"), new LawsManager.Timeouts(d, d, d, Duration.ofMillis(150)));
        return manager.startLawsAsync();
    }
    int calls(String key) { return Control.calls.getOrDefault(key, new AtomicInteger()).get(); }
    void await(CountDownLatch latch) throws Exception { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
    Runnable blocking(CountDownLatch entered, CountDownLatch release, CountDownLatch exited) {
        releases.add(release);
        return () -> {
            entered.countDown();
            while (release.getCount() != 0) try { release.await(); } catch (InterruptedException ignored) { }
            exited.countDown();
        };
    }
    @Test void independentPreparationOverlapsAndSharedDependencyStartsOnce() throws Exception {
        jar("A", ""); jar("B", ""); jar("C", "dependencies: [A, B]\n"); jar("D", "dependencies: [A]\n");
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1), exited = new CountDownLatch(2);
        Control.actions.put("A:prepare", blocking(entered, release, exited));
        Control.actions.put("B:prepare", blocking(entered, release, exited));
        var future = start(3000); await(entered);
        assertEquals(0, calls("C:prepare")); release.countDown();
        var report = future.get(5, TimeUnit.SECONDS);
        assertTrue(report.laws().values().stream().allMatch(r -> r.state() == LawsManager.State.READY));
        assertEquals(1, calls("A:run")); assertEquals(1, calls("A:prepare"));
        assertEquals(report, manager.startLawsAsync().get());
    }
    @ParameterizedTest @ValueSource(strings = {"static", "constructor", "prepare", "run"})
    void stuckStageCannotBlockHealthyLawOrReviveAfterTimeout(String stage) throws Exception {
        jar("Slow", ""); jar("Healthy", ""); jar("Child", "dependencies: [Slow]\n");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), exited = new CountDownLatch(1), healthy = new CountDownLatch(1);
        Control.actions.put("Slow:" + stage, blocking(entered, release, exited));
        Control.actions.put("Healthy:run", healthy::countDown);
        var future = start(350); await(entered); await(healthy);
        var report = future.get(5, TimeUnit.SECONDS);
        assertEquals(LawsManager.State.TIMED_OUT, report.laws().get("Slow").state());
        assertTrue(report.laws().get("Slow").codeExecuting());
        assertEquals(LawsManager.State.READY, report.laws().get("Healthy").state());
        assertEquals(LawsManager.State.DEPENDENCY_UNAVAILABLE, report.laws().get("Child").state());
        release.countDown(); await(exited);
        manager.stopLawsAsync().get(5, TimeUnit.SECONDS);
        assertEquals(0, calls("Child:run"));
        if (!stage.equals("run")) assertEquals(0, calls("Slow:run"));
    }
    @ParameterizedTest @ValueSource(strings = {"static", "constructor", "prepare", "run"})
    void errorsAreIsolated(String stage) throws Exception {
        jar("Bad", ""); jar("Healthy", "");
        Control.actions.put("Bad:" + stage, () -> { throw new NoClassDefFoundError("fixture failure"); });
        var report = start(2000).get(5, TimeUnit.SECONDS);
        assertEquals(LawsManager.State.FAILED, report.laws().get("Bad").state());
        assertEquals(LawsManager.State.READY, report.laws().get("Healthy").state());
    }
    @Test void optionalProviderOrderWaitsButFailureDoesNotDisableConsumer() throws Exception {
        jar("Provider", ""); jar("Consumer", "prepareAfter: [Provider, NotInstalled]\n");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), exited = new CountDownLatch(1);
        Control.actions.put("Provider:prepare", blocking(entered, release, exited));
        Control.actions.put("Consumer:prepare", () -> assertEquals(0, exited.getCount()));
        var future = start(3000); await(entered); assertEquals(0, calls("Consumer:prepare")); release.countDown();
        assertEquals(LawsManager.State.READY, future.get(5, TimeUnit.SECONDS).laws().get("Consumer").state());
    }
    @Test void optionalFailureAndMissingOptionalDependencyContinue() throws Exception {
        jar("Provider", ""); jar("Consumer", "prepareAfter: [Provider, NotInstalled]\n");
        Control.actions.put("Provider:prepare", () -> { throw new IllegalStateException("unavailable"); });
        assertEquals(LawsManager.State.READY, start(2000).get(5, TimeUnit.SECONDS).laws().get("Consumer").state());
    }
    @Test void aiChatMetadataOrdersProviderPublicationBeforePreparation() throws Exception {
        Path metadata = Path.of(System.getProperty("basedir")).resolve("../AIChat/src/main/resources/law.yml").normalize();
        LawCFG cfg;
        try (var reader = Files.newBufferedReader(metadata)) { cfg = new org.yaml.snakeyaml.Yaml().loadAs(reader, LawCFG.class); }
        assertEquals(Set.of("OpenAIAdapter", "DashScopeAdapter"), new HashSet<>(cfg.getPrepareAfter()));
        jar("OpenAIAdapter", ""); jar("AIChat", "prepareAfter: " + cfg.getPrepareAfter() + "\n");
        String key = "loader-test-provider";
        Control.actions.put("OpenAIAdapter:prepare", () -> com.nekoyu.Universe.Universe.Providers.put(key, "ready"));
        Control.actions.put("AIChat:prepare", () -> assertEquals("ready", com.nekoyu.Universe.Universe.Providers.get(key)));
        try { assertEquals(LawsManager.State.READY, start(2000).get(5, TimeUnit.SECONDS).laws().get("AIChat").state()); }
        finally { com.nekoyu.Universe.Universe.Providers.remove(key); }
    }
    @Test void metadataRejectsDuplicatesMissingDependenciesAndMixedCycles() throws Exception {
        jar("One", "Duplicate", "", ""); jar("Two", "Duplicate", "", "");
        jar("Missing", "dependencies: [Unknown]\n");
        jar("A", "prepareAfter: [B]\n"); jar("B", "dependencies: [A]\n"); jar("Good", "");
        var report = start(2000).get(5, TimeUnit.SECONDS);
        for (String id : List.of("Duplicate", "A", "B")) assertEquals(LawsManager.State.FAILED, report.laws().get(id).state());
        assertEquals(LawsManager.State.DEPENDENCY_UNAVAILABLE, report.laws().get("Missing").state());
        assertEquals(LawsManager.State.READY, report.laws().get("Good").state());
        assertEquals(0, calls("Duplicate:constructor")); assertEquals(0, calls("A:constructor"));
        assertTrue(report.rejectedJars().keySet().containsAll(List.of("One.jar", "Two.jar")));
    }
    @Test void overlappingOptionalCyclesRejectEveryParticipant() throws Exception {
        jar("A", "prepareAfter: [B, C]\n"); jar("B", "prepareAfter: [A]\n"); jar("C", "prepareAfter: [B]\n");
        var report = start(2000).get(5, TimeUnit.SECONDS);
        assertTrue(report.laws().values().stream().allMatch(r -> r.state() == LawsManager.State.FAILED));
        assertEquals(0, calls("C:constructor"));
    }
    @Test void explicitReadinessGatesDependentsAndHasDeadline() throws Exception {
        jar("Async", ""); jar("Child", "dependencies: [Async]\n");
        Control.signals.put("Async", new CompletableFuture<>());
        CountDownLatch run = new CountDownLatch(1); Control.actions.put("Async:run", run::countDown);
        var future = start(2000); await(run); assertEquals(0, calls("Child:run"));
        Control.signals.get("Async").complete(null);
        assertEquals(LawsManager.State.READY, future.get(5, TimeUnit.SECONDS).laws().get("Child").state());
    }
    @Test void readinessTimeoutCannotBeRevivedByLateSignal() throws Exception {
        jar("Async", ""); jar("Child", "dependencies: [Async]\n");
        Control.signals.put("Async", new CompletableFuture<>());
        var report = start(250).get(5, TimeUnit.SECONDS);
        assertEquals(LawsManager.State.TIMED_OUT, report.laws().get("Async").state());
        Control.signals.get("Async").complete(null);
        manager.stopLawsAsync().get(5, TimeUnit.SECONDS); assertEquals(0, calls("Child:run"));
    }
    @Test void stopIsReverseDependencyOrderAndFailureIsIsolated() throws Exception {
        jar("A", ""); jar("B", "dependencies: [A]\n");
        List<String> stops = new CopyOnWriteArrayList<>();
        Control.actions.put("B:stop", () -> { stops.add("B"); throw new IllegalStateException("stop failure"); });
        Control.actions.put("A:stop", () -> stops.add("A"));
        start(2000).get(5, TimeUnit.SECONDS); manager.stopLawsAsync().get(5, TimeUnit.SECONDS);
        assertEquals(List.of("B", "A"), stops);
    }
    @Test void stuckRunDoesNotOverlapStopAndDoesNotHoldShutdown() throws Exception {
        jar("LongRunning", ""); jar("Other", "");
        Control.signals.put("LongRunning", new CompletableFuture<>());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), exited = new CountDownLatch(1), stopped = new CountDownLatch(1);
        Control.actions.put("LongRunning:run", blocking(entered, release, exited));
        Control.actions.put("LongRunning:stop", () -> { assertEquals(0, exited.getCount()); stopped.countDown(); });
        var future = start(2000); await(entered); Control.signals.get("LongRunning").complete(null); future.get(5, TimeUnit.SECONDS);
        manager.stopLawsAsync().get(5, TimeUnit.SECONDS);
        assertEquals(0, calls("LongRunning:stop")); assertEquals(1, calls("Other:stop"));
        release.countDown(); await(stopped);
    }
    @Test void prepareFalseAndLegacyDependenciesAreExplicitFailures() throws Exception {
        jar("Refused", ""); jar("Legacy", "Legacy", "", "{ Dependencies = new String[]{\"Refused\"}; }");
        Control.refusePrepare.add("Refused");
        var report = start(2000).get(5, TimeUnit.SECONDS);
        assertEquals(LawsManager.State.FAILED, report.laws().get("Refused").state());
        assertTrue(report.laws().get("Legacy").detail().contains("Migrate Law.Dependencies"));
        assertEquals(0, calls("Refused:run")); assertEquals(0, calls("Legacy:prepare"));
    }
    @Test void stopTimeoutDoesNotBlockDependencyCleanup() throws Exception {
        jar("A", ""); jar("B", "dependencies: [A]\n");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), exited = new CountDownLatch(1);
        Control.actions.put("B:stop", blocking(entered, release, exited));
        start(2000).get(5, TimeUnit.SECONDS);
        var stopped = manager.stopLawsAsync(); await(entered); stopped.get(5, TimeUnit.SECONDS);
        assertEquals(1, calls("A:stop")); release.countDown(); await(exited);
    }
    @Test void emptyDirectoryAndStopBeforeStartComplete() throws Exception {
        assertTrue(start(1000).get(5, TimeUnit.SECONDS).laws().isEmpty());
        manager.stopLawsAsync().get(5, TimeUnit.SECONDS);
        manager = new LawsManager(temp.resolve("missing"), LawsManager.Timeouts.defaults());
        manager.stopLawsAsync().get(5, TimeUnit.SECONDS);
        assertTrue(manager.startLawsAsync().get(5, TimeUnit.SECONDS).laws().isEmpty());
    }
    @Test void malformedJarDoesNotPreventValidPluginLoading() throws Exception {
        jar("Good", ""); Files.writeString(temp.resolve("laws/broken.jar"), "not a zip");
        var report = start(2000).get(5, TimeUnit.SECONDS);
        assertEquals(LawsManager.State.READY, report.laws().get("Good").state());
        assertTrue(report.rejectedJars().containsKey("broken.jar"));
    }
    @Test void invalidTimeoutConfigurationIsRejected() {
        Properties p = new Properties(); p.setProperty("LawPrepareTimeoutSeconds", "0");
        assertThrows(IllegalArgumentException.class, () -> LawsManager.Timeouts.from(p));
    }
}

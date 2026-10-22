package com.nekoyu.Universe.LawsLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.JarFile;

/** Dependency scheduling and state transitions run exclusively on coordinator. Plugin code never does. */
public class LawsManager {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    public enum State { LOADING, WAITING_DEPENDENCIES, PREPARING, STARTING, READY, FAILED, TIMED_OUT, DEPENDENCY_UNAVAILABLE, STOPPED }
    public record Result(State state, String detail, boolean codeExecuting) {}
    public record StartupReport(Map<String, Result> laws, Map<String, String> rejectedJars) {
        public StartupReport { laws = Map.copyOf(laws); rejectedJars = Map.copyOf(rejectedJars); }
    }
    public record Timeouts(Duration load, Duration prepare, Duration start, Duration stop) {
        public Timeouts {
            for (Duration d : List.of(load, prepare, start, stop))
                if (d.isNegative() || d.isZero() || d.toMillis() < 1) throw new IllegalArgumentException("Law timeouts must be positive milliseconds");
        }
        public static Timeouts defaults() { return new Timeouts(Duration.ofSeconds(30), Duration.ofSeconds(120), Duration.ofSeconds(120), Duration.ofSeconds(10)); }
        public static Timeouts from(Properties p) {
            return new Timeouts(seconds(p, "LawLoadTimeoutSeconds", 30), seconds(p, "LawPrepareTimeoutSeconds", 120),
                    seconds(p, "LawStartTimeoutSeconds", 120), seconds(p, "LawStopTimeoutSeconds", 10));
        }
        private static Duration seconds(Properties p, String key, long fallback) {
            long seconds = Long.parseLong(p.getProperty(key, Long.toString(fallback)));
            if (seconds <= 0) throw new IllegalArgumentException(key + " must be positive");
            return Duration.ofSeconds(seconds);
        }
    }
    private final Path directory;
    private final Timeouts timeouts;
    private final ScheduledThreadPoolExecutor coordinator = new ScheduledThreadPoolExecutor(1, r -> daemon(r, "Law-coordinator"));
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, String> rejected = new LinkedHashMap<>();
    private final CompletableFuture<StartupReport> report = new CompletableFuture<>();
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private boolean started;
    private volatile boolean stopping;
    private static Thread daemon(Runnable r, String name) { Thread t = new Thread(r, name); t.setDaemon(true); return t; }
    private final class Node {
        final LawCFG cfg;
        final ExecutorService worker;
        final CompletableFuture<Boolean> prepared = new CompletableFuture<>();
        final CompletableFuture<Boolean> ready = new CompletableFuture<>();
        final CompletableFuture<Void> stopDone = new CompletableFuture<>();
        volatile Thread thread;
        volatile boolean executing;
        volatile Law law;
        URLClassLoader loader;
        State state = State.LOADING;
        String detail = "";
        volatile int generation;
        ScheduledFuture<?> deadline;
        long began;
        Node(LawCFG cfg) {
            this.cfg = cfg;
            worker = Executors.newSingleThreadExecutor(r -> { thread = daemon(r, "Law-" + cfg.name); return thread; });
        }
    }
    public LawsManager() { this(Path.of("laws"), Timeouts.defaults()); }
    public LawsManager(Path directory, Timeouts timeouts) {
        this.directory = directory; this.timeouts = timeouts;
        coordinator.setRemoveOnCancelPolicy(true);
    }
    /** Keeps the host alive independently of daemon plugin initialization workers. */
    public CompletableFuture<Void> termination() { return stopped.copy(); }

    private void dispatch(Runnable action) {
        try { coordinator.execute(action); }
        catch (RejectedExecutionException e) { if (!stopping) throw e; }
    }
    public synchronized CompletableFuture<StartupReport> startLawsAsync() {
        if (!started) {
            started = true;
            dispatch(() -> {
                try { discover(); validate(); wireAndStart(); }
                catch (Exception | LinkageError e) { report.completeExceptionally(e); }
            });
        }
        return report.copy();
    }
    private void discover() throws IOException {
        File[] files = directory.toFile().listFiles((d, n) -> n.endsWith(".jar"));
        if (files == null) {
            if (java.nio.file.Files.exists(directory)) throw new IOException("Cannot scan laws directory: " + directory);
            return;
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        Set<String> duplicates = new HashSet<>();
        for (File file : files) {
            try (JarFile jar = new JarFile(file)) {
                var entry = jar.getJarEntry("law.yml");
                if (entry == null) throw new IllegalArgumentException("Missing law.yml");
                LawCFG cfg;
                try (InputStream in = jar.getInputStream(entry)) { cfg = new Yaml().loadAs(in, LawCFG.class); }
                if (cfg == null || cfg.name == null || cfg.name.isBlank() || cfg.main == null || cfg.main.isBlank())
                    throw new IllegalArgumentException("Missing name/main");
                cfg.dependencies = normalized(cfg.dependencies);
                cfg.prepareAfter = normalized(cfg.prepareAfter);
                cfg.url = file.toURI().toURL();
                if (nodes.containsKey(cfg.name)) {
                    duplicates.add(cfg.name);
                    rejected.put(file.getName(), "Duplicate law name: " + cfg.name);
                    rejected.put(Path.of(nodes.get(cfg.name).cfg.url.toURI()).getFileName().toString(),
                            "Duplicate law name: " + cfg.name);
                } else nodes.put(cfg.name, new Node(cfg));
            } catch (Exception | LinkageError e) {
                rejected.put(file.getName(), e.toString());
                logger.error("读取法则元数据 {} 失败", file, e);
            }
        }
        for (String name : duplicates) fail(nodes.get(name), State.FAILED, "Duplicate law name: " + name);
    }
    private static List<String> normalized(List<String> names) {
        if (names == null) return List.of();
        for (String name : names) if (name == null || name.isBlank()) throw new IllegalArgumentException("Empty dependency name");
        return List.copyOf(new LinkedHashSet<>(names));
    }
    private List<String> edges(Node n) {
        Set<String> names = new LinkedHashSet<>(n.cfg.dependencies); names.addAll(n.cfg.prepareAfter);
        names.retainAll(nodes.keySet()); return List.copyOf(names);
    }
    private void validate() {
        for (Node n : nodes.values()) for (String dep : n.cfg.dependencies)
            if (!nodes.containsKey(dep)) fail(n, State.DEPENDENCY_UNAVAILABLE, "Missing dependency: " + dep);
        Map<String, Integer> indices = new HashMap<>(), low = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new HashSet<>();
        for (String name : nodes.keySet())
            if (!indices.containsKey(name)) visit(name, indices, low, stack, onStack);
    }
    // Strongly connected components also catch overlapping cycles, including optional ordering edges.
    private void visit(String name, Map<String, Integer> indices, Map<String, Integer> low,
                       Deque<String> stack, Set<String> onStack) {
        int index = indices.size();
        indices.put(name, index); low.put(name, index); stack.push(name); onStack.add(name);
        for (String dep : edges(nodes.get(name))) {
            if (!indices.containsKey(dep)) {
                visit(dep, indices, low, stack, onStack);
                low.put(name, Math.min(low.get(name), low.get(dep)));
            } else if (onStack.contains(dep)) low.put(name, Math.min(low.get(name), indices.get(dep)));
        }
        if (low.get(name).equals(indices.get(name))) {
            List<String> component = new ArrayList<>();
            String member;
            do { member = stack.pop(); onStack.remove(member); component.add(member); } while (!member.equals(name));
            if (component.size() > 1 || edges(nodes.get(name)).contains(name))
                for (String id : component) fail(nodes.get(id), State.FAILED, "Dependency cycle: " + component);
        }
    }
    private void wireAndStart() {
        CompletableFuture.allOf(nodes.values().stream().map(n -> n.ready).toArray(CompletableFuture[]::new))
                .thenRun(() -> {
                    Map<String, Result> results = new LinkedHashMap<>();
                    nodes.forEach((id, n) -> results.put(id, new Result(n.state, n.detail, n.executing)));
                    logger.info("宇宙法则启动结果: {}，拒绝的 JAR: {}", results, rejected);
                    report.complete(new StartupReport(results, rejected));
                });
        for (Node n : nodes.values()) {
            if (n.ready.isDone()) continue;
            LinkedHashSet<URL> urls = new LinkedHashSet<>(); gather(n, urls);
            n.loader = new URLClassLoader(urls.toArray(URL[]::new), getClass().getClassLoader());
            stage(n, State.LOADING, timeouts.load, () -> {
                Law law = (Law) Class.forName(n.cfg.main, true, n.loader).getDeclaredConstructor().newInstance();
                n.law = law; law.ID = n.cfg.name;
                if (law.Dependencies != null && law.Dependencies.length != 0)
                    throw new IllegalArgumentException("Migrate Law.Dependencies to law.yml dependencies");
            }, () -> awaitDependencies(n, false));
        }
    }
    private void gather(Node n, Set<URL> urls) {
        if (!urls.add(n.cfg.url)) return;
        for (String dep : n.cfg.dependencies) { Node d = nodes.get(dep); if (d != null) gather(d, urls); }
    }
    private void awaitDependencies(Node n, boolean running) {
        if (n.ready.isDone() || stopping) return;
        n.state = State.WAITING_DEPENDENCIES;
        List<CompletableFuture<Boolean>> waits = new ArrayList<>();
        for (String id : n.cfg.dependencies) {
            Node dep = nodes.get(id);
            CompletableFuture<Boolean> f = running ? dep.ready : dep.prepared;
            waits.add(f);
            f.thenAccept(ok -> { if (!ok) fail(n, State.DEPENDENCY_UNAVAILABLE, "Dependency unavailable: " + id); });
        }
        if (!running) for (String id : n.cfg.prepareAfter) if (nodes.containsKey(id)) waits.add(nodes.get(id).prepared);
        CompletableFuture.allOf(waits.toArray(CompletableFuture[]::new)).thenRun(() -> {
            if (n.ready.isDone() || stopping) return;
            if (running) start(n);
            else stage(n, State.PREPARING, timeouts.prepare, () -> {
                if (!n.law.prepare()) throw new IllegalStateException("prepare() returned false");
            }, () -> {
                n.law.ableToRun = true; n.law.isPrepared = true;
                n.prepared.complete(true); awaitDependencies(n, true);
            });
        });
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private void stage(Node n, State state, Duration timeout, Action action, Runnable success) {
        if (stopping || n.ready.isDone()) return;
        n.state = state;
        int generation = ++n.generation;
        n.worker.execute(() -> {
            if (stopping || n.generation != generation || n.ready.isDone()) return;
            n.executing = true;
            dispatch(() -> begin(n, generation, timeout));
            ClassLoader original = Thread.currentThread().getContextClassLoader();
            Throwable failure = null;
            try { Thread.currentThread().setContextClassLoader(n.loader); action.run(); }
            catch (Throwable e) { failure = e; }
            finally { Thread.currentThread().setContextClassLoader(original); n.executing = false; }
            Throwable error = failure;
            dispatch(() -> {
                if (n.generation != generation || n.ready.isDone() || stopping) return;
                cancelDeadline(n);
                if (error != null) { logger.error("法则 {} {} 失败", n.cfg.name, state, error); fail(n, State.FAILED, state + ": " + error); }
                else { elapsed(n, state); success.run(); }
            });
        });
    }
    private void begin(Node n, int generation, Duration timeout) {
        if (n.generation != generation || n.ready.isDone() || stopping) { if (n.thread != null) n.thread.interrupt(); return; }
        n.began = System.nanoTime();
        logger.debug("法则 {} 进入 {}", n.cfg.name, n.state);
        n.deadline = coordinator.schedule(() -> {
            if (n.generation != generation || n.ready.isDone()) return;
            fail(n, State.TIMED_OUT, n.state + " timeout; stopped waiting, code may still execute");
            if (n.thread != null) n.thread.interrupt();
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
    private void start(Node n) {
        // Unlike other stages, an explicit readiness signal may finish while run() is still executing.
        n.state = State.STARTING;
        int generation = ++n.generation;
        n.worker.execute(() -> {
            if (stopping || n.generation != generation || n.ready.isDone()) return;
            n.executing = true;
            dispatch(() -> begin(n, generation, timeouts.start));
            ClassLoader original = Thread.currentThread().getContextClassLoader();
            try {
                Thread.currentThread().setContextClassLoader(n.loader);
                CompletionStage<Void> readiness = n.law.readiness();
                if (readiness != null) readiness.whenComplete((v, e) -> dispatch(() -> finishStart(n, generation, e)));
                n.law.run();
                if (readiness == null) dispatch(() -> finishStart(n, generation, null));
            } catch (Throwable e) { dispatch(() -> finishStart(n, generation, e)); }
            finally { Thread.currentThread().setContextClassLoader(original); n.executing = false; }
        });
    }
    private void finishStart(Node n, int generation, Throwable error) {
        if (n.generation != generation || stopping) return;
        if (n.ready.isDone()) {
            if (error != null) {
                n.state = State.FAILED; n.detail = "Runtime failure after readiness: " + error;
                n.law.isRunning = false; n.law.ableToRun = false;
                logger.error("已报告就绪的法则 {} 后续运行异常", n.cfg.name, error);
            }
            return;
        }
        cancelDeadline(n);
        if (error != null) { logger.error("法则 {} 启动失败", n.cfg.name, error); fail(n, State.FAILED, "STARTING: " + error); }
        else {
            elapsed(n, State.STARTING); n.state = State.READY; n.law.isRunning = true;
            logger.debug("法则 {} 启动成功", n.cfg.name); n.ready.complete(true);
        }
    }
    private void elapsed(Node n, State state) { logger.debug("法则 {} {} 完成，耗时 {} ms", n.cfg.name, state, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - n.began)); }
    private void cancelDeadline(Node n) { if (n.deadline != null) { n.deadline.cancel(false); n.deadline = null; } }
    private void fail(Node n, State state, String detail) {
        if (n.ready.isDone()) return;
        cancelDeadline(n); ++n.generation; n.state = state; n.detail = detail;
        if (n.law != null) { n.law.ableToRun = false; n.law.isRunning = false; }
        logger.warn("法则 {} {}: {} (当前阶段已用 {} ms)", n.cfg.name, state, detail,
                n.began == 0 ? 0 : TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - n.began));
        n.prepared.complete(false); n.ready.complete(false);
    }
    /** Stops dependents before dependencies, with independent bounded cleanup for each law. */
    public CompletableFuture<Void> stopLawsAsync() {
        synchronized (this) {
            if (stopping) return stopped.copy();
            stopping = true;
            if (!started) { started = true; report.complete(new StartupReport(Map.of(), Map.of())); }
        }
        dispatch(() -> {
            for (Node n : nodes.values()) fail(n, State.STOPPED, "Host stopping");
            List<Node> order = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Node n : nodes.values()) stopOrder(n, seen, order);
            Collections.reverse(order);
            // All stop futures exist before wiring; cycle participants never ran and need no ordering.
            for (Node n : order) {
                List<CompletableFuture<Void>> dependents = new ArrayList<>();
                for (Node other : nodes.values())
                    if (other.loader != null && other != n && edges(other).contains(n.cfg.name)) dependents.add(other.stopDone);
                CompletableFuture.allOf(dependents.toArray(CompletableFuture[]::new)).thenRun(() -> stopNode(n));
            }
            CompletableFuture.allOf(nodes.values().stream().map(n -> n.stopDone).toArray(CompletableFuture[]::new))
                    .thenRun(() -> { stopped.complete(null); coordinator.shutdown(); });
        });
        return stopped.copy();
    }
    private void stopOrder(Node n, Set<String> seen, List<Node> order) {
        if (!seen.add(n.cfg.name)) return;
        for (String dep : edges(n)) stopOrder(nodes.get(dep), seen, order);
        order.add(n);
    }
    private void stopNode(Node n) {
        cancelDeadline(n);
        if (n.loader == null) {
            n.worker.shutdown(); n.state = State.STOPPED; n.stopDone.complete(null); return;
        }
        if (n.executing && n.thread != null) n.thread.interrupt();
        // Stop is queued on the same worker, so it cannot overlap a constructor/prepare/run.
        // The deadline covers queue delay too: a non-cooperative run must not block shutdown.
        ScheduledFuture<?> timeout = coordinator.schedule(() -> {
            logger.warn("法则 {} 停止超时；停止等待，代码可能仍在执行", n.cfg.name);
            if (n.thread != null) n.thread.interrupt();
            n.stopDone.complete(null);
        }, timeouts.stop.toMillis(), TimeUnit.MILLISECONDS);
        n.worker.execute(() -> {
            ClassLoader original = Thread.currentThread().getContextClassLoader();
            n.executing = true;
            try {
                Thread.currentThread().setContextClassLoader(n.loader);
                if (n.law != null) n.law.stop();
            } catch (Throwable e) { logger.error("停止法则 {} 失败", n.cfg.name, e); }
            finally {
                n.executing = false;
                if (n.law != null) { n.law.isRunning = false; n.law.ableToRun = false; }
                close(n); Thread.currentThread().setContextClassLoader(original);
                timeout.cancel(false);
                if (!coordinator.isShutdown()) {
                    try { dispatch(() -> { n.state = State.STOPPED; n.stopDone.complete(null); }); }
                    catch (RejectedExecutionException ignored) { /* Shutdown already timed out. */ }
                }
            }
        });
        n.worker.shutdown();
    }
    private void close(Node n) {
        if (n.loader != null) try { n.loader.close(); } catch (IOException e) { logger.warn("关闭法则 {} classloader 失败", n.cfg.name, e); }
    }
    public void stopLaws() { stopLawsAsync().join(); }
    /** Loaders are closed only after their lifecycle invocation has exited. */
    public void closeAllLoaders() { stopLaws(); }
}

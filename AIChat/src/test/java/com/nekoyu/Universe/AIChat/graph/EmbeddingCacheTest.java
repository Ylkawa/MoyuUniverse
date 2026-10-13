package com.nekoyu.Universe.AIChat.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class EmbeddingCacheTest {
    @TempDir Path directory;
    private final IconEmbeddingProvider.Space space = new IconEmbeddingProvider.Space("test", "independent-v1", 2);
    @Test void persistsAcrossInstancesAndDoesNotNormalizeTextOrDependOnCatalog() throws Exception {
        var calls = new AtomicInteger();
        var cache = new EmbeddingCache(directory, space, 1);
        assertArrayEquals(new double[]{1, 0}, cache.get("Laptop", () -> { calls.incrementAndGet(); return new double[]{4, 0}; }));
        cache.get("laptop", () -> { calls.incrementAndGet(); return new double[]{0, 3}; });
        assertArrayEquals(new double[]{1, 0}, new EmbeddingCache(directory, space).get("Laptop", () -> { throw new IOException("offline"); }));
        assertEquals(2, calls.get());
        var changed = new EmbeddingCache(directory, new IconEmbeddingProvider.Space("test", "independent-v2", 2));
        assertThrows(IOException.class, () -> changed.get("Laptop", () -> { throw new IOException("missing"); }));
    }
    @Test void coalescesConcurrentInstancesAndDoesNotCacheFailures() throws Exception {
        var calls = new AtomicInteger(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(6);
        try {
            var tasks = new java.util.ArrayList<Future<double[]>>();
            for (int i = 0; i < 6; i++) tasks.add(pool.submit(() -> new EmbeddingCache(directory, space).get("same", () -> {
                calls.incrementAndGet(); entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("timeout"); }
                catch (InterruptedException e) { throw new IOException(e); }
                return new double[]{1, 0};
            })));
            assertTrue(entered.await(5, TimeUnit.SECONDS)); release.countDown();
            for (var task : tasks) assertArrayEquals(new double[]{1, 0}, task.get(5, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        } finally { release.countDown(); pool.shutdownNow(); }
        var cache = new EmbeddingCache(directory, space);
        assertThrows(IOException.class, () -> cache.get("failed", () -> { throw new IOException("network"); }));
        assertFalse(Files.exists(cache.entryPath("failed")));
        assertArrayEquals(new double[]{0, 1}, cache.get("failed", () -> new double[]{0, 1}));
    }
    @Test void recoversCorruptionAndContinuesOnWriteFailure() throws Exception {
        var cache = new EmbeddingCache(directory, space);
        cache.get("broken", () -> new double[]{1, 0});
        Files.write(cache.entryPath("broken"), new byte[]{0, 1, 2});
        assertArrayEquals(new double[]{0, 1}, new EmbeddingCache(directory, space).get("broken", () -> new double[]{0, 1}));
        Path blocked = directory.resolve("file"); Files.writeString(blocked, "not a directory");
        var memoryOnly = new EmbeddingCache(blocked, space);
        assertArrayEquals(new double[]{1, 0}, memoryOnly.get("x", () -> new double[]{1, 0}));
        assertArrayEquals(new double[]{1, 0}, memoryOnly.get("x", () -> { throw new IOException("must use memory"); }));
    }
    @Test void preservesCachedIconsWhenOtherDescriptionsFailWithoutAKey() throws Exception {
        var catalog = GraphTestFixtures.catalog(directory.resolve("catalog"));
        var testProvider = new GraphTestFixtures.Provider();
        var index = new LocalIconIndex(IndexDescriptor.of(testProvider.space(), catalog), catalog.icons(), new float[]{1, 0, 0, 1});
        var cached = new CachedEmbeddingProvider(testProvider.space(), directory.resolve("text"), () -> testProvider);
        cached.embedTexts(List.of("laptop"));
        var offline = new CachedEmbeddingProvider(testProvider.space(), directory.resolve("text"), () -> { throw new IOException("no key"); });
        var resolved = new GraphMatchService(offline, index, catalog).resolve(new GraphParser().parse(
                "<graph type='list'><line icon='laptop'>cached</line><line icon='new'>offline</line></graph>"));
        assertEquals(ResolvedGraph.Status.CANDIDATES, resolved.matches().get(0).status());
        assertEquals(ResolvedGraph.Status.UNAVAILABLE, resolved.matches().get(1).status());
        assertEquals(1, testProvider.textCalls);
    }
}

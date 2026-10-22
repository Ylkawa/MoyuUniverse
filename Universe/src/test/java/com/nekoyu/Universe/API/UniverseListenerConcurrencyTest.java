package com.nekoyu.Universe.API;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class UniverseListenerConcurrencyTest {
    @Test void exactAndWildcardCallbacksUseSnapshotsOutsideRegistrationLocks() throws Exception {
        String key = "listener-" + UUID.randomUUID();
        AtomicInteger wildCalls = new AtomicInteger();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        UniverseListener wildcard = (p, m, a, r) -> wildCalls.incrementAndGet();
        UniverseListener exact = (p, m, a, r) -> {
            try { pool.submit(() -> UniverseChannel.registerListener(key + "*", wildcard)).get(3, TimeUnit.SECONDS); }
            catch (Exception e) { throw new AssertionError("Registration blocked during callback", e); }
        };
        try {
            UniverseChannel.registerListener(key, exact);
            UniverseChannelMessage message = new UniverseChannelMessage(); message.tag = key;
            UniverseChannel.dispatchInternal(new Planet(), message, "{}"); assertEquals(0, wildCalls.get());
            UniverseChannel.dispatchInternal(new Planet(), message, "{}"); assertEquals(1, wildCalls.get());
        } finally {
            UniverseChannel.unRegisterListener(key, exact);
            UniverseChannel.unRegisterListener(key + "*", wildcard);
            UniverseChannel.unRegisterListener(key + "*", wildcard);
            pool.shutdownNow();
        }
    }
    @Test void concurrentRegistrationDoesNotLoseCallbacks() throws Exception {
        String key = "listener-" + UUID.randomUUID();
        List<UniverseListener> registered = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < 100; i++) tasks.add(pool.submit(() -> {
                UniverseListener listener = (p, m, a, r) -> calls.incrementAndGet();
                registered.add(listener); UniverseChannel.registerListener(key, listener);
            }));
            for (var task : tasks) task.get(5, TimeUnit.SECONDS);
            UniverseChannelMessage message = new UniverseChannelMessage(); message.tag = key;
            UniverseChannel.dispatchInternal(new Planet(), message, "{}"); assertEquals(100, calls.get());
        } finally {
            registered.forEach(listener -> UniverseChannel.unRegisterListener(key, listener)); pool.shutdownNow();
        }
    }
}

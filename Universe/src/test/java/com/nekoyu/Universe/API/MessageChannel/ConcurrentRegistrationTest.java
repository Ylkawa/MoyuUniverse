package com.nekoyu.Universe.API.MessageChannel;

import com.nekoyu.Universe.Universe;
import com.nekoyu.Universe.API.MessageChannel.events.MCEvent;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ConcurrentRegistrationTest {
    @Test void concurrentRegistrationAndDispatchDoNotLoseListeners() throws Exception {
        String key = "concurrent-" + UUID.randomUUID();
        AtomicInteger callbacks = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        MCEvent event = new MCEvent(); event.eventId = key;
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                int id = i;
                tasks.add(pool.submit(() -> {
                    try { start.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
                    Universe.Providers.put(key + id, id);
                    MessageChannelManager.listenToEvent(key, e -> callbacks.incrementAndGet());
                    MessageChannelManager.onEvent(event);
                }));
            }
            start.countDown();
            for (Future<?> task : tasks) task.get(5, TimeUnit.SECONDS);
            callbacks.set(0); MessageChannelManager.onEvent(event);
            assertEquals(100, callbacks.get());
            for (int i = 0; i < 100; i++) assertEquals(i, Universe.Providers.get(key + i));
        } finally {
            pool.shutdownNow(); MessageChannelManager.eventListeners.removeAll(key);
            for (int i = 0; i < 100; i++) Universe.Providers.remove(key + i);
        }
    }
    @Test void callbackMayRegisterOnAnotherThreadWithoutHoldingDispatchLock() throws Exception {
        String key = "snapshot-" + UUID.randomUUID();
        AtomicInteger added = new AtomicInteger();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            MessageChannelManager.listenToEvent(key, e -> {
                try { pool.submit(() -> MessageChannelManager.listenToEvent(key, next -> added.incrementAndGet())).get(3, TimeUnit.SECONDS); }
                catch (Exception error) { throw new AssertionError("Callback held the registration lock", error); }
            });
            MCEvent event = new MCEvent(); event.eventId = key;
            MessageChannelManager.onEvent(event); assertEquals(0, added.get());
            MessageChannelManager.onEvent(event); assertEquals(1, added.get());
        } finally { pool.shutdownNow(); MessageChannelManager.eventListeners.removeAll(key); }
    }
}

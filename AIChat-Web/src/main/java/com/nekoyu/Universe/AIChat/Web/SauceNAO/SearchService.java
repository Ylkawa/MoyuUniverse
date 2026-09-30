package com.nekoyu.Universe.AIChat.Web.SauceNAO;

import okhttp3.HttpUrl;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/** One instance per registered tool, shared by all conversations. No persistent state. */
public final class SearchService {
    @FunctionalInterface
    public interface Query { SearchResponse search(String url) throws IOException; }
    private record Cached(String text, long expires) {}
    private static final long SHORT_WINDOW = 30_000, LONG_WINDOW = 86_400_000;
    private final Query query;
    private final LongSupplier clock;
    private final int shortLimit, longLimit, capacity;
    private final long ttl;
    private final LinkedHashMap<String, Cached> cache = new LinkedHashMap<>(16, .75f, true);
    private final Map<String, CompletableFuture<String>> pending = new HashMap<>();
    private final Deque<Long> shortCalls = new ArrayDeque<>(), longCalls = new ArrayDeque<>();
    private long shortBlockedUntil, longBlockedUntil;

    public SearchService(Query query, int shortLimit, int longLimit) {
        this(query, shortLimit, longLimit, 256, 600_000,
                () -> System.nanoTime() / 1_000_000);
    }

    public SearchService(Query query, int shortLimit, int longLimit, int capacity, long ttl, LongSupplier clock) {
        if (shortLimit <= 0 || longLimit <= 0 || capacity <= 0 || ttl <= 0)
            throw new IllegalArgumentException("SauceNAO limits must be positive");
        this.query = Objects.requireNonNull(query);
        this.clock = Objects.requireNonNull(clock);
        this.shortLimit = shortLimit;
        this.longLimit = longLimit;
        this.capacity = capacity;
        this.ttl = ttl;
    }

    public String search(String url) {
        HttpUrl parsed = url == null ? null : HttpUrl.parse(url);
        if (parsed == null || !parsed.username().isEmpty() || !parsed.password().isEmpty())
            return "SauceNAO 参数错误：需要 HTTP/HTTPS 图片直链或有效图片引用";
        CompletableFuture<String> future;
        boolean owner;
        synchronized (this) {
            long now = clock.getAsLong();
            cache.entrySet().removeIf(e -> e.getValue().expires <= now);
            Cached hit = cache.get(url);
            if (hit != null) return "缓存结果（配额为查询时快照）\n" + hit.text;
            future = pending.get(url);
            owner = future == null;
            if (owner) {
                prune(shortCalls, now - SHORT_WINDOW);
                prune(longCalls, now - LONG_WINDOW);
                if (now < shortBlockedUntil || shortCalls.size() >= shortLimit)
                    return "SauceNAO 限流：30 秒窗口额度不足，请稍后再试";
                if (now < longBlockedUntil || longCalls.size() >= longLimit)
                    return "SauceNAO 限流：24 小时窗口额度不足，请稍后再试";
                shortCalls.addLast(now);
                longCalls.addLast(now);
                future = new CompletableFuture<>();
                pending.put(url, future);
            }
        }
        if (!owner) return future.join();
        String text;
        try {
            SearchResponse response = query.search(url);
            if (response == null || response.header == null || response.header.status == null
                    || response.header.status < 0) throw new IOException("Invalid response");
            text = response.toString();
            synchronized (this) {
                long now = clock.getAsLong();
                if (Integer.valueOf(0).equals(response.header.short_remaining))
                    shortBlockedUntil = Math.max(shortBlockedUntil, now + SHORT_WINDOW);
                if (Integer.valueOf(0).equals(response.header.long_remaining))
                    longBlockedUntil = Math.max(longBlockedUntil, now + LONG_WINDOW);
                if (response.header.status == 0) {
                    cache.put(url, new Cached(text, now + ttl));
                    while (cache.size() > capacity) cache.remove(cache.keySet().iterator().next());
                }
            }
        } catch (Client.QuotaException error) {
            synchronized (this) {
                long now = clock.getAsLong();
                shortBlockedUntil = Math.max(shortBlockedUntil, now + SHORT_WINDOW);
                if (error.longExhausted) longBlockedUntil = Math.max(longBlockedUntil, now + LONG_WINDOW);
            }
            text = "SauceNAO 限流：服务端请求限制或配额不足，请稍后再试";
        } catch (IOException | RuntimeException error) {
            text = "SauceNAO 查询失败，暂时无法确认";
        } catch (Error error) {
            synchronized (this) { future.completeExceptionally(error); pending.remove(url); }
            throw error;
        }
        synchronized (this) {
            future.complete(text);
            pending.remove(url);
        }
        return text;
    }

    private static void prune(Deque<Long> calls, long cutoff) {
        while (!calls.isEmpty() && calls.peekFirst() <= cutoff) calls.removeFirst();
    }
}

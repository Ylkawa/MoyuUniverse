import com.nekoyu.Universe.AIChat.Web.SauceNAO.*;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Offline deterministic cache, concurrency and quota checks; no API requests. */
public class SauceNaoServiceTest {
    static final String A = "https://example.com/a", B = "https://example.com/b";
    static SearchResponse response(int status, Integer shortRemaining, Integer longRemaining) {
        SearchResponse r = new SearchResponse();
        r.header = new SearchResponse.Header();
        r.header.status = status;
        r.header.short_remaining = shortRemaining;
        r.header.long_remaining = longRemaining;
        return r;
    }
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        AtomicLong clock = new AtomicLong();
        AtomicInteger calls = new AtomicInteger();
        SearchService.Query query = url -> { calls.incrementAndGet(); return response(0, 3, 99); };
        SearchService cache = new SearchService(query, 1000, 1000, 2, 100, clock::get);
        check(cache.search("<quote:1>").contains("参数错误"), "unresolved reference");
        check(cache.search("https://user:pass@example.com/a").contains("参数错误"), "credentials rejected");
        check(calls.get() == 0, "invalid input spends no quota");
        check(cache.search(A).contains("未找到匹配来源"), "empty result");
        check(cache.search(A).contains("查询时快照") && calls.get() == 1, "empty cached");
        clock.set(100);
        cache.search(A);
        check(calls.get() == 2, "expiry boundary");
        cache.search(B); cache.search(A); cache.search("https://example.com/c"); cache.search(B);
        check(calls.get() == 5, "LRU eviction");

        clock.set(0);
        SearchService limited = new SearchService(query, 1, 2, 2, 1, clock::get);
        limited.search(A);
        check(limited.search(B).contains("30 秒"), "short limit");
        clock.set(30_000); limited.search(B);
        clock.set(60_000);
        check(limited.search(A).contains("24 小时"), "long limit");
        clock.set(86_400_000);
        check(!limited.search(A).contains("限流"), "long window expiry");

        clock.set(0);
        SearchService exhausted = new SearchService(url -> response(0, 0, 0), 10, 100, 2, 100, clock::get);
        exhausted.search(A);
        check(exhausted.search(A).contains("缓存"), "cached despite exhaustion");
        check(exhausted.search(B).contains("30 秒"), "server short zero");
        clock.set(30_000);
        check(exhausted.search(B).contains("24 小时"), "server long zero");
        clock.set(86_400_000);
        check(!exhausted.search(B).contains("限流"), "server block expires");

        AtomicInteger partialCalls = new AtomicInteger();
        SearchService partial = new SearchService(url -> {
            partialCalls.incrementAndGet(); return response(1, 3, 99);
        }, 10, 100);
        check(partial.search(A).contains("部分索引异常"), "partial summary");
        partial.search(A);
        check(partialCalls.get() == 2, "partial not cached");
        AtomicInteger failures = new AtomicInteger();
        SearchService failing = new SearchService(url -> {
            failures.incrementAndGet(); throw new IOException("secret");
        }, 10, 100);
        check(!failing.search(A).contains("secret"), "error redacted"); failing.search(A);
        check(failures.get() == 2, "failure not cached");
        SearchService quota = new SearchService(url -> { throw new Client.QuotaException(true); },
                10, 100, 2, 100, clock::get);
        check(quota.search(A).contains("限流"), "quota exception");
        clock.addAndGet(30_000);
        check(quota.search(B).contains("24 小时"), "negative API quota blocks long window");

        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger parallelCalls = new AtomicInteger();
        SearchService parallel = new SearchService(url -> {
            parallelCalls.incrementAndGet(); entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("timeout"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            return response(0, 3, 99);
        }, 1, 100);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Thread> follower = new AtomicReference<>();
        try {
            Future<String> first = pool.submit(() -> parallel.search(A));
            check(entered.await(5, TimeUnit.SECONDS), "query entered");
            Future<String> second = pool.submit(() -> {
                follower.set(Thread.currentThread());
                return parallel.search(A);
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((follower.get() == null || follower.get().getState() != Thread.State.WAITING)
                    && System.nanoTime() < deadline) Thread.yield();
            check(follower.get() != null && follower.get().getState() == Thread.State.WAITING,
                    "follower waits on in-flight request before cache exists");
            // A different URL must fail immediately while the first request is running.
            check(parallel.search(B).contains("限流"), "no queue for different URL");
            release.countDown();
            check(first.get(5, TimeUnit.SECONDS).contains("未找到")
                    && second.get(5, TimeUnit.SECONDS).contains("未找到"), "shared result");
            check(parallelCalls.get() == 1, "single flight");
        } finally { release.countDown(); pool.shutdownNow(); }
        System.out.println("SauceNaoServiceTest passed");
    }
}

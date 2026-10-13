package com.nekoyu.Universe.AIChat;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 可控时钟验证排队、旧回调、推理互斥与会话隔离，不访问模型服务。 */
public class SessionReplyQueueTest {
    public static void main(String[] args) throws Exception {
        debounceAndStaleCallback();
        messagesDuringInference(false);
        messagesDuringInference(true);
        userMergeAndFailure();
        closeAndBlockedPreparation();
        concurrentInputs();
        independentSessions();
        System.out.println("SessionReplyQueueTest passed");
    }

    static SessionReplyQueue queue(ManualTime time, Runnable reply, List<RuntimeException> failures) {
        return new SessionReplyQueue(() -> time.now, time::timer, time, driven -> true, reply, failures::add);
    }

    private static void debounceAndStaleCallback() {
        ManualTime time = new ManualTime();
        List<String> inputs = new ArrayList<>();
        AtomicInteger replies = new AtomicInteger();
        SessionReplyQueue queue = queue(time, replies::incrementAndGet, new ArrayList<>());
        time.advance(10_000);
        check(replies.get() == 0, "empty queue never infers");
        queue.drive(() -> inputs.add("first"));
        time.drainWorkers();
        Runnable stale = time.timers.peek().action;
        time.advance(4_000);
        queue.drive(() -> inputs.add("second"));
        time.drainWorkers();
        time.advance(1_000);
        stale.run();
        time.drainWorkers();
        check(replies.get() == 0, "stale timer cannot start a reply");
        time.advance(3_999);
        check(replies.get() == 0, "wait a full five seconds after the latest input");
        time.advance(1);
        check(replies.get() == 1 && inputs.equals(List.of("first", "second")), "one ordered batch");
        time.advance(20_000);
        check(replies.get() == 1 && !queue.hasWork(), "consumed batch does not infer again");
    }

    private static void messagesDuringInference(boolean expired) {
        ManualTime time = new ManualTime();
        AtomicInteger replies = new AtomicInteger();
        List<String> inputs = new ArrayList<>();
        SessionReplyQueue[] holder = new SessionReplyQueue[1];
        holder[0] = queue(time, () -> {
            if (replies.incrementAndGet() == 1) {
                holder[0].drive(() -> inputs.add("during inference"));
                time.advance(expired ? 6_000 : 2_000);
                check(inputs.isEmpty() && replies.get() == 1, "queue input until current inference finishes");
            }
        }, new ArrayList<>());
        holder[0].requestReply();
        time.drainWorkers();
        check(inputs.size() == 1, "flush input when idle");
        if (!expired) {
            check(replies.get() == 1, "wait only the remaining time");
            time.advance(2_999);
            check(replies.get() == 1, "not before deadline");
            time.advance(1);
        }
        check(replies.get() == 2, "expired deadline resumes immediately after inference");
    }

    private static void userMergeAndFailure() {
        ManualTime time = new ManualTime();
        AtomicInteger replies = new AtomicInteger();
        List<RuntimeException> failures = new ArrayList<>();
        SessionReplyQueue queue = queue(time, () -> {
            if (replies.incrementAndGet() == 1) throw new IllegalStateException("fake failure");
        }, failures);
        queue.drive(() -> {});
        time.drainWorkers();
        time.advance(4_000);
        check(queue.mergeUserTrigger(), "user trigger joins driver batch");
        time.advance(4_999);
        check(replies.get() == 0, "user trigger resets deadline");
        time.advance(1);
        time.advance(20_000);
        check(replies.get() == 1 && failures.size() == 1, "failed inference is not retried");
        check(!queue.mergeUserTrigger(), "ordinary user timing returns after driver batch");
        queue.drive(() -> {});
        time.advance(5_000);
        check(replies.get() == 2, "new input survives a previous failure");
    }

    private static void closeAndBlockedPreparation() {
        ManualTime time = new ManualTime();
        AtomicInteger replies = new AtomicInteger();
        SessionReplyQueue queue = queue(time, replies::incrementAndGet, new ArrayList<>());
        queue.drive(() -> {});
        time.drainWorkers();
        Runnable stale = time.timers.peek().action;
        queue.close();
        stale.run();
        time.advance(5_000);
        check(replies.get() == 0 && !queue.hasWork(), "closed queue cannot wake or reply");
        SessionReplyQueue blocked = new SessionReplyQueue(() -> time.now, time::timer, time,
                driven -> driven, replies::incrementAndGet, e -> { throw e; });
        blocked.requestReply();
        time.drainWorkers();
        check(!blocked.hasWork() && replies.get() == 0, "blocked ordinary reply does not spin");
        blocked.drive(() -> {});
        time.advance(5_000);
        check(replies.get() == 1, "driver can bypass background-task gating");
    }

    private static void concurrentInputs() throws Exception {
        ManualTime time = new ManualTime();
        List<Integer> inputs = new ArrayList<>();
        AtomicInteger replies = new AtomicInteger();
        SessionReplyQueue queue = queue(time, replies::incrementAndGet, new ArrayList<>());
        ExecutorService senders = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                int id = i;
                tasks.add(senders.submit(() -> queue.drive(() -> inputs.add(id))));
            }
            for (Future<?> task : tasks) task.get(3, TimeUnit.SECONDS);
            time.advance(5_000);
            check(inputs.size() == 100 && new HashSet<>(inputs).size() == 100 && replies.get() == 1,
                    "concurrent arrivals are neither lost nor duplicated");
        } finally {
            senders.shutdownNow();
        }
    }

    private static void independentSessions() throws Exception {
        ManualTime time = new ManualTime();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch otherReply = new CountDownLatch(1);
        SessionReplyQueue first = new SessionReplyQueue(() -> time.now, time::timer, workers,
                driven -> true, () -> {
                    entered.countDown();
                    try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }, e -> { throw e; });
        SessionReplyQueue second = new SessionReplyQueue(() -> time.now, time::timer, workers,
                driven -> true, otherReply::countDown, e -> { throw e; });
        try {
            first.requestReply();
            check(entered.await(3, TimeUnit.SECONDS), "first session started");
            first.drive(() -> {});
            second.requestReply();
            check(otherReply.await(3, TimeUnit.SECONDS), "another session can infer while first is blocked");
            first.close();
            second.close();
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** 工作任务和定时任务分别排队，advance 不需要真实 sleep。 */
    static final class ManualTime extends AbstractExecutorService implements ScheduledExecutorService {
        volatile long now;
        private boolean shutdown;
        private long sequence;
        private final Deque<Runnable> workers = new ArrayDeque<>();
        final PriorityQueue<Task<?>> timers = new PriorityQueue<>();

        synchronized Runnable timer(Runnable action, long delay) {
            ScheduledFuture<?> future = schedule(action, delay, TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }

        void drainWorkers() {
            while (true) {
                Runnable work;
                synchronized (this) { work = workers.pollFirst(); }
                if (work == null) return;
                work.run();
            }
        }

        void advance(long millis) {
            now += millis;
            while (true) {
                drainWorkers();
                Task<?> task;
                synchronized (this) {
                    if (timers.isEmpty() || timers.peek().due > now) return;
                    task = timers.poll();
                }
                task.run();
            }
        }

        @Override public synchronized void execute(Runnable command) {
            if (shutdown) throw new RejectedExecutionException();
            workers.addLast(command);
        }
        @Override public synchronized ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            return add(Executors.callable(command, null), command, delay, unit);
        }
        @Override public synchronized <V> ScheduledFuture<V> schedule(Callable<V> command, long delay, TimeUnit unit) {
            return add(command, () -> {}, delay, unit);
        }
        private <V> Task<V> add(Callable<V> command, Runnable action, long delay, TimeUnit unit) {
            if (shutdown) throw new RejectedExecutionException();
            Task<V> task = new Task<>(command, action, now + unit.toMillis(delay), sequence++);
            timers.add(task);
            return task;
        }
        @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initial, long period, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initial, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }
        @Override public synchronized void shutdown() { shutdown = true; }
        @Override public synchronized List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> abandoned = new ArrayList<>(workers);
            workers.clear();
            timers.forEach(t -> t.cancel(false));
            timers.clear();
            return abandoned;
        }
        @Override public synchronized boolean isShutdown() { return shutdown; }
        @Override public synchronized boolean isTerminated() { return shutdown && workers.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }

        final class Task<V> extends FutureTask<V> implements RunnableScheduledFuture<V> {
            final Runnable action;
            final long due;
            final long order;
            Task(Callable<V> command, Runnable action, long due, long order) {
                super(command);
                this.action = action;
                this.due = due;
                this.order = order;
            }
            @Override public long getDelay(TimeUnit unit) { return unit.convert(due - now, TimeUnit.MILLISECONDS); }
            @Override public int compareTo(Delayed other) {
                Task<?> task = (Task<?>) other;
                int byTime = Long.compare(due, task.due);
                return byTime == 0 ? Long.compare(order, task.order) : byTime;
            }
            @Override public boolean isPeriodic() { return false; }
        }
    }
}

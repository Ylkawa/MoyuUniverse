package com.nekoyu.Universe.AIChat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** 每个会话独立串行处理输入和推理；计时器只唤醒工作线程。 */
final class SessionReplyQueue {
    static final long DRIVE_DEBOUNCE_MILLIS = 5_000;

    @FunctionalInterface
    interface Timer {
        /** 返回取消本次排期的操作。 */
        Runnable schedule(Runnable task, long delayMillis);
    }

    private final LongSupplier clock;
    private final Timer timer;
    private final Executor worker;
    private final Consumer<Boolean> processRound;
    private final Consumer<RuntimeException> onFailure;
    private final Deque<Runnable> inputs = new ArrayDeque<>();
    private boolean pending;
    private boolean driven;
    private boolean active;
    private boolean closed;
    private long deadline;
    private long generation;
    private Runnable cancelTimer;

    SessionReplyQueue(LongSupplier clock, Timer timer, Executor worker,
                      Predicate<Boolean> prepare, Runnable reply, Consumer<RuntimeException> onFailure) {
        this.clock = clock;
        this.timer = timer;
        this.worker = worker;
        this.processRound = driven -> {
            if (prepare.test(driven) && !isClosed()) reply.run();
        };
        this.onFailure = onFailure;
    }

    SessionReplyQueue(LongSupplier clock, Timer timer, Executor worker,
                      Consumer<Boolean> processRound, Consumer<RuntimeException> onFailure) {
        this.clock = clock;
        this.timer = timer;
        this.worker = worker;
        this.processRound = processRound;
        this.onFailure = onFailure;
    }

    synchronized void drive(Runnable appendInput) {
        submit(appendInput, false, true);
    }

    /** 所有输入共用队列；立即触发仍等待当前推理释放执行权。 */
    synchronized void submit(Runnable appendInput, boolean immediate, boolean forceReply) {
        if (closed) throw new IllegalStateException("会话已关闭");
        inputs.addLast(appendInput);
        pending = true;
        driven |= forceReply;
        deadline = clock.getAsLong() + (immediate ? 0 : DRIVE_DEBOUNCE_MILLIS);
        wake();
    }

    /** 生命周期检查不得被连续旁聊无限推迟，也不强制主助手回复。 */
    synchronized void requestCheck() {
        if (closed) return;
        pending = true;
        deadline = clock.getAsLong();
        wake();
    }

    /** 用户消息已经写入上下文，这里只合并回复触发，不再追加消息。 */
    synchronized boolean mergeUserTrigger() {
        if (closed || !driven) return false;
        deadline = clock.getAsLong() + DRIVE_DEBOUNCE_MILLIS;
        wake();
        return true;
    }

    synchronized void requestReply() {
        if (closed) return;
        pending = true;
        if (!driven) deadline = clock.getAsLong();
        wake();
    }

    synchronized boolean hasWork() {
        return !closed && (pending || active || !inputs.isEmpty());
    }

    synchronized boolean hasQueuedInputs() {
        return !inputs.isEmpty() || pending;
    }

    synchronized boolean isClosed() {
        return closed;
    }

    synchronized void close() {
        closed = true;
        pending = false;
        driven = false;
        inputs.clear();
        cancelTimer();
    }

    /** 调用方持有本队列锁；耗时的消息转换和推理都在锁外执行。 */
    private void wake() {
        cancelTimer();
        if (closed || active || !pending) return;
        long delay = deadline - clock.getAsLong();
        if (!inputs.isEmpty() || delay <= 0) {
            active = true;
            try {
                worker.execute(this::process);
            } catch (RuntimeException e) {
                active = false;
                close();
                onFailure.accept(e);
            }
        } else {
            long scheduledGeneration = generation;
            cancelTimer = timer.schedule(() -> {
                synchronized (SessionReplyQueue.this) {
                    if (closed || scheduledGeneration != generation) return;
                    wake();
                }
            }, delay);
        }
    }

    private void cancelTimer() {
        generation++;
        if (cancelTimer != null) cancelTimer.run();
        cancelTimer = null;
    }

    private void process() {
        try {
            boolean drivenRound;
            while (true) {
                List<Runnable> batch;
                synchronized (this) {
                    if (closed) return;
                    batch = new ArrayList<>(inputs);
                    inputs.clear();
                    if (batch.isEmpty()) {
                        if (!pending || clock.getAsLong() < deadline) return;
                        drivenRound = driven;
                        pending = false;
                        driven = false;
                        break;
                    }
                }
                for (Runnable input : batch) {
                    if (isClosed()) return;
                    try {
                        input.run();
                    } catch (RuntimeException e) {
                        onFailure.accept(e);
                    }
                }
            }
            if (!isClosed()) processRound.accept(drivenRound);
        } catch (RuntimeException e) {
            onFailure.accept(e);
        } finally {
            synchronized (this) {
                active = false;
                wake();
            }
        }
    }
}

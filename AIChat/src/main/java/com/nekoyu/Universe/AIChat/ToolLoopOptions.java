package com.nekoyu.Universe.AIChat;

/** 智能体层面的函数调用及跨请求异步工具策略。 */
public class ToolLoopOptions {
    public static final int DEFAULT_MAX_TOOL_CALLS_PER_TURN = 20;
    public static final int DEFAULT_MAX_TOOL_ROUNDS = 5;
    public static final long DEFAULT_ASYNC_MAX_WAIT_MILLIS = 30_000;
    public static final long DEFAULT_ASYNC_DEBOUNCE_MILLIS = 5_000;

    public int MaxToolCallsPerTurn = DEFAULT_MAX_TOOL_CALLS_PER_TURN;
    public int MaxToolRounds = DEFAULT_MAX_TOOL_ROUNDS;
    public boolean EnableAsyncTools = false;
    public long AsyncMaxWaitMillis = DEFAULT_ASYNC_MAX_WAIT_MILLIS;
    public long AsyncDebounceMillis = DEFAULT_ASYNC_DEBOUNCE_MILLIS;

    public ToolLoopOptions copy() {
        ToolLoopOptions copy = new ToolLoopOptions();
        copy.MaxToolCallsPerTurn = MaxToolCallsPerTurn;
        copy.MaxToolRounds = MaxToolRounds;
        copy.EnableAsyncTools = EnableAsyncTools;
        copy.AsyncMaxWaitMillis = AsyncMaxWaitMillis;
        copy.AsyncDebounceMillis = AsyncDebounceMillis;
        return copy;
    }
}

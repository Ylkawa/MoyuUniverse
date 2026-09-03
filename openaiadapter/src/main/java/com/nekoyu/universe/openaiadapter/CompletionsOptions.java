package com.nekoyu.universe.openaiadapter;

/**
 * 单次补全(completions)请求会话的可调参数。
 * 全部字段均有默认值，不配置也能正常工作，
 * 通过 {@link OpenAIChannel#setCompletionsOptions(CompletionsOptions)} 整体注入。
 */
public class CompletionsOptions {
    public static final int DEFAULT_MAX_TOOL_CALLS_PER_TURN = 20;
    public static final int DEFAULT_MAX_TOOL_ROUNDS = 5;
    public static final long DEFAULT_ASYNC_MAX_WAIT_MILLIS = 30_000;
    public static final long DEFAULT_ASYNC_DEBOUNCE_MILLIS = 5_000;

    /** 单次回复内允许执行的最大工具调用次数，超出后禁用工具强制文字回复，防止模型陷入工具调用死循环 */
    public int maxToolCallsPerTurn = DEFAULT_MAX_TOOL_CALLS_PER_TURN;
    /** 单次回复内工具调用最多进行的请求轮数，超时后禁用工具做最后一次文字回复 */
    public int maxToolRounds = DEFAULT_MAX_TOOL_ROUNDS;
    /** 是否启用跨请求后台异步工具结果 */
    public boolean enableAsyncTools = false;
    /** 异步工具结果默认最大等待时间（毫秒） */
    public long asyncMaxWaitMillis = DEFAULT_ASYNC_MAX_WAIT_MILLIS;
    /** 新消息静默去抖等待时长（毫秒） */
    public long asyncDebounceMillis = DEFAULT_ASYNC_DEBOUNCE_MILLIS;
}
package com.nekoyu.universe.openaiadapter;

/**
 * 单次补全(completions)请求会话的可调参数。
 * 全部字段均有默认值，不配置也能正常工作，
 * 通过 {@link OpenAIChannel#setCompletionsOptions(CompletionsOptions)} 整体注入。
 */
public class CompletionsOptions {
    public static final int DEFAULT_MAX_TOOL_CALLS_PER_TURN = 20;
    public static final int DEFAULT_MAX_TOOL_ROUNDS = 5;
    public static final double DEFAULT_DUPLICATE_THRESHOLD = 0.8;

    /** 单次回复内允许执行的最大工具调用次数，超出后禁用工具强制文字回复，防止模型陷入工具调用死循环 */
    public int maxToolCallsPerTurn = DEFAULT_MAX_TOOL_CALLS_PER_TURN;
    /** 单次回复内工具调用最多进行的请求轮数，超时后禁用工具做最后一次文字回复 */
    public int maxToolRounds = DEFAULT_MAX_TOOL_ROUNDS;
    /** 对相同工具的两次调用，参数 token 集合 Jaccard 相似度达到该值即视为重复调用，不再重复执行 */
    public double duplicateThreshold = DEFAULT_DUPLICATE_THRESHOLD;
}
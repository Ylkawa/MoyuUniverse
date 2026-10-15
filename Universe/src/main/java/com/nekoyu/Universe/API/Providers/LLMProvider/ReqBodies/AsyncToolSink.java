package com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies;

/**
 * 异步工具结果回调：当 Context/Agent 执行工具回调并识别出 {@code asyncPending} 占位结果时调用，
 * 由上层（通常是会话编排层）登记该 tool_call 为异步未决。
 */
public interface AsyncToolSink {
    /**
     * 某个 tool_call 被注册为异步未决。
     *
     * @param toolCallId         该次调用的 tool_call_id
     * @param toolTimeoutMillis  工具声明覆盖的超时（毫秒），0 表示使用通道默认值
     */
    void onPendingResult(String toolCallId, long toolTimeoutMillis);

    /** 会话是否能接收异步工具的分段回调；旧的一次性补投实现保持原行为。 */
    default boolean acceptsCallbacks() { return false; }

    /** 分段回调仍交给会话原有队列处理，不在工具线程中直接生成或发送回复。 */
    default void onCallback(String toolCallId, Message message) {}
}

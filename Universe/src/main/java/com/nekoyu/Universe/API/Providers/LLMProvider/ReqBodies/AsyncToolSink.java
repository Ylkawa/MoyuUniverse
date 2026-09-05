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
}

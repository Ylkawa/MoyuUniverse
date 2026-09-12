package com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies;

import java.util.HashMap;
import java.util.Map;

public class CompletionsRequest {
    public Map<String, String> placeholders = new HashMap<>();
    public boolean enable_thinking = false;
    /**
     * 工具选择策略，对应 OpenAI 兼容协议的 tool_choice 字段：
     * null 表示不下发（由 Provider 决定默认行为，通常为 auto），
     * "none" 禁止本轮调用工具，"auto" 由模型自行决定，"required" 强制调用某个工具。
     * <p>
     * 关键用途：强制收尾时保持 tools 列表不变、仅置 "none"，
     * 避免因移除 tools 改变 prompt 前部渲染而打断上下文（前缀）缓存。
     */
    public String toolChoice = null;
    /** 异步工具结果的订阅者：Context/Agent 识别到 asyncPending 占位结果时回调（可空） */
    public transient AsyncToolSink asyncToolSink;
}

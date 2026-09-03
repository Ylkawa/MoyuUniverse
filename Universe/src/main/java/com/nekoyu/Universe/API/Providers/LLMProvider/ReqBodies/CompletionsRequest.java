package com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies;

import java.util.HashMap;
import java.util.Map;

public class CompletionsRequest {
    public Map<String, String> placeholders = new HashMap<>();
    public boolean enable_thinking = false;
    /** 异步工具结果的订阅者：Provider 识别到 asyncPending 占位结果时回调（可空） */
    public transient AsyncToolSink asyncToolSink;
}
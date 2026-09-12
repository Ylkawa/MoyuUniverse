package com.nekoyu.universe.openaiadapter.RequestBodies;

import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.LLMFunction;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Message;

import java.util.LinkedList;
import java.util.List;

public class OpenAICompletionsRequest {
    public String model;
    public boolean stream;
    public List<Message> messages;
    public List<LLMFunction> tools;
    /** OpenAI 兼容协议的 tool_choice：null 时 Gson 不序列化该字段，保持默认行为 */
    public String tool_choice;

    public OpenAICompletionsRequest() {
        messages = new LinkedList<>();
        tools = new LinkedList<>();
    }
}

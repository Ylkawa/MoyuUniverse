package com.nekoyu.universe.openaiadapter.RequestBodies;

import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.LLMFunction;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Message;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ResponseFormat;

import java.util.LinkedList;
import java.util.List;

public class OpenAICompletionsRequest {
    public String model;
    public boolean stream;
    public List<Message> messages;
    public List<LLMFunction> tools;
    /** OpenAI 兼容协议的 tool_choice：null 时 Gson 不序列化该字段，保持默认行为 */
    public String tool_choice;
    /** null 时不序列化；普通请求和特调请求共用相同的输出格式配置。 */
    public ResponseFormat response_format;

    public OpenAICompletionsRequest() {
        messages = new LinkedList<>();
        tools = new LinkedList<>();
    }
}

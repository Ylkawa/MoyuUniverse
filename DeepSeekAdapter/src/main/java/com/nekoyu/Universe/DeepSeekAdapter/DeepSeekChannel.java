package com.nekoyu.Universe.DeepSeekAdapter;

import com.google.gson.Gson;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class DeepSeekChannel {
    public static final Gson gson = new Gson();
    public static OkHttpClient okHttpClient;
    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public String id;
    private String base_url;
    private String api_key;
    private Logger logger = LoggerFactory.getLogger(getClass());

    public DeepSeekChannel(String id, String base_url, String api_key) {
        this.id = id;
        this.base_url = base_url;
        this.api_key = api_key;

        okHttpClient = new OkHttpClient().newBuilder()
                .connectTimeout(30, TimeUnit.SECONDS)  // 连接超时
                .readTimeout(60, TimeUnit.SECONDS)      // 读取响应超时（重点调大）
                .writeTimeout(30, TimeUnit.SECONDS)     // 发送请求超时
                .build();
    }

    public Assistant getAssistant(String model) {
        return new Assistant(this, model);
    }

    public AssistantResponse request(MessageList messageList, Assistant assistant) throws IOException, DSException {
        return request(messageList, assistant, 0);
    }

    private AssistantResponse request(MessageList messageList, Assistant assistant, int reqNum) throws IOException, DSException {
        reqNum++;
        if (reqNum >= 5) {
            throw new OutOfRequestLimit("超出调用次数限制");
        }
        var ar = new AssistantRequest();
        ar.model = assistant.model;
        ar.messages = messageList.getMessageList().toArray(new Message[0]);
        if (!assistant.deepSeekTools.isEmpty()) ar.tools = assistant.deepSeekTools.values().toArray(new DeepSeekTool[0]);

        // API请求 - 构建请求
        RequestBody body = RequestBody.create(gson.toJson(ar), JSON);

        Request request = new Request.Builder()
                .url(base_url + "/chat/completions")
                .addHeader("Authorization", "Bearer " + api_key)
                .post(body)
                .build();

        // 尝试请求
        try (Response response = okHttpClient.newCall(request).execute()) {
            String rawContent = response.body().string();
            if (response.code() == 200) {
                AssistantResponse assistantResponse = gson.fromJson(rawContent, AssistantResponse.class);
                if (assistantResponse.usage != null) {
                    logger.info("本次请求消耗token量: 输入: {}(未命中缓存) {}(命中缓存) 输出: {}", assistantResponse.usage.prompt_cache_miss_tokens, assistantResponse.usage.prompt_cache_hit_tokens, assistantResponse.usage.completion_tokens);
                    switch (assistantResponse.choices[0].finish_reason) {
                        case "tool_calls", "function_call":
                            logger.info("发起工具调用: {}/{}", reqNum, 5);
                            var tool_calls = new HashMap<Tool_call, String>();
                            for (Tool_call tool_call : assistantResponse.choices[0].message.tool_calls) {
                                Map<String, String> args = gson.fromJson(tool_call.function.arguments, HashMap.class);
                                String tool_resp = assistant.deepSeekTools.get(tool_call.function.name).function.cf.function(args);
                                if (tool_resp != null) {
                                    tool_calls.put(tool_call, tool_resp);
                                }
                            }
                            if (tool_calls.isEmpty()) { // 如果调用的函数都返回了 null 则默认为模型不需要知道调用的结果，直接继续
                                messageList.addMessage("assistant", assistantResponse.choices[0].message.content);
                                if (assistantResponse.choices[0].message.content.isEmpty())
                                    return request(messageList, assistant, reqNum); // 但是我还是怕有憨批模型调用了函数但是一声不吭
                                return assistantResponse;
                            } else {
                                messageList.addToolRequest(assistantResponse.choices[0].message.content, tool_calls.keySet().toArray(new Tool_call[0]));
                                for (Map.Entry<Tool_call, String> entry : tool_calls.entrySet()) {
                                    messageList.addToolResponse(entry.getValue(), entry.getKey().id);
                                }
                                return request(messageList, assistant, reqNum);
                            }
                        case "stop":
                            messageList.addMessage("assistant", assistantResponse.choices[0].message.content);
                            return assistantResponse;
                    }
                    return null;
                }
            }
            DSException dsException = new DSException("未知错误");
            dsException.setRawResponse(rawContent);
            throw dsException;
        }
    }
}

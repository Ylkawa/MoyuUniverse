package com.nekoyu.Universe.DeepSeekAdapter;

import com.google.gson.Gson;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
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

    public AssistantResponse request(MessageList messageList, Assistant assistant) throws IOException {
        return request(messageList, assistant, 0);
    }

    private AssistantResponse request(MessageList messageList, Assistant assistant, int reqNum) throws IOException {
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
            AssistantResponse assistantResponse = gson.fromJson(response.body().string(), AssistantResponse.class);
            logger.info("本次请求消耗token量: 输入: {}(未命中缓存) {}(命中缓存) 输出: {}", assistantResponse.usage.prompt_cache_miss_tokens, assistantResponse.usage.prompt_cache_hit_tokens, assistantResponse.usage.completion_tokens);
            switch (assistantResponse.choices[0].finish_reason){
                case "tool_calls", "function_call":
                    messageList.addToolRequest(assistantResponse.choices[0].message.content, assistantResponse.choices[0].message.tool_calls);
                    logger.info("发起工具调用");
                    Map<String, String> map = gson.fromJson(assistantResponse.choices[0].message.tool_calls[0].function.arguments, HashMap.class);
                    String tool_resp = assistant.deepSeekTools.get(assistantResponse.choices[0].message.tool_calls[0].function.name).function.cf.function(map);
                    messageList.addToolResponse(tool_resp, assistantResponse.choices[0].message.tool_calls[0].id);
                    return request(messageList, assistant, reqNum);
                case "stop":
                    messageList.addMessage("assistant", assistantResponse.choices[0].message.content);
                    return assistantResponse;

            }
            return null;
        }
    }
}

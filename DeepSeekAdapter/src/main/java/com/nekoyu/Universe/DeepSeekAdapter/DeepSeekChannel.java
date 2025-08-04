package com.nekoyu.Universe.DeepSeekAdapter;

import com.google.gson.Gson;
import okhttp3.*;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class DeepSeekChannel {
    public static final Gson gson = new Gson();
    public static OkHttpClient okHttpClient;
    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public String id;
    private String base_url;
    private String api_key;

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
        var ar = new AssistantRequest();
        ar.model = assistant.model;
        ar.messages = messageList.getMessageList().toArray(new Message[0]);

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
            messageList.addMessage("assistant", assistantResponse.choices[0].message.content);
            return assistantResponse;
        }
    }
}

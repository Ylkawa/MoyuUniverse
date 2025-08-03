package com.nekoyu.Universe.DeepSeekAdapter;

import com.google.gson.Gson;
import okhttp3.*;

import java.io.IOException;

public class DeepSeekChannel {
    public static final Gson gson = new Gson();
    public static final OkHttpClient okHttpClient = new OkHttpClient();
    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public String id;
    private String base_url;
    private String api_key;

    public DeepSeekChannel(String id, String base_url, String api_key) {
        this.id = id;
        this.base_url = base_url;
        this.api_key = api_key;
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
            return gson.fromJson(response.body().string(), AssistantResponse.class);
        }
    }
}

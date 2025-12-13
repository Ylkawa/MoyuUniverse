package com.nekoyu.universe.aichat;

import com.google.gson.Gson;
import com.nekoyu.Universe.AIChat.AIChat;
import com.nekoyu.Universe.AIChat.AIChatPlugin;
import com.nekoyu.Universe.API.Providers.LLMProvider.LLMFunction;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.LLMTool;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.HashMap;

public class Whois extends AIChatPlugin {
    OkHttpClient client = new OkHttpClient();
    Gson gson = new Gson();

    public Whois(AIChat aiChat) {
        super(aiChat);
    }

    @Override
    public void onEnable() {
        LLMFunction function = LLMFunction.Builder()
                .name("WhoisLookup")
                .description("查询某一个域名的whois信息")
                .parameters(new LLMFunction.Parameters("object", new String[]{"Domain"}, new String[]{"Domain"}))
                .callback(args -> {
                    // Using this API to lookup: https://xxapi.cn/doc/whois
                    Request req = new Request.Builder()
                            .url(HttpUrl.parse("https://v2.xxapi.cn/api/whois").newBuilder() // 暂时不知道怎么 NPE
                                    .addQueryParameter("domain", args.get("Domain"))
                                    .build())
                            .build();
                    try (Response response = client.newCall(req).execute()) {
                        return response.body().string();
                    } catch (IOException e) {
                        return "查询失败: " + e.getMessage();
                    }
                })
                .build();
        AIChat.registerFunction("WhoisLookup", function);
    }

    @Override
    public void onDisable() {

    }
}

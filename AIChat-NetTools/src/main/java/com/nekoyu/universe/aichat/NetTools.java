package com.nekoyu.universe.aichat;

import com.nekoyu.Universe.AIChat.AIChat;
import com.nekoyu.Universe.AIChat.AIChatPlugin;
import com.nekoyu.Universe.API.Providers.LLMProvider.LLMFunction;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;

public class NetTools extends AIChatPlugin {
    OkHttpClient client = new OkHttpClient();

    public NetTools(AIChat aiChat) {
        super(aiChat);
    }

    @Override
    public void onEnable() {
        AIChat.registerFunction("WhoisLookup", LLMFunction.Builder()
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
                .build());

        AIChat.registerFunction("DNSLookup", LLMFunction.Builder()
                .name("DNSLookup")
                .description("查询某一个域名的DNS信息, RR Type 用于指定要解析的记录种类，如A和AAAA，默认情况下仅解析A和AAAA")
                .parameters(new String[]{"Domain", "RRType"}, new String[]{"Domain"})
                .callback(args -> {
                    String rr = args.get("RRType");
                    if (rr != null) rr = "A";
                    Request req = new Request.Builder()
                            .url(HttpUrl.parse("https://223.5.5.5/resolve").newBuilder()
                                    .addQueryParameter("name", args.get("Domain"))// 使用 阿里DNS
                                    .addQueryParameter("type", rr)
                                    .build())
                            .build();
                    try (Response response = client.newCall(req).execute()) {
                        return response.body().string();
                    } catch (IOException e) {
                        return "查询失败: " + e.getMessage();
                    }
                })
                .build()
        );
    }

    @Override
    public void onDisable() {

    }
}

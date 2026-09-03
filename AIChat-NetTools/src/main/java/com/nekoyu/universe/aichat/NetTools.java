package com.nekoyu.universe.aichat;

import com.nekoyu.Universe.AIChat.AIChat;
import com.nekoyu.Universe.AIChat.AIChatPlugin;
import com.nekoyu.Universe.API.MessageChannel.MFChain;
import com.nekoyu.Universe.API.MessageChannel.MessageField.TextField;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.JsonSchema;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.LLMFunction;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Message;
import com.google.gson.JsonObject;
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
        registerFunction(new String[]{"WhoisLookup", "NetTools"}, LLMFunction.builder()
                .name("WhoisLookup")
                .description("查询某一个域名的 Whois 信息")
                .parameters(JsonSchema.object()
                        .property("Domain", JsonSchema.string().description("要查询的域名"))
                        .required("Domain"))
                .syncCallback(args -> {
                    JsonObject o = args.getAsJsonObject();
                    // Using this API to lookup: https://xxapi.cn/doc/whois
                    Request req = new Request.Builder()
                            .url(HttpUrl.parse("https://v2.xxapi.cn/api/whois").newBuilder() // 暂时不知道怎么 NPE
                                    .addQueryParameter("domain", o.get("Domain").getAsString())
                                    .build())
                            .build();
                    try (Response response = client.newCall(req).execute()) {
                        return new Message(response.body().string());
                    } catch (IOException e) {
                        return new Message("查询失败: " + e.getMessage());
                    }
                })
                .build());

        registerFunction(new String[]{"DNSLookup", "NetTools"}, LLMFunction.builder()
                .name("DNSLookup")
                .description("查询某一个域名的 DNS 信息, RR Type 用于指定要解析的记录种类，如 A 和 AAAA ，默认情况下仅解析 A")
                .parameters(JsonSchema.object()
                        .property("Domain", JsonSchema.string().description("要查询的域名"))
                        .property("RRType", JsonSchema.enumType("A", "AAAA", "CNAME", "MX", "NS", "TXT")
                                .description("要解析的记录种类，默认 A"))
                        .required("Domain"))
                .syncCallback(args -> {
                    JsonObject o = args.getAsJsonObject();
                    String rr = o.has("RRType") ? o.get("RRType").getAsString() : "A";
                    Request req = new Request.Builder()
                            .url(HttpUrl.parse("https://223.5.5.5/resolve").newBuilder()
                                    .addQueryParameter("name", o.get("Domain").getAsString()) // 使用 阿里DNS
                                    .addQueryParameter("type", rr.toUpperCase())
                                    .build())
                            .build();
                    try (Response response = client.newCall(req).execute()) {
                        return new Message(response.body().string());
                    } catch (IOException e) {
                        return new Message("查询失败: " + e.getMessage());
                    }
                })
                .build()
        );
    }

    @Override
    public void onDisable() {

    }
}

package com.nekoyu.Universe.AIChat.WebSearch;

import com.google.gson.Gson;
import com.nekoyu.Universe.AIChat.AIChat;
import com.nekoyu.Universe.AIChat.AIChatPlugin;
import com.nekoyu.Universe.AIChat.WebSearch.GoogleWebSearchAPI.SearchResponse;
import com.nekoyu.Universe.DeepSeekAdapter.DeepSeekTool;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.HashMap;


public class WebSearch extends AIChatPlugin {
    OkHttpClient client;
    Config config;
    Gson gson = new Gson();
    boolean able = true;

    public WebSearch(AIChat aiChat) {
        super(aiChat);
    }

    @Override
    public void onEnable() {
        getConfigDir();
        Proxy proxy = Proxy.NO_PROXY;
        try {
            config = new Gson().fromJson(new FileReader("./config/AIChat/Plugins/WebSearch/config.json"), Config.class);
            if (config.EnableProxy) {
                if (config.HttpProxyURI == null || config.HttpProxyURI.isEmpty()) {
                    logger.error("Proxy URI is null or empty");
                    able = false;
                }
                String[] hostAndPort = config.HttpProxyURI.split("http://")[1].split(":");
                try {
                    InetSocketAddress isa = new InetSocketAddress(hostAndPort[0], Integer.parseInt(hostAndPort[1]));
                    proxy = new Proxy(Proxy.Type.HTTP, isa);
                } catch (Exception e) {
                    logger.error("Proxy URI is invalid");
                    able = false;
                }
            }
        } catch (FileNotFoundException e) {
            config = new Config();
            try (FileWriter fw = new FileWriter("./config/AIChat/Plugins/WebSearch/config.json")) {
                fw.write(new Gson().toJson(config));
            } catch (IOException ex) {
                able = false;
                logger.error(ex.getMessage());
                return;
            }
        }
        client = new OkHttpClient.Builder()
                .proxy(proxy)
                .build();

        DeepSeekTool dst = new DeepSeekTool("Google搜索",
                "使用Google的API在全网搜索内容，仅当用户要求或者要回答的内容具有时效性时使用",
                args -> search(args.get("搜索词")),
                new HashMap<>(){{put("搜索词", new DeepSeekTool.Function.Parameters.Property("搜索词"));}},
                new String[]{"搜索词"});
        registerTool("WebSearch", dst);
    }

    public String search(String content) {
        // 构建请求
        HttpUrl url = HttpUrl.parse("https://www.googleapis.com/customsearch/v1")
                .newBuilder()
                .addQueryParameter("key", config.SearchAPIKey)
                .addQueryParameter("cx", config.SearchEngineID)
                .addQueryParameter("q", content)
                .build();
        Request req = new Request.Builder()
                .url(url)
                .get()
                .build();
        try (Response response = client.newCall(req).execute()) {
            if (response.isSuccessful()) {
                SearchResponse searchR = gson.fromJson(response.body().string(), SearchResponse.class);
                // 构建自然语言响应
                StringBuilder sb = new StringBuilder();
                for (SearchResponse.Item item : searchR.items) {
                    sb.append("{\n");
                    sb.append("「").append(item.title).append("」 - ").append(item.link).append("\n");
                    sb.append("摘要: ").append(item.snippet).append("\n");
                    sb.append("}\n\n");
                }
                return sb.toString();
            } else {
                return "搜索失败，状态码: "+response.code();
            }
        } catch (IOException e) {
            return "搜索异常: "+e.getMessage();
        }
    }
}

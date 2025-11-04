package com.nekoyu.Universe.AIChat.WebSearch.GoogleWebSearchAPI;

import com.google.gson.Gson;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.net.Proxy;

public class Client {
    private final String searchEngineId;
    private final String ak;
    OkHttpClient client = new OkHttpClient();
    private final Gson gson = new Gson();

    public Client(String ak, String searchEngineId) {
        this.ak = ak;
        this.searchEngineId = searchEngineId;
    }

    public SearchResponse search(String query, int page, int pageSize) throws Exception {
        // 构建请求
        HttpUrl url = HttpUrl.parse("https://www.googleapis.com/customsearch/v1")
                .newBuilder()
                .addQueryParameter("key", ak)
                .addQueryParameter("cx", searchEngineId)
                .addQueryParameter("q", query)
                .build();
        Request req = new Request.Builder()
                .url(url)
                .get()
                .build();
        try (Response response = client.newCall(req).execute()) {
            SearchResponse searchR = gson.fromJson(response.body().string(), SearchResponse.class);
            return searchR;
        }
    }

    public void setProxy(Proxy proxy) {
        client = new OkHttpClient.Builder()
                .proxy(proxy)
                .build();
    }
}

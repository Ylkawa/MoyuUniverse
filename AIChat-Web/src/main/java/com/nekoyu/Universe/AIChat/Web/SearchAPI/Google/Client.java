package com.nekoyu.Universe.AIChat.Web.SearchAPI.Google;

import com.google.gson.Gson;
import com.nekoyu.Universe.AIChat.Web.SearchAPI.SearchClient;
import com.nekoyu.Universe.AIChat.Web.SearchAPI.SearchResult;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.net.Proxy;
import java.net.URL;

/**
 时代的眼泪，Google将在2027停用此API，且不再接受新用户
 */
public class Client extends SearchClient {
    private final String searchEngineId;
    private final String ak;
    OkHttpClient client = new OkHttpClient();
    private final Gson gson = new Gson();

    public Client(String ak, String searchEngineId) {
        this.ak = ak;
        this.searchEngineId = searchEngineId;
    }

    public SearchResult search(String query) throws IOException {
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
            SearchResponse sr = gson.fromJson(response.body().string(), SearchResponse.class);

            SearchResult result = new SearchResult();
            result.resultFor = query;

            if (sr.items != null && !sr.items.isEmpty()) {
                for (SearchResponse.Item itemResponse : sr.items) {
                    SearchResult.Item itemResult = new SearchResult.Item();
                    itemResult.title = itemResponse.title;
                    itemResult.snippet = itemResponse.snippet;
                    itemResult.link = new URL(itemResponse.link);

                    result.items.add(itemResult);
                }
            }

            return result;
        }
    }

    public void setProxy(Proxy proxy) {
        client = new OkHttpClient.Builder()
                .proxy(proxy)
                .build();
    }
}

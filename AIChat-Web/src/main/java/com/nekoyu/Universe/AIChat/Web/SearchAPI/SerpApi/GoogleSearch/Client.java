package com.nekoyu.Universe.AIChat.Web.SearchAPI.SerpApi.GoogleSearch;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.nekoyu.Universe.AIChat.Web.Config;
import com.nekoyu.Universe.AIChat.Web.SearchAPI.SearchClient;
import com.nekoyu.Universe.AIChat.Web.SearchAPI.SearchResult;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.net.Proxy;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public class Client extends SearchClient {
    OkHttpClient client;
    String apiKey;
    static Gson gson = new Gson();

    public Client(String apiKey) {
        this(apiKey, Proxy.NO_PROXY);
    }

    public Client(String apiKey, Proxy proxy) {
        if (apiKey == null) {
            throw new IllegalArgumentException("ApiKey cannot be null");
        }
        this.apiKey = apiKey;
        this.client = new OkHttpClient.Builder().proxy(proxy).build();
    }

    @Override
    public SearchResult search(String query) throws IOException {
        var urlBuilder = HttpUrl.parse("https://serpapi.com/search.json")
                .newBuilder()
                .addQueryParameter("engine", "google")
                .addQueryParameter("q", query)
                .addQueryParameter("api_key", apiKey);
        if (searchParam.language != null) urlBuilder.addQueryParameter("hl", searchParam.language);
        if (searchParam.country != null) urlBuilder.addQueryParameter("gl", searchParam.country);
        if (searchParam.location != null) urlBuilder.addQueryParameter("location", searchParam.location);
        Request request = new Request.Builder().url(urlBuilder.build()).build();
        try (Response response = client.newCall(request).execute()) {
            SearchResponse sr = gson.fromJson(response.body().string(), SearchResponse.class);
            SearchResult result = new SearchResult();

            result.resultFor = sr.search_information.query_displayed; // 错别字情况会被搜索引擎自动修正，所以有时搜索到的东西不一定等于输入的东西
            if (sr.knowledge_graph != null && sr.knowledge_graph.get("breadcrumb") != null) {
                StringBuilder sb = new StringBuilder();
                boolean first = true;
                for (var jao : sr.knowledge_graph.get("breadcrumbs").getAsJsonArray()) {
                    var jo = jao.getAsJsonObject();
                    if (first) {
                        first = false;
                    } else sb.append("/");
                    sb.append(jo.getAsJsonObject().get("title").getAsString());
                }
                sb.append(": ");
                for (Map.Entry<String, JsonElement> je : sr.knowledge_graph.entrySet()) {
                    if (je.getKey().equals("breadcrumbs") || je.getKey().endsWith("_link") || je.getKey().endsWith("_stick")) continue;
                    for (var jo : je.getValue().getAsJsonArray()) {
                        sb.append(" ").append(jo.getAsJsonObject().get("name").getAsString());
                    }
                }
                result.overview = sb.toString();
            }

            for (var organic_result : sr.organic_results) {
                SearchResult.SiteItem item = new SearchResult.SiteItem();

                item.title = organic_result.title;
                item.snippet = organic_result.snippet;
                item.link = new URL(organic_result.displayed_link);
                if (organic_result.sitelinks != null) for (var siteLink : organic_result.sitelinks.expanded) {
                    SearchResult.SiteItem.SiteLink sl = new SearchResult.SiteItem.SiteLink();
                    sl.link = new URL(siteLink.link);
                    sl.title = siteLink.title;
                    item.siteLinks.add(sl);
                }

                result.items.add(item);
            }
            return result;
        }
    }
}

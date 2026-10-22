package com.nekoyu.Universe.AIChat.Web.SearchAPI.SerpApi.GoogleSearch;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.nekoyu.Universe.AIChat.Web.SearchAPI.SearchClient;
import com.nekoyu.Universe.AIChat.Web.SearchAPI.SearchResult;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.net.Proxy;
import java.net.URL;
import java.util.concurrent.TimeUnit;

public class Client extends SearchClient {
    static Gson gson = new Gson();
    OkHttpClient client;
    String apiKey;

    public Client(String apiKey) {
        this(apiKey, Proxy.NO_PROXY);
    }

    public Client(String apiKey, Proxy proxy) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("ApiKey cannot be blank");
        }
        this.apiKey = apiKey.trim();
        this.client = new OkHttpClient.Builder().proxy(proxy)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(90, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public SearchResult search(String query) throws IOException {
        var urlBuilder = HttpUrl.parse("https://serpapi.com/search.json")
                .newBuilder()
                .addQueryParameter("engine", "google")
                .addQueryParameter("q", query)
                .addQueryParameter("api_key", apiKey);
        if (searchParam != null) {
            if (searchParam.language != null) urlBuilder.addQueryParameter("hl", searchParam.language);
            if (searchParam.country != null) urlBuilder.addQueryParameter("gl", searchParam.country);
            if (searchParam.location != null) urlBuilder.addQueryParameter("location", searchParam.location);
        }
        Request request = new Request.Builder().url(urlBuilder.build()).build();
        try (Response response = client.newCall(request).execute()) {
            String string = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                String detail = "";
                try {
                    var json = JsonParser.parseString(string);
                    if (json.isJsonObject()) detail = text(json.getAsJsonObject().get("error"));
                } catch (JsonParseException ignored) {
                    // HTML/代理错误页也保留 HTTP 状态，不把整页或含密钥的 URL 写入日志。
                }
                throw failure("HTTP " + response.code() + (detail.isBlank() ? "" : ": " + detail));
            }
            if (string.isBlank()) throw failure("响应体为空");
            SearchResponse sr;
            try {
                var json = JsonParser.parseString(string);
                if (!json.isJsonObject()) throw failure("响应不是 JSON 对象");
                sr = gson.fromJson(json, SearchResponse.class);
            } catch (JsonParseException | IllegalStateException e) {
                throw failure("响应 JSON 格式或字段类型无效");
            }
            String status = sr.search_metadata == null ? null : sr.search_metadata.status;
            boolean empty = sr.search_information != null
                    && "Fully empty".equalsIgnoreCase(sr.search_information.organic_results_state);
            // SerpApi 的正常无结果响应也可能带 error，不能误报为鉴权或服务故障。
            if (sr.error != null && !sr.error.isBlank() && !("Success".equals(status) && empty))
                throw failure("API 错误: " + sr.error);
            if (!"Success".equals(status))
                throw failure("搜索未成功完成，status=" + (status == null ? "缺失" : status));
            if (sr.organic_results == null && !empty) throw failure("响应缺少 organic_results");
            SearchResult result = new SearchResult();

            result.resultFor = sr.search_information == null ? query : sr.search_information.query_displayed;
            if (result.resultFor == null || result.resultFor.isBlank()) result.resultFor = query;
            if (sr.knowledge_graph != null) {
                String title = text(sr.knowledge_graph.get("title"));
                String description = text(sr.knowledge_graph.get("description"));
                String overview = title + (title.isBlank() || description.isBlank() ? "" : ": ") + description;
                if (!overview.isBlank()) result.overview = overview;
            }

            if (sr.organic_results == null) return result;
            for (var organic_result : sr.organic_results) {
                if (organic_result == null) continue;
                URL link = link(organic_result.link);
                if (link == null) continue;
                SearchResult.SiteItem item = new SearchResult.SiteItem();

                item.title = organic_result.title;
                item.snippet = organic_result.snippet;
                item.link = link;
                if (organic_result.sitelinks != null && organic_result.sitelinks.expanded != null) {
                    for (var siteLink : organic_result.sitelinks.expanded) {
                        if (siteLink == null) continue;
                        URL siteUrl = link(siteLink.link);
                        if (siteUrl == null) continue;
                        SearchResult.SiteItem.SiteLink sl = new SearchResult.SiteItem.SiteLink();
                        sl.link = siteUrl;
                        sl.title = siteLink.title;
                        item.siteLinks.add(sl);
                    }
                }

                result.items.add(item);
            }
            if (!sr.organic_results.isEmpty() && result.items.isEmpty())
                throw failure("自然搜索结果中没有有效的 HTTP/HTTPS 链接");
            return result;
        }
    }

    private IOException failure(String message) {
        String safe = message.replace(apiKey, "[REDACTED]");
        return new IOException("SerpApi 搜索失败: " + (safe.length() > 500 ? safe.substring(0, 500) + "..." : safe));
    }

    private static String text(JsonElement value) {
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static URL link(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URL url = new URL(value);
            return ("http".equals(url.getProtocol()) || "https".equals(url.getProtocol()))
                    && !url.getHost().isBlank() ? url : null;
        } catch (IOException error) {
            return null;
        }
    }
}

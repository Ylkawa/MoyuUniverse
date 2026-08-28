package com.nekoyu.Universe.AIChat.Web.SearchAPI.BrightData.GoogleSearch;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.nekoyu.Universe.AIChat.Web.SearchAPI.SearchClient;
import com.nekoyu.Universe.AIChat.Web.SearchAPI.SearchResult;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class Client extends SearchClient {
    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    Gson gson = new Gson();
    OkHttpClient client = new OkHttpClient();
    String apikey;
    String zone;
    Logger logger = LoggerFactory.getLogger(this.getClass());

    public Client(String apikey, String zone) {
        this.zone = zone;
        this.apikey = apikey;
    }

    @Override
    public SearchResult search(String query) throws IOException {
        var reqBody = new SearchRequest();
        reqBody.zone = zone;
        reqBody.url = HttpUrl.parse("https://www.google.com/search")
                .newBuilder()
                .addQueryParameter("q", query)
                .build().toString();
        String requestJson = gson.toJson(reqBody);
        RequestBody body = RequestBody.create(requestJson, JSON);
        Request request = new Request.Builder()
                .url("https://api.brightdata.com/request")
                .post(body)
                .addHeader("Authorization", "Bearer " + apikey)
                .build();
        try (Response response = client.newCall(request).execute()) {
            String string = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw fail("BrightData 请求失败: HTTP " + response.code() + " - " + truncate(string, 500), requestJson, string, null);
            }

            JsonObject pack;
            try {
                pack = JsonParser.parseString(string).getAsJsonObject();
            } catch (JsonSyntaxException | IllegalStateException e) {
                throw fail("BrightData 返回的不是 JSON: " + truncate(string, 500), requestJson, string, e);
            }
            JsonElement bodyElement = pack.get("body");
            if (bodyElement == null || bodyElement.isJsonNull() || !bodyElement.isJsonPrimitive()) {
                throw fail("BrightData 响应缺少有效 body 字段: " + truncate(string, 500), requestJson, string, null);
            }
            String bodyString = bodyElement.getAsString();
            if (bodyString == null || bodyString.isBlank() || "null".equalsIgnoreCase(bodyString.trim())) {
                throw fail("BrightData body 为空: 请检查账户额度是否耗尽、zone 是否配置正确或 BrightData 是否已拒绝请求", requestJson, string, null);
            }

            SearchResponse searchResponse;
            try {
                searchResponse = gson.fromJson(bodyString, SearchResponse.class);
            } catch (JsonSyntaxException e) {
                throw fail("BrightData body 无法解析为搜索结果: " + truncate(bodyString, 500), requestJson, bodyString, e);
            }
            if (searchResponse == null || searchResponse.organic == null || searchResponse.organic.isEmpty()) {
                throw fail("BrightData 未返回任何搜索结果(organic 缺失或为空), 本次请求未能取得联网数据: " + truncate(bodyString, 300), requestJson, bodyString, null);
            }

            SearchResult searchResult = new SearchResult();
            searchResult.resultFor = query;
            for (SearchResponse.Item item : searchResponse.organic) {
                var resultItem = new SearchResult.Item();
                resultItem.title = item.title;
                resultItem.snippet = item.description;
                resultItem.link = item.link;
                resultItem.extensions = item.extensions;
                searchResult.items.add(resultItem);
            }
            return searchResult;
        }
    }

    private IOException fail(String message, String requestJson, String responseBody, Exception cause) {
        logger.error("搜索失败: " + message + "\n请求体: " + requestJson + "\n响应体: " + responseBody, cause);
        return new IOException(message, cause);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}

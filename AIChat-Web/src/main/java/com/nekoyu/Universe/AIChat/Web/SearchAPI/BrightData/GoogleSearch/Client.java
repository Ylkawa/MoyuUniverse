package com.nekoyu.Universe.AIChat.Web.SearchAPI.BrightData.GoogleSearch;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nekoyu.Universe.AIChat.Web.SearchAPI.SearchClient;
import com.nekoyu.Universe.AIChat.Web.SearchAPI.SearchResult;
import okhttp3.*;

import java.io.IOException;
import java.util.Map;

public class Client extends SearchClient {
    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    Gson gson = new Gson();
    OkHttpClient client = new OkHttpClient();
    String apikey;
    String zone;

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
        RequestBody body = RequestBody.create(gson.toJson(reqBody), JSON);
        Request request = new Request.Builder()
                .url("https://api.brightdata.com/request")
                .post(body)
                .addHeader("Authorization", "Bearer " + apikey)
                .build();
        try (Response response = client.newCall(request).execute()) {
            String string = response.body().string();
            JsonObject pack = JsonParser.parseString(string).getAsJsonObject();
            SearchResponse searchResponse = gson.fromJson(pack.get("body").getAsString(), SearchResponse.class);
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
}

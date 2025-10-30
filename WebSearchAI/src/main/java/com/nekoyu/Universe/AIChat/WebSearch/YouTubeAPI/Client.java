package com.nekoyu.Universe.AIChat.WebSearch.YouTubeAPI;

import com.google.gson.Gson;
import okhttp3.*;

import java.io.IOException;
import java.net.Proxy;

public class Client {
    OkHttpClient client = new OkHttpClient();
    Gson gson = new Gson();
    String key;

    public Client(String key) {
        client = new OkHttpClient();
        this.key = key;
    }

    public VideoListResponse getVideoListResponse(String id) throws IOException {
        return getVideoListResponse(id, new String[]{"snippet","statistics","contentDetails"});

    }

    public VideoListResponse getVideoListResponse(String id, String[] parts) throws IOException {
        String part = "";
        boolean isFirst = true;
        for (var p : parts) {
            if (isFirst) isFirst = false;
            else part += ",";
            part += p;
        }
        HttpUrl url = HttpUrl.parse("https://www.googleapis.com/youtube/v3/videos")
                .newBuilder()
                .addQueryParameter("part", part)
                .addQueryParameter("id", id)
                .addQueryParameter("key", key)
                .build();
        Request req = new Request.Builder()
                .url(url)
                .build();
        Response resp = client.newCall(req).execute();
        if (resp.isSuccessful()) return gson.fromJson(resp.body().string(), VideoListResponse.class);
        else throw new IOException("Unexpected code " + resp.code());
    }

    public void setProxy(Proxy proxy) {
        client = new OkHttpClient.Builder()
                .proxy(proxy)
                .build();
    }
}

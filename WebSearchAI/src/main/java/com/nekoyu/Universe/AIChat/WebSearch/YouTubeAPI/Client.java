package com.nekoyu.Universe.AIChat.WebSearch.YouTubeAPI;

import com.google.gson.Gson;
import okhttp3.*;
import okhttp3.Response;

import java.io.IOException;
import java.net.Proxy;

public class Client {
    OkHttpClient client;
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
        StringBuilder part = new StringBuilder();
        boolean isFirst = true;
        for (var p : parts) {
            if (isFirst) isFirst = false;
            else part.append(",");
            part.append(p);
        }
        HttpUrl url = HttpUrl.parse("https://www.googleapis.com/youtube/v3/videos")
                .newBuilder()
                .addQueryParameter("part", part.toString())
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

    public CommentThreadListResponse getCommentThreadListResponse(String videoId) throws IOException {
        HttpUrl url = HttpUrl.parse("https://www.googleapis.com/youtube/v3/commentThreads")
                .newBuilder()
                .addQueryParameter("part", "snippet")
                .addQueryParameter("videoId", videoId)
                .addQueryParameter("key", key)
                .build();
        Request req = new Request.Builder()
                .url(url)
                .build();
        Response resp = client.newCall(req).execute();
        if (resp.isSuccessful()) return gson.fromJson(resp.body().string(), CommentThreadListResponse.class);
        else throw new IOException("Unexpected code " + resp.code());
    }

    public void setProxy(Proxy proxy) {
        client = new OkHttpClient.Builder()
                .proxy(proxy)
                .build();
    }
}

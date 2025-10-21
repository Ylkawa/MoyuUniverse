package com.nekoyu.Universe.AIChat.WebSearch.BiliBiliAPI;

import com.google.gson.Gson;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class Client {
    public static Gson gson = new Gson();
    public static OkHttpClient client = new OkHttpClient.Builder().build();
    public static Logger logger = LoggerFactory.getLogger(Client.class);
    // Wbi 签名相关
    public static String imgKey = "unsolved";
    public static String subKey = "unsolved";
    public static int lastUpdate = 0;

    public static VideoInfo getVideoInfo(String bvid) throws IOException {
        HttpUrl url = HttpUrl.parse("https://api.bilibili.com/x/web-interface/view")
                .newBuilder()
                .addQueryParameter("bvid", bvid)
                .build();
        Request req = new Request.Builder()
                .url(url)
                .get()
                .build();

        try (Response response = client.newCall(req).execute()) {
            if (response.isSuccessful()) {
                return gson.fromJson(response.body().string(), VideoInfo.class);
            } else {
                throw new IOException("Unexpected code " + response);
            }
        }
    }
}

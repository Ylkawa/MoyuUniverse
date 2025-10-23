package com.nekoyu.Universe.AIChat.WebSearch.BiliBiliAPI;

import com.google.gson.Gson;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigInteger;
import java.util.TreeMap;

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

    public static UserInfo getUserInfo(String uid) throws IOException {
        TreeMap<String, Object> getUserInfo = new TreeMap<>();
        for (String arg : "platform=web&web_location=1550101&dm_img_list=[]&dm_img_str=V2ViR0wgMS4wIChPcGVuR0wgRVMgMi4wIENocm9taXVtKQ&dm_cover_img_str=QU5HTEUgKEludGVsLCBJbnRlbChSKSBVSEQgR3JhcGhpY3MgKDB4MDAwMEE3OEIpIERpcmVjdDNEMTEgdnNfNV8wIHBzXzVfMCwgRDNEMTEpR29vZ2xlIEluYy4gKEludGVsKQ&dm_img_inter=%7B%22ds%22:[],%22wh%22:[5533,5981,85],%22of%22:[202,404,202]%7D".split("&")) {
            String[] n = arg.split("=");
            getUserInfo.put(n[0], n[1]);
        }
        getUserInfo.put("mid", uid);
        String url = "https://api.bilibili.com/x/space/wbi/acc/info?" + WbiSolver.getFinalReqArgs(getUserInfo);
        Request req = new Request.Builder()
                .url(url)
                .addHeader("Cookie", "buvid3=CB937B38-EBBD-9860-CA0E-83D1AA5C648130551infoc; b_nut=1761053830; b_lsid=89B9CF48_19A06FCF5C8; _uuid=E9F59399-EF91-3ECA-65FE-E88712244B3B32657infoc; enable_web_push=DISABLE; home_feed_column=5; browser_resolution=1707-932; buvid4=2DAC9A23-DE42-CA5E-AC16-40C29C83FE4131220-025102121-B2uVB6kj5sblxRRCuRU1Ow%3D%3D; buvid_fp=b7eadede72d08601230a648d0f15324b; bili_ticket=eyJhbGciOiJIUzI1NiIsImtpZCI6InMwMyIsInR5cCI6IkpXVCJ9.eyJleHAiOjE3NjEzMTMwNjMsImlhdCI6MTc2MTA1MzgwMywicGx0IjotMX0.akN416B4bmGxt8QFvYZa7vNi6lNPjf6Fd0PzxEgQAMw; bili_ticket_expires=1761313003") // 游客的cookie
                .addHeader("priority", "u=0, i")
                .addHeader("sec-ch-ua", "\"Google Chrome\";v=\"141\", \"Not?A_Brand\";v=\"8\", \"Chromium\";v=\"141\"")
                .addHeader("sec-ch-ua-mobile", "?0")
                .addHeader("sec-ch-ua-platform", "\"Windows\"")
                .addHeader("sec-fetch-dest", "empty")
                .addHeader("sec-fetch-mode", "cors")
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36")
                .addHeader("Referer", "https://space.bilibili.com/")
                .addHeader("Origin", "https://space.bilibili.com")
                .get()
                .build();

        Response resp = client.newCall(req).execute();
        String string = resp.body().string();
        logger.debug(string);

        return gson.fromJson(string, UserInfo.class);
    }

    public static DynamicList getUserDynamicList(String uid) {
        Request req = new Request.Builder()
                .url(
                        HttpUrl.parse("https://api.bilibili.com/x/polymer/web-dynamic/desktop/v1/feed/space").newBuilder().addQueryParameter("host_mid", uid).build()
                )
                .build();
        try (Response resp = client.newCall(req).execute()) {
            return gson.fromJson(resp.body().string(), DynamicList.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        DynamicList dl = getUserDynamicList("497423225");
        for (var item : dl.data.items) {
            for (var d : item.modules) {
                if (d.module_author != null) logger.info(d.module_author.user.name);
            }
        }
    }
}

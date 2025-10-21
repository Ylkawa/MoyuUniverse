package com.nekoyu.Universe.AIChat.WebSearch.BiliBiliAPI;

import com.google.gson.Gson;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

public class WbiSolver {
    static OkHttpClient client = new OkHttpClient();
    static Gson gson = new Gson();

    static String mixinKey = null;
    static int lastUpdate = 0;

    private static final int[] MIXIN_KEY_ENC_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    };

    private static void updateMixinKey() {
        Request req = new Request.Builder()
                .url("https://api.bilibili.com/x/web-interface/nav")
                .get()
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36")
                .build();
        try (Response response = client.newCall(req).execute()) {
            NavResponse nr = gson.fromJson(response.body().string(), NavResponse.class);
            mixinKey = getMixinKey(nr.data.wbi_img.getImgFileName() + nr.data.wbi_img.getSubFileName());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String getMixinKey(String raw_key) {
        if (raw_key == null || raw_key.length() < 64) {
            throw new IllegalArgumentException("rawWbiKey 长度不足 64");
        }

        StringBuilder mixinKeyBuilder = new StringBuilder();
        for (int n : MIXIN_KEY_ENC_TAB) {
            mixinKeyBuilder.append(raw_key.charAt(n));
        }

        // 只保留前 32 位字符
        return mixinKeyBuilder.substring(0, 32);
    }

    private static String urlEncode(TreeMap<String, Object> args) {
        StringBuilder queryBuilder = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            if (!first) queryBuilder.append("&");
            first = false;
            queryBuilder.append(entry.getKey()).append("=").append(URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8));
        }
        return queryBuilder.toString();
    }

    public static String getFinalReqArgs(TreeMap<String, Object> rawArgs) {
        long nowTime = System.currentTimeMillis() / 1000;
        if (nowTime - lastUpdate > 82800 || mixinKey == null) {
            updateMixinKey();
            lastUpdate = (int) nowTime;
        }
        TreeMap<String, Object> calculatingArgs = (TreeMap<String, Object>) rawArgs.clone();
        calculatingArgs.put("wts", nowTime);
        String urlEncode = urlEncode(calculatingArgs);
        // 计算散列
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] digest = md5.digest((urlEncode + mixinKey).replaceAll("[!~*'()]", "").getBytes(StandardCharsets.UTF_8));
            StringBuilder wbiBuilder = new StringBuilder();
            for (byte b : digest) {
                wbiBuilder.append(String.format("%02x", b));
            }
            return urlEncode(rawArgs) + "&w_rid=" + wbiBuilder + "&wts=" + nowTime;
        } catch (NoSuchAlgorithmException e) {
            // ?真的会抛出这个exception吗
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        TreeMap<String, Object> rawArgs = new TreeMap<>();
        rawArgs.put("foo", "114");
        rawArgs.put("bar", "514");
        rawArgs.put("zab", 1919810);

        System.out.println(getFinalReqArgs(rawArgs));



        TreeMap<String, Object> getUserInfo = new TreeMap<>();
        // getUserInfo.put("mid", 497423225);
        for (String arg : "platform=web&web_location=1550101&dm_img_list=[]&dm_img_str=V2ViR0wgMS4wIChPcGVuR0wgRVMgMi4wIENocm9taXVtKQ&dm_cover_img_str=QU5HTEUgKEludGVsLCBJbnRlbChSKSBVSEQgR3JhcGhpY3MgKDB4MDAwMEE3OEIpIERpcmVjdDNEMTEgdnNfNV8wIHBzXzVfMCwgRDNEMTEpR29vZ2xlIEluYy4gKEludGVsKQ&dm_img_inter=%7B%22ds%22:[],%22wh%22:[5533,5981,85],%22of%22:[202,404,202]%7D".split("&")) {
            String[] n = arg.split("=");
            getUserInfo.put(n[0], n[1]);
        }
        getUserInfo.put("mid", 946974);
        String url = "https://api.bilibili.com/x/space/wbi/acc/info?" + getFinalReqArgs(getUserInfo);
        System.out.println(url);
        Request req = new Request.Builder()
                .url(url)
                .addHeader("Cookie", "buvid3=CB937B38-EBBD-9860-CA0E-83D1AA5C648130551infoc; b_nut=1761053830; b_lsid=89B9CF48_19A06FCF5C8; _uuid=E9F59399-EF91-3ECA-65FE-E88712244B3B32657infoc; enable_web_push=DISABLE; home_feed_column=5; browser_resolution=1707-932; buvid4=2DAC9A23-DE42-CA5E-AC16-40C29C83FE4131220-025102121-B2uVB6kj5sblxRRCuRU1Ow%3D%3D; buvid_fp=b7eadede72d08601230a648d0f15324b; bili_ticket=eyJhbGciOiJIUzI1NiIsImtpZCI6InMwMyIsInR5cCI6IkpXVCJ9.eyJleHAiOjE3NjEzMTMwNjMsImlhdCI6MTc2MTA1MzgwMywicGx0IjotMX0.akN416B4bmGxt8QFvYZa7vNi6lNPjf6Fd0PzxEgQAMw; bili_ticket_expires=1761313003")
                .addHeader("priority", "u=0, i")
                .addHeader("sec-ch-ua", "\"Google Chrome\";v=\"141\", \"Not?A_Brand\";v=\"8\", \"Chromium\";v=\"141\"")
                .addHeader("sec-ch-ua-mobile", "?0")
                .addHeader("sec-ch-ua-platform", "\"Windows\"")
                .addHeader("sec-fetch-dest", "empty")
                .addHeader("sec-fetch-mode", "cors")
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36")
                .addHeader("Referer", "https://space.bilibili.com/497423225")
                .addHeader("Origin", "https://space.bilibili.com")
                .get()
                .build();

        try (Response resp = client.newCall(req).execute()) {
            UserInfo ui = gson.fromJson(resp.body().string(), UserInfo.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}

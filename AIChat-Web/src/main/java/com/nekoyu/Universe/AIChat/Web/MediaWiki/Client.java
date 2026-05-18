package com.nekoyu.Universe.AIChat.Web.MediaWiki;

import com.google.gson.Gson;
import okhttp3.OkHttpClient;
import okhttp3.Request;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Client {
    static OkHttpClient client = new OkHttpClient();
    static Gson gson = new Gson();

    public static QueryResp query(String url) {
        Request req = new Request.Builder()
                .url(url)
                .build();
        try (var resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("Unexpected code " + resp);
            }

            return gson.fromJson(resp.body().string(), QueryResp.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** 将搜索引擎中得到的URL转换成自动化爬取的格式 */
    public static String rewriteUrl(String url) {
        Matcher matcher = Pattern.compile("^https?://([^/]+)(/.*)?$").matcher(url);
        if (!matcher.matches()) throw new IllegalArgumentException("Invalid URL: " + url);
        String domain = matcher.group(1);
        String site;
        if (domain.endsWith("moegirl.org.cn")) site = "moegirl";
        else if (domain.endsWith("wikipedia.org")) site = "wikipedia";
        else throw new IllegalArgumentException("Invalid URL: " + url);
        String path = matcher.group(2);
        if (path == null) throw new IllegalArgumentException("Invalid URL: " + url);
        path = path.substring(1); // 删掉 最前面的/
        if (path.startsWith("wiki/")) path = path.substring(5); // 删掉 wiki
        // path现在为title
        switch (site) {
            case "moegirl" -> {
                return "https://" + domain + "/api.php?action=query&titles=" + path + "&prop=extracts&explaintext=1&format=json";
            }
            case "wikipedia" -> {
                return "https://" + domain + "/w/api.php?action=query&titles=" + path + "&prop=extracts&explaintext=1&format=json";
            }
            default -> throw new IllegalArgumentException("Failed to prase URL");
        }
    }
}

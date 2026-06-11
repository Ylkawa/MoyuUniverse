package com.nekoyu.Universe.AIChat.Web.MediaWiki;

import com.google.gson.Gson;
import okhttp3.OkHttpClient;
import okhttp3.Request;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Client {
    static OkHttpClient client = new OkHttpClient();
    static Gson gson = new Gson();

    public static QueryResp query(String url) {
        Matcher matcher = Pattern.compile("^https?://([^/]+)(/.*)?$").matcher(url);
        if (!matcher.matches()) throw new IllegalArgumentException("Invalid URL: " + url);
        String domain = matcher.group(1);
        String site;
        if (domain.endsWith("moegirl.org.cn")) site = "moegirl";
        else if (domain.endsWith("wikipedia.org")) site = "wikipedia";
        else if (domain.endsWith("wiki.biligame.com")) site = "biligame";
        else throw new IllegalArgumentException("Invalid URL: " + url);
        String path = matcher.group(2);
        if (path == null) throw new IllegalArgumentException("Invalid URL: " + url);
        path = path.substring(1); // 删掉 最前面的/
        // path现在为title
        switch (site) {
            case "moegirl" -> {
                url = "https://" + domain + "/api.php?action=query&titles=" + path + "&prop=extracts&explaintext=1&format=json";
            }
            case "wikipedia" -> {
                if (path.startsWith("wiki/")) path = path.substring(5); // 删掉 wiki
                url = "https://" + domain + "/w/api.php?action=query&titles=" + path + "&prop=extracts&explaintext=1&format=json";
            }
            case "biligame" -> {
                var split = path.split("/");
                url = "https://" + domain + "/" + split[0] + "/api.php?action=query&prop=revisions&rvprop=content&titles=" + split[1] + "&format=json";
            }
            default -> throw new IllegalArgumentException("Failed to prase URL");
        }

        Request req = new Request.Builder()
                .url(url)
                .build();
        try (var resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("Unexpected code " + resp);
            }

            String body = resp.body().string();
            QueryResp queryResp = gson.fromJson(body, QueryResp.class);
            if (site.equals("biligame")) {
                QueryRespBiligames queryRespBiligames = gson.fromJson(body, QueryRespBiligames.class);
                for (Map.Entry<String, QueryRespBiligames.Query.Page> page : queryRespBiligames.query.pages.entrySet()) {
                    queryResp.query.pages.get(page.getKey()).extract = (String) page.getValue().revisions.get(0).get("*");
                }
            }
            return queryResp;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}

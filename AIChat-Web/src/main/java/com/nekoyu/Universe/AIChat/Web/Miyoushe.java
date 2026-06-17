package com.nekoyu.Universe.AIChat.Web;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.nekoyu.Universe.API.MessageChannel.MFChain;
import com.nekoyu.Universe.API.MessageChannel.MessageField.ImageField;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;

public class Miyoushe {
    static OkHttpClient client = new OkHttpClient();

    public static class Post {
        public String title;
        public MFChain content;
    }

    public static Post fetchPost(int gameId, int postId) throws IOException {
        Request request = new Request.Builder()
                .url(HttpUrl.parse("https://bbs-api.miyoushe.com/post/wapi/getPostFull").newBuilder()
                        .addQueryParameter("gids", String.valueOf(gameId))
                        .addQueryParameter("post_id", String.valueOf(postId))
                        .addQueryParameter("read", "1")
                        .build()
                )
                .addHeader("Referer", "https://www.miyoushe.com/")
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36")
                .addHeader("Accept", "application/json, text/plain, */*")
                .build();
        try (Response response = client.newCall(request).execute()) {
            Map<String, JsonElement> json = JsonParser.parseString(response.body().string()).getAsJsonObject().asMap();
            Post result = new Post();
            result.title = json.get("data").getAsJsonObject().get("post").getAsJsonObject().get("post").getAsJsonObject().get("subject").getAsString();
            String html = json.get("data").getAsJsonObject().get("post").getAsJsonObject().get("post").getAsJsonObject().get("content").getAsString(); // 这里拿到的是HTML
            result.content = fromHtml(html);
            return result;
        }
    }

    public static MFChain fromHtml(String html) {
        Document doc = Jsoup.parse(html);
        MFChain.Builder builder = new MFChain.Builder();

        parseNode(doc.body(), builder);

        return builder.build();
    }

    private static void parseNode(Node node, MFChain.Builder builder) {

        // 1. Text 节点
        if (node instanceof TextNode textNode) {
            String text = textNode.text().trim();
            if (!text.isEmpty()) {
                builder.text(text);
            }
            return;
        }

        if (!(node instanceof Element el)) {
            return;
        }

        String tag = el.tagName();

        switch (tag) {

            // ===== 文本类 =====
            case "p", "h1", "h2", "h3", "strong", "span" -> {
                String text = el.text().trim();
                if (!text.isEmpty()) {
                    builder.text(text + "\n");
                }
            }

            // ===== 图片 =====
            case "img" -> {
                String src = el.attr("src");

                // 有些是 lazy / style opacity 0 的结构，要额外兜底
                if (src.isEmpty()) return;

                try {
                    builder.image(new ImageField(new URL(src)));
                } catch (MalformedURLException ignored) {}
            }

            // ===== 图片容器（你这个HTML重点）=====
            case "div" -> {
                String cls = el.className();

                // 普通图片结构
                if (cls.contains("ql-image")) {
                    Elements imgs = el.select("img");
                    for (Element img : imgs) {
                        String src = img.attr("src");
                        if (!src.isEmpty()) {
                            try {
                                builder.image(new ImageField(new URL(src)));
                            } catch (MalformedURLException ignored) {}
                        }
                    }
                }

                // 分割线
                if (cls.contains("ql-divider")) {
                    Elements imgs = el.select("img[src]");
                    for (Element img : imgs) {
                        String src = img.attr("src");
                        try {
                            builder.image(new ImageField(new URL(src)));
                        } catch (MalformedURLException ignored) {}
                    }
                }

                // 继续递归处理子节点
                for (Node child : el.childNodes()) {
                    parseNode(child, builder);
                }
            }

            default -> {
                // 递归处理所有子节点
                for (Node child : el.childNodes()) {
                    parseNode(child, builder);
                }
            }
        }
    }
}

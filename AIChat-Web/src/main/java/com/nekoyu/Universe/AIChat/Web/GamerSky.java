package com.nekoyu.Universe.AIChat.Web;

import com.nekoyu.Universe.API.MessageChannel.MFChain;
import com.nekoyu.Universe.API.MessageChannel.MessageField.ImageField;
import com.nekoyu.Universe.API.MessageChannel.MessageField.TextField;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

public class GamerSky {
    static OkHttpClient client = new OkHttpClient();

    public static class Handbook {
        public static MFChain fetchContent(String pageUrl) throws IOException {
            String html;
            Request req = new Request.Builder()
                    .url(pageUrl)
                    .build();
            try (var response = client.newCall(req).execute()) {
                html = response.body().string();
            }
            Document doc = Jsoup.parse(html);
            Element root = doc.getElementsByClass("Mid2L_con").first();
            MFChain mfc = new MFChain();
            if (root == null) {
                mfc.add(new TextField("无法解析的网页格式，解析失败"));
                return mfc;
            }
            parse(mfc, root, new AtomicBoolean(true)); // 抓正文

            Element contentPaging = doc.getElementsByClass("Content_Paging").first();
            if (contentPaging != null) {
                Element links = contentPaging.getElementById("pe100_page_contentpage"); // 抓子页面目录
                if (links != null) {
                    StringBuilder subPages = new StringBuilder("\n此页的子页面");
                    for (Element link : links.getElementsByTag("a")) {
                        subPages.append("[").append(link.text()).append("]:");
                        subPages.append(link.attr("href")).append(" ");
                    }
                    mfc.add(new TextField(subPages.toString()));
                }
            }
            return mfc;
        }

        public static void parse(MFChain mfc, Element element, AtomicBoolean keep) throws MalformedURLException {
            if (element.className().equals("blockreference")) { // 正文结束，退出
                keep.set(false); // 一步一步往回return
                return;
            }
            if (!element.children().isEmpty()) for (Element child : element.children()) {
                if (!keep.get()) return;
                if (element.className().equals("post_ding_top_down")) return;
                parse(mfc, child, keep);
            }
            else {
                if (!element.text().isEmpty()) mfc.add(new TextField(element.text() + "\n"));
            }
            if (element.tag().toString().equals("a") && element.attr("href").endsWith(".jpg")) {
                mfc.add(new ImageField(new URL(element.attr("href").split("\\?")[1])));
            }
        }
    }
}

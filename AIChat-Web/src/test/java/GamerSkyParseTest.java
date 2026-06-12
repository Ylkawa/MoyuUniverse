import com.nekoyu.Universe.API.MessageChannel.MFChain;
import com.nekoyu.Universe.API.MessageChannel.MessageField.ImageField;
import com.nekoyu.Universe.API.MessageChannel.MessageField.TextField;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class GamerSkyParseTest {
    static OkHttpClient client = new OkHttpClient();

    public static void main(String[] args) throws Exception {
        String html;
        Request req = new Request.Builder()
                .url("https://www.gamersky.com/handbook/202507/1961712.shtml")
                .build();
        try (var response = client.newCall(req).execute()) {
            html = response.body().string();
        }
        Document doc = Jsoup.parse(html);
        Element root = doc.getElementsByClass("Mid2L_con").first();
        MFChain mfc = new MFChain();
        parse(mfc, root, new AtomicBoolean(true));
        System.out.println(mfc);

        Element links = doc.getElementsByClass("Content_Paging").first().getElementById("pe100_page_contentpage");
        List<String> pageNames = new ArrayList<>();
        List<URL> urls = new ArrayList<>();
        if (links != null) {
            for (Element link : links.getElementsByTag("a")) {
                pageNames.add(link.text());
                urls.add(new URL(link.attr("href")));
            }
        }
        System.out.println(pageNames);
        System.out.println(urls);
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
        } else {
            if (!element.text().isEmpty()) mfc.add(new TextField(element.text() + "\n"));
        }
        if (element.tag().toString().equals("a") && element.attr("href").endsWith(".jpg")) {
            mfc.add(new ImageField(new URL(element.attr("href"))));
            mfc.add(new TextField("\n"));
        }
    }
}

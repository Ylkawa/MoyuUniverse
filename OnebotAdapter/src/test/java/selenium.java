import DataObjects.Comment;
import DataObjects.Feed;
import DataObjects.Image;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.devtools.DevTools;
import org.openqa.selenium.devtools.v144.network.Network;
import org.openqa.selenium.devtools.v144.network.model.RequestId;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class selenium {
    static boolean receiving = false;

    /*
    目前这个测试的行为是：
    60秒内持续侦测有无“好友动态”的按钮（登录进入主页后左侧可以选择的分类）
    检测到就开始侦听数据流，截取好友动态相关响应体
     */
    public static void main(String[] args) {
        Gson gson = new Gson();

        ChromeDriver driver = new ChromeDriver();

        DevTools devTools = driver.getDevTools();
        devTools.createSession();

        // 启用 Network 域
        devTools.send(Network.enable(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
        ));

        // 用于存储 requestId 与 URL 的映射（线程安全）
        ConcurrentHashMap<String, String> requestUrlMap = new ConcurrentHashMap<>();

        // 监听请求即将发送事件，记录 requestId 和 URL
        devTools.addListener(Network.requestWillBeSent(), request -> {
            String requestId = request.getRequestId().toString();
            String url = request.getRequest().getUrl();
            requestUrlMap.put(requestId, url);
        });

        // 监听加载完成事件，获取响应体并打印 URL 和响应体
        devTools.addListener(Network.loadingFinished(), finished -> {
            if (!receiving) return;
            try {
                RequestId requestId = finished.getRequestId();
                String url = requestUrlMap.get(requestId.toString());
                requestUrlMap.remove(requestId.toString());

                // 获取响应体
                String body = devTools.send(
                        Network.getResponseBody(requestId)
                ).getBody();

                if (url.startsWith("https://user.qzone.qq.com/proxy/domain/ic2.qzone.qq.com/cgi-bin/feeds/feeds3_html_more?")) {
                    System.out.println("URL: " + url);
                    String cleaned = MessyDataCleaner.cleanToGsonJson(body);
                    System.out.println("Cleaned: " + cleaned);
                    FeedResponse fr = gson.fromJson(cleaned, FeedResponse.class);
                    {
                        for (FeedResponse.Data.Feed f : fr.data.data) {
                            String html = f.html;
                            // 清理广告后解析
                            FeedParser.QZoneFeed feed = FeedParser.parseFeed(html);
                            if (feed != null) {
                                System.out.println(html);
                                System.out.println("发布者：" + feed.getPublisherNick() + "(" + feed.getPublisherQQ() + ")");
                                System.out.println("正文：" + feed.getContent());
                                System.out.println("时间戳：" + feed.getPublishTimestamp());
                                System.out.println("设备：" + (feed.getDevice() == null ? "Undefined" : feed.getDevice()));
                                System.out.println("转发数：" + feed.getRetweetCount());
                                System.out.println("附图：");
                                for (String imgUrl : feed.getImageUrls()) {
                                    System.out.println("  - " + imgUrl);
                                }
                                System.out.println("点赞数：" + feed.getLikers().size());
                                for (FeedParser.QZoneFeed.Liker liker : feed.getLikers()) {
                                    System.out.println("  - " + liker.getNick() + "(" + liker.getQq() + ")");
                                }
                                System.out.println("评论数：" + feed.getComments().size());
                                for (int i = 0; i < feed.getComments().size(); i++) {
                                    FeedParser.QZoneFeed.Comment comment = feed.getComments().get(i);
                                    printComment(comment, i + 1, 0);
                                }
                                System.out.println();
                            }
                        }
                    }
                    System.out.println("------------------------");
                }
            } catch (JsonSyntaxException e) {
                e.printStackTrace();
            } catch (Exception ignored) {
                // 可能某些请求没有响应体（如重定向、跨域等），忽略异常
            }
        });

        driver.get("https://qzone.qq.com/");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            driver.quit();
            System.out.println("Driver Closed");
        }));

        new Thread(() -> {
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(60));

            WebElement element = wait.until(
                    ExpectedConditions.elementToBeClickable(By.id("tab_menu_friend"))
            );
            element.click();
            receiving = true;
            System.out.println("Clicked on friend");
        }).start();

        // 防止 main 线程退出
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private static void printComment(FeedParser.QZoneFeed.Comment comment, int index, int level) {
        StringBuilder indent = new StringBuilder();
        for (int i = 0; i < level; i++) {
            indent.append("  ");
        }
        System.out.println(indent + "评论" + index + ": " + comment.getPublisherNick() + "(" + comment.getPublisherQQ() + ") 时间:" + comment.getTimeStr() + " 内容:" + comment.getContent());
        if (comment.getReplies() != null && !comment.getReplies().isEmpty()) {
            for (int j = 0; j < comment.getReplies().size(); j++) {
                printComment(comment.getReplies().get(j), j + 1, level + 1);
            }
        }
    }
}
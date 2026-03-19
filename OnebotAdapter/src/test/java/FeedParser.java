import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;

/**
 * QQ空间Feed解析器（支持广告过滤、评论提取、附图提取及转发动态正文合并）
 * 依赖：jsoup-1.15.3.jar
 */
public class FeedParser {

    public static QZoneFeed parseFeed(String html) {
        Document doc = Jsoup.parseBodyFragment(html);
        Element feedItem = doc.selectFirst("li.f-single");
        if (feedItem == null) return null;

        if (isAdvertisement(feedItem)) return null;

        QZoneFeed feed = new QZoneFeed();

        // 1. 发布者信息
        Element userLink = feedItem.selectFirst(".user-pto a");
        if (userLink != null) {
            String qq = extractQQFromUrl(userLink.attr("href"));
            feed.setPublisherQQ(qq);
        }
        Element nickElem = feedItem.selectFirst(".f-nick .f-name");
        if (nickElem != null) {
            feed.setPublisherNick(nickElem.text());
        }

        // 2. 发布时间戳
        Element dataElem = feedItem.selectFirst("[data-abstime]");
        if (dataElem != null) {
            String abstime = dataElem.attr("data-abstime");
            if (!abstime.isEmpty()) feed.setPublishTimestamp(Long.parseLong(abstime));
        }

        // 3. 正文（处理转发）
        StringBuilder contentBuilder = new StringBuilder();

        // 获取原发布者QQ，判断是否转发
        Element feedData = feedItem.selectFirst("i[name=feed_data]");
        String origUin = feedData != null ? feedData.attr("data-origuin") : "";
        boolean isRepost = origUin != null && !origUin.isEmpty() && !origUin.equals(feed.getPublisherQQ());

        // 转发者自己的评论
        Element infoDiv = feedItem.selectFirst(".f-info");
        if (infoDiv != null) {
            infoDiv.select("a[data-cmd=qz_toggle]").remove(); // 移除“展开全文”
            String infoText = infoDiv.text().trim();
            if (!infoText.isEmpty()) contentBuilder.append(infoText);
        }

        // 如果是转发，添加原动态内容
        if (isRepost) {
            Element txtBox = feedItem.selectFirst(".f-ct-txtimg .txt-box");
            if (txtBox != null) {
                String txtBoxText = txtBox.text().trim();
                if (!txtBoxText.isEmpty()) {
                    if (contentBuilder.length() > 0) contentBuilder.append("\n");
                    contentBuilder.append(txtBoxText);
                }
            }
        }

        // 非转发且没有 .f-info 时，尝试 .txt-box（作为后备）
        if (!isRepost && contentBuilder.length() == 0) {
            Element txtBox = feedItem.selectFirst(".f-ct-txtimg .txt-box");
            if (txtBox != null) contentBuilder.append(txtBox.text().trim());
        }

        feed.setContent(contentBuilder.toString().trim());

        // 4. 发布设备
        Element deviceSpan = feedItem.selectFirst(".f-reprint span.phone-style");
        if (deviceSpan != null) feed.setDevice(deviceSpan.text());

        // 5. 转发数
        if (dataElem != null) {
            String retweet = dataElem.attr("data-retweetcount");
            if (!retweet.isEmpty()) feed.setRetweetCount(Integer.parseInt(retweet));
        }

        // 6. 点赞者列表
        Elements likeItems = feedItem.select(".f-like-list .user-list a");
        List<QZoneFeed.Liker> likers = new ArrayList<>();
        for (Element a : likeItems) {
            String qq = extractQQFromUrl(a.attr("href"));
            String nick = a.text();
            likers.add(new QZoneFeed.Liker(qq, nick));
        }
        feed.setLikers(likers);

        // 7. 评论列表
        Elements commentRoots = feedItem.select(".mod-comments .comments-list > ul > li.comments-item[data-type=commentroot]");
        List<QZoneFeed.Comment> comments = new ArrayList<>();
        for (Element rootLi : commentRoots) comments.add(parseComment(rootLi));
        feed.setComments(comments);

        // 8. 附图列表
        Elements imgItems = feedItem.select(".img-box a.img-item[data-pickey]");
        List<String> imageUrls = new ArrayList<>();
        for (Element a : imgItems) {
            String pickey = a.attr("data-pickey");
            if (pickey != null && pickey.contains(",")) {
                String[] parts = pickey.split(",", 2);
                if (parts.length > 1) {
                    String url = parts[1].trim().replace("&amp;", "&");
                    imageUrls.add(url);
                }
            } else {
                Element img = a.selectFirst("img");
                if (img != null) {
                    String src = img.attr("src");
                    if (src != null && !src.isEmpty()) imageUrls.add(src);
                }
            }
        }
        feed.setImageUrls(imageUrls);

        return feed;
    }

    private static boolean isAdvertisement(Element feedItem) {
        if (feedItem.hasClass("f-single-biz")) return true;
        if (feedItem.selectFirst("[data-advfeed-click-url]") != null) return true;
        Element dataElem = feedItem.selectFirst("i[name=feed_data][data-fkey]");
        if (dataElem != null) {
            String fkey = dataElem.attr("data-fkey");
            if (fkey != null && fkey.startsWith("advertisement")) return true;
        }
        if (feedItem.selectFirst(".f-single-top span:contains(广告)") != null) return true;
        return false;
    }

    private static QZoneFeed.Comment parseComment(Element li) {
        QZoneFeed.Comment comment = new QZoneFeed.Comment();
        comment.setPublisherQQ(li.attr("data-uin"));
        Element nickLink = li.selectFirst(".comments-content .nickname");
        if (nickLink != null) comment.setPublisherNick(nickLink.text());

        Element contentDiv = li.selectFirst(".comments-content");
        if (contentDiv != null) {
            StringBuilder sb = new StringBuilder();
            for (org.jsoup.nodes.Node node : contentDiv.childNodes()) {
                if (node instanceof TextNode) sb.append(((TextNode) node).text());
                else if (node instanceof Element) {
                    Element e = (Element) node;
                    if (!e.hasClass("nickname") && !e.hasClass("name")) sb.append(e.text());
                }
            }
            comment.setContent(sb.toString().trim());
        }

        Element timeSpan = li.selectFirst(".comments-op .state");
        if (timeSpan != null) comment.setTimeStr(timeSpan.text());

        Element subList = li.selectFirst(".mod-comments-sub > ul");
        if (subList != null) {
            List<QZoneFeed.Comment> replies = new ArrayList<>();
            for (Element replyLi : subList.select("> li.comments-item"))
                replies.add(parseComment(replyLi));
            comment.setReplies(replies);
        }
        return comment;
    }

    private static String extractQQFromUrl(String url) {
        if (url == null || url.isEmpty()) return "";
        for (String part : url.split("/"))
            if (part.matches("\\d+")) return part;
        return "";
    }

    // ==================== 数据模型 ====================
    public static class QZoneFeed {
        private String publisherNick, publisherQQ, content, device;
        private long publishTimestamp;
        private int retweetCount;
        private List<Liker> likers;
        private List<Comment> comments;
        private List<String> imageUrls;

        // getters and setters (省略，请自行生成)
        public String getPublisherNick() { return publisherNick; }
        public void setPublisherNick(String publisherNick) { this.publisherNick = publisherNick; }
        public String getPublisherQQ() { return publisherQQ; }
        public void setPublisherQQ(String publisherQQ) { this.publisherQQ = publisherQQ; }
        public long getPublishTimestamp() { return publishTimestamp; }
        public void setPublishTimestamp(long publishTimestamp) { this.publishTimestamp = publishTimestamp; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public String getDevice() { return device; }
        public void setDevice(String device) { this.device = device; }
        public int getRetweetCount() { return retweetCount; }
        public void setRetweetCount(int retweetCount) { this.retweetCount = retweetCount; }
        public List<Liker> getLikers() { return likers; }
        public void setLikers(List<Liker> likers) { this.likers = likers; }
        public List<Comment> getComments() { return comments; }
        public void setComments(List<Comment> comments) { this.comments = comments; }
        public List<String> getImageUrls() { return imageUrls; }
        public void setImageUrls(List<String> imageUrls) { this.imageUrls = imageUrls; }

        public static class Liker {
            private String qq, nick;
            public Liker(String qq, String nick) { this.qq = qq; this.nick = nick; }
            public String getQq() { return qq; }
            public String getNick() { return nick; }
        }

        public static class Comment {
            private String publisherQQ, publisherNick, content, timeStr;
            private List<Comment> replies;
            // getters and setters...
            public String getPublisherQQ() { return publisherQQ; }
            public void setPublisherQQ(String publisherQQ) { this.publisherQQ = publisherQQ; }
            public String getPublisherNick() { return publisherNick; }
            public void setPublisherNick(String publisherNick) { this.publisherNick = publisherNick; }
            public String getContent() { return content; }
            public void setContent(String content) { this.content = content; }
            public String getTimeStr() { return timeStr; }
            public void setTimeStr(String timeStr) { this.timeStr = timeStr; }
            public List<Comment> getReplies() { return replies; }
            public void setReplies(List<Comment> replies) { this.replies = replies; }
        }
    }

    private static void printFeed(QZoneFeed feed, String title) {
        System.out.println("===== " + title + " =====");
        System.out.println("发布者：" + feed.getPublisherNick() + "(" + feed.getPublisherQQ() + ")");
        System.out.println("正文：\n" + feed.getContent());
        System.out.println("时间戳：" + feed.getPublishTimestamp());
        System.out.println("设备：" + (feed.getDevice() == null ? "无" : feed.getDevice()));
        System.out.println("转发数：" + feed.getRetweetCount());
        System.out.println("图片数：" + feed.getImageUrls().size());
        for (String url : feed.getImageUrls()) System.out.println("  - " + url);
        System.out.println("点赞数：" + feed.getLikers().size());
        for (QZoneFeed.Liker liker : feed.getLikers()) System.out.println("  - " + liker.getNick() + "(" + liker.getQq() + ")");
        System.out.println("评论数：" + feed.getComments().size());
        for (int i = 0; i < feed.getComments().size(); i++) printComment(feed.getComments().get(i), i + 1, 0);
        System.out.println();
    }

    private static void printComment(QZoneFeed.Comment comment, int index, int level) {
        StringBuilder indent = new StringBuilder();
        for (int i = 0; i < level; i++) indent.append("  ");
        System.out.println(indent + "评论" + index + ": " + comment.getPublisherNick() + "(" + comment.getPublisherQQ() + ") 时间:" + comment.getTimeStr() + " 内容:" + comment.getContent());
        if (comment.getReplies() != null)
            for (int j = 0; j < comment.getReplies().size(); j++)
                printComment(comment.getReplies().get(j), j + 1, level + 1);
    }
}
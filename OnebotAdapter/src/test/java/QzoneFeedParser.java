// QzoneFeedParser.java - 修正版
import DataObjects.Comment;
import DataObjects.Feed;
import DataObjects.Image;
import DataObjects.User;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class QzoneFeedParser {

    /**
     * 解析单条动态HTML
     */
    public Feed parseFeed(String html) {
        if (html == null || html.trim().isEmpty()) {
            return null;
        }

        Document doc = Jsoup.parse(html);
        Element feedElement = doc.selectFirst("li.f-single");

        if (feedElement == null) {
            return null;
        }

        return parseSingleFeedElement(feedElement);
    }

    /**
     * 解析多条动态HTML
     */
    public List<Feed> parseFeeds(String html) {
        List<Feed> feeds = new ArrayList<>();
        if (html == null || html.trim().isEmpty()) {
            return feeds;
        }

        Document doc = Jsoup.parse(html);
        Elements feedElements = doc.select("li.f-single");

        for (Element feedElement : feedElements) {
            if (isAdvertisement(feedElement)) {
                continue;
            }
            Feed feed = parseSingleFeedElement(feedElement);
            if (feed != null) {
                feeds.add(feed);
            }
        }

        return feeds;
    }

    /**
     * 解析单条动态元素 - 核心方法
     */
    private Feed parseSingleFeedElement(Element feedElement) {
        Feed feed = new Feed();

        // 1. 获取动态ID（从li标签的id属性）
        String feedId = feedElement.id();
        if (feedId != null && !feedId.isEmpty()) {
            feed.setFeedId(feedId);
        }

        // 2. 解析发布者信息
        feed.setPublisher(parsePublisher(feedElement));

        // 3. 解析动态内容（优先f-info，其次txt-box）
        parseFeedContent(feedElement, feed);

        // 4. 解析图片
        parseImages(feedElement, feed);

        // 5. 解析互动数据
        parseInteractionData(feedElement, feed);

        // 6. 解析评论
        parseComments(feedElement, feed);

        // 7. 解析点赞用户列表
        parseLikers(feedElement, feed);

        return feed;
    }

    /**
     * 解析发布者信息
     */
    private User parsePublisher(Element feedElement) {
        User user = new User();

        Element avatarLink = feedElement.selectFirst(".user-pto a");
        if (avatarLink != null) {
            String href = avatarLink.attr("href");
            user.setQzoneUrl(href);

            // 从URL提取QQ号
            if (href.contains("user.qzone.qq.com/")) {
                String[] parts = href.split("/");
                for (String part : parts) {
                    if (part.matches("\\d+")) {
                        user.setUin(part);
                        break;
                    }
                }
            }

            Element avatarImg = avatarLink.selectFirst("img");
            if (avatarImg != null) {
                user.setAvatarUrl(avatarImg.attr("src"));
            }
        }

        Element nickElement = feedElement.selectFirst(".f-nick a.f-name");
        if (nickElement != null) {
            user.setNick(nickElement.text().trim());
        }

        return user;
    }

    /**
     * 解析动态内容 - 修正版
     */
    private void parseFeedContent(Element feedElement, Feed feed) {
        // 【修正】优先从 .f-info 获取内容（这是纯文本动态的内容位置）
        Element fInfo = feedElement.selectFirst(".f-info");
        if (fInfo != null && !fInfo.text().trim().isEmpty()) {
            feed.setContent(fInfo.text().trim());
        } else {
            // 其次从 .txt-box 获取（这是图文混合动态的内容位置）
            Element txtBox = feedElement.selectFirst(".txt-box");
            if (txtBox != null) {
                String content = txtBox.text().trim();
                // 移除昵称前缀
                content = content.replaceAll("^\\S+\\s*：", "").trim();
                feed.setContent(content);
            }
        }

        // 获取时间文本
        Element timeElement = feedElement.selectFirst(".user-info .info-detail .state");
        if (timeElement != null) {
            feed.setTimeText(timeElement.text().trim());
        }

        // 获取时间戳
        Element feedData = feedElement.selectFirst("i[name=\"feed_data\"]");
        if (feedData != null) {
            String abstime = feedData.attr("data-abstime");
            if (abstime != null && !abstime.isEmpty()) {
                try {
                    long timestamp = Long.parseLong(abstime);
                    feed.setPublishTimestamp(timestamp);
                    feed.setPublishTime(new Date(timestamp * 1000));
                } catch (NumberFormatException e) {
                    // 忽略
                }
            }

            // 获取动态Key
            String fkey = feedData.attr("data-fkey");
            if (fkey != null && !fkey.isEmpty()) {
                feed.setFeedKey(fkey);
            }
        }

        // 获取发布设备
        Element deviceElement = feedElement.selectFirst(".f-reprint .phone-style");
        if (deviceElement != null) {
            feed.setDevice(deviceElement.text().trim());
        }
    }

    /**
     * 解析图片列表
     */
    private void parseImages(Element feedElement, Feed feed) {
        Elements imgElements = feedElement.select(".img-box .img-item");

        int index = 0;
        for (Element imgElement : imgElements) {
            Image image = new Image();
            image.setPicIndex(index++);

            Element imgTag = imgElement.selectFirst("img");
            if (imgTag != null) {
                String src = imgTag.attr("src");
                // 处理lazy load
                if (src == null || src.isEmpty() || src.contains("b.gif")) {
                    src = imgTag.attr("data-trueSrc");
                }
                image.setImageUrl(src);
            }

            // 获取原图URL
            String pickey = imgElement.attr("data-pickey");
            if (pickey != null && !pickey.isEmpty() && pickey.contains(",")) {
                String[] parts = pickey.split(",", 2);
                if (parts.length > 1) {
                    image.setOriginUrl(parts[1]);
                }
            }

            // 获取宽高
            String width = imgElement.attr("data-width");
            String height = imgElement.attr("data-height");
            if (width != null && !width.isEmpty()) {
                try { image.setWidth(Integer.parseInt(width)); } catch (NumberFormatException e) {}
            }
            if (height != null && !height.isEmpty()) {
                try { image.setHeight(Integer.parseInt(height)); } catch (NumberFormatException e) {}
            }

            feed.getImages().add(image);
        }
    }

    /**
     * 解析互动数据
     */
    private void parseInteractionData(Element feedElement, Feed feed) {
        // 点赞数
        Element likeBtn = feedElement.selectFirst("a.qz_like_btn_v3");
        if (likeBtn != null) {
            String likeCnt = likeBtn.attr("data-likecnt");
            if (likeCnt != null && !likeCnt.isEmpty()) {
                try {
                    feed.setLikeCount(Integer.parseInt(likeCnt));
                } catch (NumberFormatException e) {
                    feed.setLikeCount(0);
                }
            }
        }

        // 转发数
        Element feedData = feedElement.selectFirst("i[name=\"feed_data\"]");
        if (feedData != null) {
            String retweetCount = feedData.attr("data-retweetcount");
            if (retweetCount != null && !retweetCount.isEmpty()) {
                try {
                    feed.setRetweetCount(Integer.parseInt(retweetCount));
                } catch (NumberFormatException e) {
                    feed.setRetweetCount(0);
                }
            }
        }

        // 评论数
        Elements comments = feedElement.select(".comments-list .comments-item[data-type=\"commentroot\"]");
        feed.setCommentCount(comments.size());
    }

    /**
     * 解析评论列表
     */
    private void parseComments(Element feedElement, Feed feed) {
        Elements commentElements = feedElement.select(".comments-list .comments-item[data-type=\"commentroot\"]");

        for (Element commentElement : commentElements) {
            Comment comment = parseComment(commentElement);
            if (comment != null) {
                feed.getComments().add(comment);
            }
        }
    }

    /**
     * 解析单条评论
     */
    private Comment parseComment(Element commentElement) {
        Comment comment = new Comment();

        String uin = commentElement.attr("data-uin");
        String nick = commentElement.attr("data-nick");
        comment.setUin(uin);
        comment.setNick(nick);

        Element avatarImg = commentElement.selectFirst(".ui-avatar img");
        if (avatarImg != null) {
            comment.setAvatarUrl(avatarImg.attr("src"));
        }

        Element contentElement = commentElement.selectFirst(".comments-content");
        if (contentElement != null) {
            String content = contentElement.text();
            // 移除昵称和时间
            content = content.replaceAll("^" + Pattern.quote(nick) + "\\s*:\\s*", "").trim();
            content = content.replaceAll("\\d{1,2}:\\d{2}\\s*$", "").trim();
            comment.setContent(content);
        }

        Element timeElement = commentElement.selectFirst(".comments-op .state");
        if (timeElement != null) {
            comment.setTime(timeElement.text().trim());
        }

        // 评论中的图片
        Elements imgElements = commentElement.select(".comments-thumbnails .img-item");
        for (Element imgElement : imgElements) {
            Image image = new Image();
            Element imgTag = imgElement.selectFirst("img");
            if (imgTag != null) {
                String src = imgTag.attr("data-trueSrc");
                if (src == null || src.isEmpty()) {
                    src = imgTag.attr("src");
                }
                image.setImageUrl(src);
            }
            comment.getImages().add(image);
        }

        return comment;
    }

    /**
     * 解析点赞用户列表 - 修正版
     */
    private void parseLikers(Element feedElement, Feed feed) {
        Element likeList = feedElement.selectFirst(".f-like-list .user-list");
        if (likeList == null) {
            return;
        }

        // 【修正】直接获取所有a标签，提取文本
        Elements likerElements = likeList.select("a.q_namecard");
        for (Element likerElement : likerElements) {
            // 获取纯文本（移除img标签）
            String nick = likerElement.ownText().trim();
            if (!nick.isEmpty()) {
                feed.getLikers().add(nick);
            }
        }
    }

    /**
     * 判断是否为广告内容
     */
    private boolean isAdvertisement(Element element) {
        String classAttr = element.className();
        String html = element.html();

        String[] adKeywords = {"广告", "sponsor", "promotion", "推广", "f-adorn"};
        for (String keyword : adKeywords) {
            if (classAttr.contains(keyword) || html.contains(keyword)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 清理HTML中的广告内容
     */
    public String cleanAdContent(String html) {
        if (html == null || html.trim().isEmpty()) {
            return html;
        }

        Document doc = Jsoup.parse(html);
        doc.select("[class*=ad], [class*=sponsor], .f-adorn-top, .f-adorn-bottom").remove();

        return doc.body() != null ? doc.body().html() : html;
    }
}

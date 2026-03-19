package DataObjects;

import lombok.Data;

import java.util.List;
import java.util.ArrayList;
import java.util.Date;

@Data
public class Feed {
    private String feedKey;           // 动态Key（更稳定）
    private String feedId;            // 动态ID（从feed_data获取）
    private String publisherUin;      // 发布者QQ号
    private User publisher;           // 发布者详细信息
    private String content;           // 文字内容
    private List<Image> images;       // 图片列表
    private Date publishTime;         // 发布时间
    private Long publishTimestamp;    // 时间戳
    private String timeText;          // 时间文本
    private String device;            // 发布设备
    private Integer viewCount;        // 浏览量
    private Integer likeCount;        // 点赞数
    private Integer commentCount;     // 评论数
    private Integer retweetCount;     // 转发数
    private List<String> likers;      // 点赞用户列表
    private List<Comment> comments;   // 评论列表

    public Feed() {
        this.images = new ArrayList<>();
        this.comments = new ArrayList<>();
        this.likers = new ArrayList<>();
    }
}

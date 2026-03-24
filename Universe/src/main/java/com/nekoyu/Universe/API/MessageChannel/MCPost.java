package com.nekoyu.Universe.API.MessageChannel;

import javax.annotation.Nullable;
import java.util.List;

public class MCPost {
    public String sessionId;
    public long timestamp;
    public Account poster;
    public MCMessage content;
    @Nullable
    public MCPost repost; // 如果这个帖子是转载自某个帖子，则在此处指向原帖子，此值不保证提供
    public List<Account> liker; // 部分点赞者
    public int likeCount; // 总共点赞数
    public List<Account> reposts; // 转发人员列表
    public int repostCount;
    public List<MCMessage> replies; // 回复
    public int viewCount;
}
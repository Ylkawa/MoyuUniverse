package com.nekoyu.Universe.API.MessageChannel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public class MCPost {
    public String sessionId;
    public int level = 0;
    public Account poster;
    public long timestamp = 0;
    public MFChain messageFields;
    public String messageString;
    @Nullable
    public MCPost repost; // 如果这个帖子是转载自某个帖子，则在此处指向原帖子，此值不保证提供
    public List<Account> likers; // 部分点赞者
    public int likeCount = 0; // 总共点赞数
    public List<Account> reposts; // 转发人员列表
    public int repostCount = 0;
    public List<MCMessage> replies; // 回复
    public int viewCount = 0;

    public MCPost() {
        likers = new ArrayList<>();
        reposts = new ArrayList<>();
        replies = new ArrayList<>();
        messageFields = new MFChain();
    }

    public void sendLike() {
        MessageChannelManager.sendLikeToPost(sessionId);
    }
    public void reply(MFChain message) {
        MessageChannelManager.replyPost(sessionId, message);
    }

    public String getLocationId() {
        return poster.platform + ":" + sessionId.split(":")[1];
    }
}
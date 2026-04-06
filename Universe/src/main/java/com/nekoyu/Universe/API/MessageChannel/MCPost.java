package com.nekoyu.Universe.API.MessageChannel;

import com.nekoyu.Universe.API.MessageChannel.MessageField.MsgField;
import com.nekoyu.Universe.Universe;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class MCPost {
    public String sessionId;
    public Account poster;
    public long timestamp = 0;
    public LinkedList<MsgField> messageFields;
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
        messageFields = new LinkedList<>();
    }

    public void reply(MessageChain message) {
        Universe.MessageChannelManager.replyPost(sessionId, message);
    }
}
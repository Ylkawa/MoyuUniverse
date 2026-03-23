package com.nekoyu.Universe.API.MessageChannel;

import javax.annotation.Nullable;
import java.util.List;

public class MCPost {
    String sessionId;
    Account poster;
    MCMessage content;
    @Nullable
    MCPost repost; // 如果这个帖子是转载自某个帖子，则在此处指向原帖子，此值不保证提供
    List<Account> liker; // 部分点赞者
    int likeCount; // 总共点赞数
    List<Account> reposts; // 转发人员列表
    int repostCount;
    List<MCMessage> replies; // 回复
    int viewCount;
}
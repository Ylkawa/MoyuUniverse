package com.nekoyu.Universe.API.MessageChannel.Features;

import com.nekoyu.Universe.API.MessageChannel.MessageChain;

public interface PostChat {
    void sendLike(String sessionId);
    void replyPost(String sessionId, MessageChain message);
    void repost(String sessionId, MessageChain message);
}

package com.nekoyu.Universe.API.MessageChannel.Features;

import com.nekoyu.Universe.API.MessageChannel.MFChain;

public interface PostChat {
    void sendLike(String sessionId);
    void replyPost(String sessionId, MFChain message);
    void repost(String sessionId, MFChain message);
}

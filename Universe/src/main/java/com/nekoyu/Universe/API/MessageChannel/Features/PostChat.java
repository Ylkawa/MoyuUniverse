package com.nekoyu.Universe.API.MessageChannel.Features;

import com.nekoyu.Universe.API.MessageChannel.MessageChain;

public interface PostChat {
    void replyPost(String sessionId, MessageChain message);
}

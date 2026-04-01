package com.nekoyu.Universe.API.MessageChannel.Features;

import com.nekoyu.Universe.API.MessageChannel.MessageChain;

public interface SessionChat {
    int sendMessage(String sessionId, MessageChain message);
}

package com.nekoyu.Universe.API.MessageChannel.Features;

import com.nekoyu.Universe.API.MessageChannel.MFChain;

public interface SessionChat {
    int sendMessage(String sessionId, MFChain message);
}

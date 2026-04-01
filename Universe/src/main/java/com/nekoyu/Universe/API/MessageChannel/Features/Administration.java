package com.nekoyu.Universe.API.MessageChannel.Features;

import com.nekoyu.Universe.API.MessageChannel.UnsupportedAction;

public interface Administration {
    void setSessionName(String sessionId, String name) throws UnsupportedAction;
}

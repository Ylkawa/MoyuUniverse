package com.nekoyu.Universe.API.MessageChannel;

import com.nekoyu.Universe.API.MessageSession;
import com.nekoyu.Universe.Universe;

public abstract class MessageChannel {
    public String type;
    public final String ID;

    abstract public MessageSession getSession(String sessionId);
    abstract public void load();
    abstract public void stop();
    abstract public void sendMessage(String message, String sessionId);

    protected void broadcastMessage(String sessionId, MCMessage message) {
        message.sessionId = ID + ":" + sessionId;
        Universe.MessageChannelManager.onMessage(message);
    }

    public MessageChannel(String id) {
        this.ID = id;
    }
}

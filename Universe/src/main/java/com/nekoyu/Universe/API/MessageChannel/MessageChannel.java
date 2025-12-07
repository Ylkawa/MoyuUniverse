package com.nekoyu.Universe.API.MessageChannel;

import com.nekoyu.Universe.API.MessageSession;
import com.nekoyu.Universe.Universe;

public abstract class MessageChannel {
    public String type;
    public final String ID;
    public String accountId;
    public String nickname;

    abstract public MessageSession getSession(String sessionId);
    abstract public void load();
    abstract public void stop();
    abstract public int sendMessage(String sessionId, String message);
    abstract public void setSessionName(String sessionId, String name) throws UnsupportedAction;

    protected void broadcastMessage(String sessionId, MCMessage message) {
        message.sessionId = ID + ":" + sessionId;
        Universe.MessageChannelManager.onMessage(this, message);
    }

    public MessageChannel(String id) {
        this.ID = id;
    }
}

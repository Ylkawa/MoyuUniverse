package com.nekoyu.Universe.API.MessageChannel;

import com.nekoyu.Universe.API.MessageSession;
import com.nekoyu.Universe.Universe;

import java.awt.*;

public abstract class MessageChannel {
    public Color mainColor = new Color(255, 255, 255);
    public String type;
    public final String ID;
    public String accountId;
    public String nickname;

    abstract public MessageSession getSession(String sessionId);
    abstract public void load();
    abstract public void stop();
    abstract public int sendMessage(String sessionId, String message);
    abstract public void setSessionName(String sessionId, String name) throws UnsupportedAction;
    abstract public SessionInfo getSessionInfo(String sessionId);

    protected void broadcastMessage(String sessionId, MCMessage message) {
        message.sessionId = ID + ":" + sessionId;
        Universe.MessageChannelManager.onMessage(this, message);
    }

    public MessageChannel(String id) {
        this.ID = id;
    }
}

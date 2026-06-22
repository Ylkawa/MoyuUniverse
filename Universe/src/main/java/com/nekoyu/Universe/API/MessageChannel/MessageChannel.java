package com.nekoyu.Universe.API.MessageChannel;

import com.nekoyu.Universe.API.MessageChannel.events.MCEvent;

import java.awt.*;

public abstract class MessageChannel {
    public Color mainColor = Color.WHITE; // 默认主题色就是白色
    public String type;
    public final String ID;
    public String accountId;
    public String nickname;
    public Account loginAccount;

    abstract public void load();
    abstract public void stop();
    abstract public Session getSession(String sessionId);

    protected void broadcastMessage(String sessionId, MCMessage message) {
        message.sessionId = ID + ":" + sessionId;
        MessageChannelManager.onMessage(this, message);
    }

    protected void broadcastMessage(String sessionId, MCPost message) {
        message.sessionId = ID + ":" + sessionId;
        MessageChannelManager.onMessage(this, message);
    }

    protected void broadcastEvent(String eventId, MCEvent event) {
        event.eventId = ID + ":" + eventId;
        event.messageChannel = this;
        MessageChannelManager.onEvent(event);
    }

    public MessageChannel(String id) {
        this.ID = id;
    }
}

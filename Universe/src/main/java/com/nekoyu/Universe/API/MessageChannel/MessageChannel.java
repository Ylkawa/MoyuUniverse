package com.nekoyu.Universe.API.MessageChannel;

import com.nekoyu.Universe.API.MessageChannel.MessageField.MsgField;
import com.nekoyu.Universe.API.MessageSession;
import com.nekoyu.Universe.Universe;

import java.awt.*;
import java.util.LinkedList;

public abstract class MessageChannel {
    public Color mainColor = Color.WHITE; // 默认主题色就是白色
    public String type;
    public final String ID;
    public String accountId;
    public String nickname;
    public Account loginAccount;

    abstract public MessageSession getChatSession(String sessionId);
    abstract public void load();
    abstract public void stop();
    abstract public Session getSession(String sessionId);

    protected void broadcastMessage(String sessionId, MCMessage message) {
        message.sessionId = ID + ":" + sessionId;
        Universe.MessageChannelManager.onMessage(this, message);
    }

    protected void broadcastMessage(String sessionId, MCPost message) {
        message.sessionId = ID + ":" + sessionId;
        Universe.MessageChannelManager.onMessage(this, message);
    }

    public MessageChannel(String id) {
        this.ID = id;
    }
}

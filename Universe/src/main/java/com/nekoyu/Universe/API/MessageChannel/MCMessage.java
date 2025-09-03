package com.nekoyu.Universe.API.MessageChannel;

import com.nekoyu.Universe.API.MessageChannel.MessageField.MsgField;

import java.io.File;
import java.util.LinkedList;

public class MCMessage {
    public Account receiver;
    public Account sender;
    public String messageString;
    public String sessionId;
    public QuickAction action;
    public long time;
    public int id;
    public LinkedList<MsgField> messageFields;

    public MCMessage() {
        sender = new Account();
        receiver = new Account();
        messageFields = new LinkedList<>();
    }
}

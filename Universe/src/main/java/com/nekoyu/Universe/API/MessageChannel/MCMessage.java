package com.nekoyu.Universe.API.MessageChannel;

public class MCMessage {
    public Account receiver;
    public Account sender;
    public String message;
    public String sessionId;
    public QuickAction action;

    public MCMessage() {
        sender = new Account();
        receiver = new Account();
    }
}

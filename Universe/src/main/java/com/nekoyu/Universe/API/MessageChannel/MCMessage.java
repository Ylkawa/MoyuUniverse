package com.nekoyu.Universe.API.MessageChannel;

import java.io.File;
import java.net.URI;
import java.util.LinkedList;

public class MCMessage {
    public Account receiver;
    public Account sender;
    public String message;
    public String sessionId;
    public QuickAction action;
    public long time;
    public int id;
    public LinkedList<Field> MessageFields = new LinkedList<>();

    public MCMessage() {
        sender = new Account();
        receiver = new Account();
    }

    public static class Field {
        String type;
    }

    public static class TextField extends Field {
        String text;
    }

    public static class ImageField extends Field {
        File file;
    }
}

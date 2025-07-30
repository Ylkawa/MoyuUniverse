package com.nekoyu.Universe.API;

import org.java_websocket.WebSocket;

public class Planet {
    String ID;
    String Type;
    WebSocket webSocket;
    boolean local;

    public String getID() {
        return ID;
    }

    public String getType() {
        return Type;
    }
}

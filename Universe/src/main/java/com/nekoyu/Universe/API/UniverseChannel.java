package com.nekoyu.Universe.API;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.nekoyu.Universe.LawsLoader.Law;
import com.nekoyu.Universe.Universe;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.*;

public class UniverseChannel {
    private WebSocketServer wsServer;
    private int port;
    private final Map<String, UniverseListener> listeners = new HashMap<>();
    private String token = null;
    private final Logger logger = LoggerFactory.getLogger(getClass());

    public void setToken(String token) {
        this.token = token;
    }

    public UniverseChannel(int port) {
        this.port = port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public void load() {
        wsServer = new WebSocketServer(new InetSocketAddress(port)) {
            @Override
            public void onOpen(WebSocket webSocket, ClientHandshake clientHandshake) {
                if (token != null) {
                    if (!Objects.equals(clientHandshake.getFieldValue("Token"), token)) webSocket.close(400);
                    return;
                }
            }

            @Override
            public void onClose(WebSocket webSocket, int i, String s, boolean b) {

            }

            @Override
            public void onMessage(WebSocket webSocket, String rawContent) {
                try {
                    UCMessage ucm = new Gson().fromJson(rawContent, UCMessage.class);
                    if (ucm.target != null && ucm.message != null) {
                        listeners.get(ucm.target).onMessage(ucm.message);
                    }
                } catch (JsonSyntaxException e) {
                    logger.error("{} 发送了一段不符合 JSON 规范的消息", webSocket.getRemoteSocketAddress());
                }
            }

            @Override
            public void onError(WebSocket webSocket, Exception e) {

            }

            @Override
            public void onStart() {
                Universe.logger.info("宇宙穿隧加载成功");
            }
        };
    }

    public void registerListener(Law law) {
        if (law instanceof UniverseListener) listeners.put(law.ID, (UniverseListener) law);
    }

    public void unRegisterListener(Law law) {
        if (law instanceof UniverseListener) listeners.remove(law.ID);
    }

    private class UCMessage {
        String target;
        Map message;
    }
}

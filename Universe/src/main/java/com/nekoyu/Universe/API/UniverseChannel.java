package com.nekoyu.Universe.API;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.nekoyu.Universe.LawsLoader.Law;
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
    private final Multimap<String, UniverseListener> listeners = ArrayListMultimap.create();
    private final Multimap<String, WebSocket> clientGroup = ArrayListMultimap.create();
    private String token = null;
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Map<String, WebSocket> clientList = new HashMap<>();

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
                    if (!Objects.equals(clientHandshake.getFieldValue("Token"), token)) {
                        webSocket.close(400);
                        logger.info("拒绝了来自 {} 的连接，因为口令校验不通过", webSocket.getRemoteSocketAddress());
                        return;
                    }
                    clientList.put(clientHandshake.getFieldValue("ID"), webSocket);
                    clientGroup.put(clientHandshake.getFieldValue("Type"), webSocket);
                    Map<String, String> attachment = new HashMap<>();
                    attachment.put("ID", clientHandshake.getFieldValue("ID"));
                    attachment.put("Type", clientHandshake.getFieldValue("Type"));
                    webSocket.setAttachment(attachment);
                    logger.info("{} ({}-{}) 通过口令校验并创建了连接", webSocket.getRemoteSocketAddress(), clientHandshake.getFieldValue("Type"), clientHandshake.getFieldValue("ID"));
                }
            }

            @Override
            public void onClose(WebSocket conn, int code, String reason, boolean remote) {
                logger.info("{} 断开了连接 {}: {}", conn.getRemoteSocketAddress(), code, reason);
            }

            @Override
            public void onMessage(WebSocket webSocket, String rawContent) {
                try {
                    UniverseChannelMessage ucm = new Gson().fromJson(rawContent, UniverseChannelMessage.class);
                    if (ucm.tag != null && ucm.args != null) {
                        for (UniverseListener listener : listeners.get(ucm.tag)) {
                            listener.onMessage(( (Map<String, String>) webSocket.getAttachment()).get("ID"), ucm.message, ucm.args);
                        }
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
                logger.info("宇宙穿隧加载成功");
            }
        };
        wsServer.start();
    }

    public void registerListener(String tag, Law law) {
        synchronized (listeners) {
            if (law instanceof UniverseListener) listeners.put(tag, (UniverseListener) law);
        }
    }

    public void unRegisterListener(String tag, Law law) {
        synchronized (listeners) {
            if (law instanceof UniverseListener) listeners.remove(tag, law);
        }
    }
}
package com.nekoyu.MoyuUniverse.Nya.OnebotAdapter;

import com.google.gson.Gson;
import com.nekoyu.API.MessageChannel;
import com.nekoyu.API.MessageSession;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.Request.SendGroupMessage;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.Request.SendPrivateMessage;
import com.nekoyu.Universe;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

public class OnebotChannel extends MessageChannel {
    WebSocketClient wsConnection;
    boolean isReady = true;
    Logger logger = LoggerFactory.getLogger(this.getClass());
    URI uri;
    String ID;
    String token;
    int falls = 0;

    // 构建对象的时候，需要同时准备配置
    public OnebotChannel() {}

    @Override
    public MessageSession getSession(String sessionId) {
        String[] sessionParam = sessionId.split("/", 2);
        return switch (sessionParam[0]) {
            case "private" -> {
                OnebotSession private_session = new OnebotSession() {
                    @Override
                    public void sendMessage(String message) {
                        sendPrivateMessage(sessionParam[1], message);
                    }
                };
                yield private_session;
            }
            case "group" -> {
                OnebotSession group_session = new OnebotSession() {
                    @Override
                    public void sendMessage(String message) {
                        sendGroupMessage(sessionParam[1], message);
                    }
                };
                yield group_session;
            }
            default -> null;
        };
    }

    private void sendGroupMessage(String id, String message) {
        SendGroupMessage spm = new SendGroupMessage();
        spm.user_id = id;
        spm.message = message;

        sendRequest(spm);
    }

    private void sendPrivateMessage(String id, String message) {
        SendPrivateMessage spm = new SendPrivateMessage();
        spm.user_id = id;
        spm.message = message;

        sendRequest(spm);
    }

    @Override
    public void load() {
        Map<String, String> header = new HashMap<>();
        header.put("Authorization", "Bearer " + token);
        wsConnection = new WebSocketClient(uri, header) {
            @Override
            public void onOpen(ServerHandshake serverHandshake) {
                logger.info("{} 连接成功", ID);
                falls = 0;
            }

            @Override
            public void onMessage(String s) {

            }

            @Override
            public void onClose(int i, String s, boolean b) {

            }

            @Override
            public void onError(Exception e) {
                logger.error(e.getMessage());
                falls ++;
                if (falls <= 10) {
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                    reload();
                }
            }
        };
        wsConnection.connect();
        Universe.MessageChannels.put(this.ID, this);
    }

    @Override
    public void stop() {
        isReady = false;
        wsConnection.close();
    }

    @Override
    public void sendMessage(String message, String sessionId) {

    }

    private void sendRequest(OBRequest request) {
        wsConnection.send(new Gson().toJson(request));
    }

    private void reload() {
        load();
        logger.info("{} 已重载", ID);
    }
}
package com.nekoyu.MessageChannel.Adapter.Onebot11;

import com.nekoyu.MessageChannel.ChatBot;
import com.nekoyu.MessageHandler;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

public class Onebot11 extends ChatBot {
    private boolean enable = true;
    //    private static String BASE_URL;
    private final String AUTH_KEY;
    private final String QQ_ID;
    private final String TARGET_GROUP_ID;

    private WebSocketClient webSocketClient;
    private int GroupNameChangeCooldownTime = 60;
    private long lastExecution = 0;
    private String GroupName;
    private Map<Integer, String> syncIndex = new HashMap<>();
    private int syncNum = 1;
    private URI uri;


    public Onebot11 (Map chatBotProperties, MessageHandler messageHandler){
        String[] address = chatBotProperties.get("Address").toString().split(":", 2);
        this.AUTH_KEY = chatBotProperties.get("AuthKey").toString();
        this.QQ_ID = chatBotProperties.get("QQID").toString();
        this.TARGET_GROUP_ID = chatBotProperties.get("TargetGroup").toString();
        try {
            URI uri = new URI("ws://" + address);
            this.uri = uri;
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
        String GroupNameChangeCooldown = (String) chatBotProperties.get("GroupNameChangeCooldown");
        if (GroupNameChangeCooldown != null) this.GroupNameChangeCooldownTime = Integer.parseInt(GroupNameChangeCooldown);
        newWebSocketClient();
    }

    public boolean newWebSocketClient(){
        if (enable) {
            Map headers = new HashMap<>();
            headers.put("Authorization", AUTH_KEY);
            WebSocketClient wsConn = new WebSocketClient(uri, headers) {
                @Override
                public void onOpen(ServerHandshake serverHandshake) {

                }

                @Override
                public void onMessage(String s) {

                }

                @Override
                public void onClose(int i, String s, boolean b) {

                }

                @Override
                public void onError(Exception e) {

                }
            };
            wsConn.connect();
            webSocketClient = wsConn;
            return true;
        } else {
            return false;
        }
    }

    public void disable() {
        enable = false;
        webSocketClient.close();
    }

    @Override
    public void sendMessage(String msg) {

    }

    @Override
    public void sendMessage(String msg, String GroupID) {

    }
}

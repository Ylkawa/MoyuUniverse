package com.nekoyu.MessageChannel.Adapter.Mirai;

import com.google.gson.*;
import com.nekoyu.ChatBot;
import com.nekoyu.MessageHandler;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

/**
 * Mirai适配器
 */
public class Mirai extends ChatBot {
    private boolean enable = true;
//    private static String BASE_URL;
    private final String HOST;
    private final int PORT;
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

    private final MessageHandler messageHandler;

    public Mirai(Map chatBotProperties, MessageHandler messageHandler){
        this.messageHandler = messageHandler;
//        this.BASE_URL = BASE_URL;
        String[] address = chatBotProperties.get("Address").toString().split(":", 2);
        this.HOST = address[0];
        this.PORT = Integer.parseInt(address[1]);
        this.AUTH_KEY = chatBotProperties.get("AuthKey").toString();
        this.QQ_ID = chatBotProperties.get("QQID").toString();
        this.TARGET_GROUP_ID = chatBotProperties.get("TargetGroup").toString();
        try {
            URI uri = new URI("ws", null, HOST, PORT, "/all", "verifyKey="+AUTH_KEY+"&qq="+QQ_ID, null);
            this.uri = uri;
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
        String GroupNameChangeCooldown = (String) chatBotProperties.get("GroupNameChangeCooldown");
        if (GroupNameChangeCooldown != null) this.GroupNameChangeCooldownTime = Integer.parseInt(GroupNameChangeCooldown);
        newWebSocketClient();
    }

    private void newWebSocketClient() {
        try {
            webSocketClient = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake serverHandshake) {
                    System.out.println("WebSocket opened");
                    getGroupName();
                }

                @Override
                public void onMessage(String s) {
                    System.out.println(s);
                    onMessageReceived(s);
                }

                @Override
                public void onClose(int i, String s, boolean b) {

                }

                @Override
                public void onError(Exception e) {
                    System.out.println(e.getMessage());
                }
            };
            webSocketClient.connect();
        } catch (URISyntaxException e) {
            System.out.println(e.getMessage());
            enable = false;
        }
    }

    @Override
    public void sendMessage(String msg) { //发给默认群聊
        sendMessage(msg, TARGET_GROUP_ID);
    }

    @Override
    public void sendMessage(String msg, String GroupID) { //发给指定QQ群
        if (enable && webSocketClient.isOpen()) {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("syncId", "0");
            request.put("command", "sendGroupMessage");

            Map<String, Object> content = new LinkedHashMap<>();
            content.put("target", GroupID);

            List<Map<String, Object>> messageChain = new ArrayList<>();
            Map<String, Object> plainText = new LinkedHashMap<>();
            plainText.put("type", "Plain");
            plainText.put("text", msg);
            messageChain.add(plainText);

            content.put("messageChain", messageChain);
            request.put("content", content);

            String jsonMessage = new Gson().toJson(request);
            System.out.println(jsonMessage); // 输出生成的JSON以进行调试
            webSocketClient.send(jsonMessage);
        } else if (enable && webSocketClient.isClosed()) {
            newWebSocketClient();
            sendMessage(msg, GroupID); // 修复方法调用中的缺失参数
        }
    }

    public void changeGroupNameIfNotMatch(String name) {
        if (!GroupName.equals(name)) {
            safelyChangeGroupName(name);
        }
    }

    public void safelyChangeGroupName(String name) {
        if (lastExecution + GroupNameChangeCooldownTime <= System.currentTimeMillis()) changeGroupName(name);
    }

    public void changeGroupName(String name) {
        GroupName = name;

        Map<String, Object> request = new HashMap<>();
        request.put("syncId", System.currentTimeMillis());
        request.put("command", "groupConfig");
        request.put("subCommand", "update");

        Map<String, Object> content = new HashMap<>();
        content.put("target", TARGET_GROUP_ID);
        request.put("content", content);

        Map<String, Object> config = new HashMap<>();
        config.put("name", name);
        content.put("config", config);

        System.out.println(new Gson().toJson(request));

        webSocketClient.send(new Gson().toJson(request));
    }

    public void getGroupName() {
        Map<String, Object> request = new HashMap<>();
        int syncId = syncNum;
        syncNum++;
        request.put("syncId", syncId);
        request.put("command", "groupConfig");
        request.put("subCommand", "get");

        Map<String, Object> content = new HashMap<>();
        content.put("target", TARGET_GROUP_ID);
        request.put("content", content);

        syncIndex.put(syncId, "GetGroupConfig");

        webSocketClient.send(new Gson().toJson(request));
    }

    private void onMessageReceived(String s) {
        MiraiPush push = new Gson().fromJson(s, MiraiPush.class);
        JsonObject data = push.getData();
        String syncId = push.getSyncId();
        switch (syncId) {
            case "-1" -> {
                switch (data.get("type").getAsString()) {
                    case "GroupMessage" -> {
                        messageHandler.onGroupMessageReceived(data);
                    }
                }
            }
            case "" -> {
                //byd什么时候会为空白啊
            }
            case "0" -> {
                //syncId为0
            }
            default -> {
                switch (syncIndex.get(Integer.parseInt(syncId))) {
                    case "GetGroupConfig" -> {
                        GroupConfig groupConfig = new Gson().fromJson(data, GroupConfig.class);
                        GroupName = groupConfig.getName();
                    }
                }
            }
        }
    }

    public void close() {
        enable = false;
        webSocketClient.close();
    }

    public boolean isDisconnected() {
        return webSocketClient.isClosed();
    }

    public void reconnect() {
        newWebSocketClient();
    }
}
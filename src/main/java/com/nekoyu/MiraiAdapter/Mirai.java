package com.nekoyu.MiraiAdapter;

import com.google.gson.*;
import com.nekoyu.ChatBot;
import com.nekoyu.MessageHandler;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.lang.reflect.Type;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

public class Mirai extends ChatBot {
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
    private List<String> syncIndex = new ArrayList<>();
    private int syncNum = 1;

    private MessageHandler messageHandler;

    private Gson Gson = BuildGson();

    private Gson BuildGson() {
        GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.registerTypeAdapter(Object.class, new JsonDeserializer<Object>() {
            @Override
            public Object deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
                if (json.isJsonPrimitive()) {
                    JsonPrimitive primitive = json.getAsJsonPrimitive();
                    if (primitive.isNumber()) {
                        return primitive.getAsString();
                    }
                }
                return json;
            }
        });

        return gsonBuilder.create();
    }

    public Mirai(Map chatBotProperties, MessageHandler messageHandler){
        this.messageHandler = messageHandler;
//        this.BASE_URL = BASE_URL;
        String[] address = chatBotProperties.get("Address").toString().split(":", 2);
        this.HOST = address[0];
        this.PORT = Integer.parseInt(address[1]);
        this.AUTH_KEY = chatBotProperties.get("AuthKey").toString();
        this.QQ_ID = chatBotProperties.get("QQID").toString();
        this.TARGET_GROUP_ID = chatBotProperties.get("TargetGroup").toString();
        String GroupNameChangeCooldown = (String) chatBotProperties.get("GroupNameChangeCooldown");
        if (GroupNameChangeCooldown != null) this.GroupNameChangeCooldownTime = Integer.parseInt(GroupNameChangeCooldown);
        Connect(); // 立即连接
    }

    public void Connect() {
        try {
            URI uri = new URI("ws", null, HOST, PORT, "/all", "verifyKey="+AUTH_KEY+"&qq="+QQ_ID, null);
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
            e.getCause().printStackTrace();
        }
    }

    @Override
    public void sendMessage(String msg) {
        Map<String, Object> request = new HashMap<>();
        request.put("syncId", System.currentTimeMillis());

        Map<String, Object> data = new HashMap<>();
        data.put("target", TARGET_GROUP_ID);

        // Create the messageChain map outside the anonymous class
        Map<String, String> plainText = new HashMap<>();
        plainText.put("type", "Plain");
        plainText.put("text", msg);

        // Wrap it in an array as you were doing before
        data.put("messageChain", new Object[]{plainText});

        request.put("command", "sendGroupMessage");
        request.put("content", data);

        String jsonMessage = new Gson().toJson(request);
        webSocketClient.send(jsonMessage);
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

        syncIndex.add(syncId, "GetGroupConfig");

        webSocketClient.send(new Gson().toJson(request));
    }

    private void onMessageReceived(String s) {
        MiraiPush push = new Gson().fromJson(s, MiraiPush.class);
        JsonObject data = push.getData();
        String syncId = push.getSyncId();
        if (syncId.equals("-1")) {
            switch (data.get("type").getAsString()) {
                case "GroupMessage" -> {
                    GroupMessage gm = new Gson().fromJson(data, GroupMessage.class);
                    String Message = gm.getTextMessage();
                    if (Message != null)
                        System.out.println(gm.getGroupName() + "(" + gm.getGroupID() + ")" + gm.getSenderName() + "(" + gm.getSenderID() + ") : " + Message);
                }
            }
        } else if(Objects.equals(syncId, "")) {
            //byd什么时候会为空白啊
        } else {
            switch (syncIndex.get(Integer.parseInt(syncId))) {
                case "GetGroupConfig" -> {
                    GroupConfig groupConfig = new Gson().fromJson(data, GroupConfig.class);
                    GroupName = groupConfig.getName();
                }
            }
        }
    }

    public void close() {
        webSocketClient.close();
    }
}
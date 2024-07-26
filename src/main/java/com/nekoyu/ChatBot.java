package com.nekoyu;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.lang.reflect.Type;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

public class ChatBot {
//    private static String BASE_URL;
    private final String HOST;
    private final int PORT;
    private final String AUTH_KEY;
    private final String QQ_ID;
    private final String TARGET_GROUP_ID;

    private WebSocketClient webSocketClient;
    private int GroupNameChangeCooldownTime = 10;
    private String GroupName;
    private List<String> syncIndex = new ArrayList<>();
    private int syncNum = 0;

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

    public ChatBot(Map chatBotProperties, MessageHandler messageHandler){
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
                    System.out.println("ChatBot Received message: " + s);
                    Map msg = processMap(new GsonBuilder().create().fromJson(s, new TypeToken<Map<String, Object>>(){}.getType()));
                    System.out.println(msg);

                    String syncId = (String)  msg.get("syncId");
                    int sync = -2;
                    if (!syncId.equals("")) sync = Integer.parseInt(syncId);
                    Map data = (Map)  msg.get("data");
                    if (sync == -1) {
                        switch ((String) data.get("type")) {
//                            case "FriendMessage": {
//                                List<Map<String, Object>> messageChain = (List<Map<String, Object>>) data.get("messageChain");
//                                String message = null;
//                                for (Map<String, Object> map : messageChain) {
//                                    if (map.get("type").toString().equals("Plain"))
//                                        message = map.get("text").toString();
//                                }
//                                break;
//                            }
                            case "GroupMessage": {
                                List<Map<String, Object>> messageChain = (List<Map<String, Object>>) data.get("messageChain");
                                String message = null;
                                for (Map<String, Object> map : messageChain) {
                                    if (map.get("type").toString().equals("Plain")) message = map.get("text").toString();
                                }
                                Map<String, Object> sender = (Map<String, Object>) data.get("sender");
                                System.out.println(sender);
                                if (message != null) messageHandler.onGroupMessageReceived((Long) sender.get("id"), (String) sender.get("memberName"), message);
                                break;
                            }

                        }
                    } else if(sync == -2) {

                    }
                    else {
                        switch (syncIndex.get(sync)){
                            case "GetGroupConfig":
                                GroupName = data.get("name").toString();
                                break;
                        }
                    }
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
            changeGroupName(name);
        }
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

    private Map<String, Object> processMap(Map<String, Object> map) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (entry.getValue() instanceof Double) {
                Double doubleValue = (Double) entry.getValue();
                if (doubleValue % 1 == 0) {
                    entry.setValue(doubleValue.longValue());
                }
            } else if (entry.getValue() instanceof Map) {
                entry.setValue(processMap((Map<String, Object>) entry.getValue()));
            } else if (entry.getValue() instanceof Iterable) {
                for (Object element : (Iterable<?>) entry.getValue()) {
                    if (element instanceof Map) {
                        processMap((Map<String, Object>) element);
                    }
                }
            }
        }
        return map;
    }
}
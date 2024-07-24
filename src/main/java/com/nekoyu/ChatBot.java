package com.nekoyu;

import com.google.gson.Gson;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Date;
import java.util.Map;
import java.util.HashMap;

public class ChatBot {
//    private static String BASE_URL;
    private final String HOST;
    private final int PORT;
    private final String AUTH_KEY;
    private final String QQ_ID;
    private final String TARGET_GROUP_ID;

    private WebSocketClient webSocketClient;
    private int GroupNameChangeCooldownTime = 10;

    public ChatBot(Map chatBotProperties){
//        this.BASE_URL = BASE_URL;
        String[] address = chatBotProperties.get("Address").toString().split(":", 2);
        this.HOST = address[0];
        this.PORT = Integer.parseInt(address[1]);
        this.AUTH_KEY = chatBotProperties.get("AuthKey").toString();
        this.QQ_ID = chatBotProperties.get("QQID").toString();
        this.TARGET_GROUP_ID = chatBotProperties.get("TargetGroup").toString();
        String GroupNameChangeCooldown = (String) chatBotProperties.get("GroupNameChangeCooldown");
        if (GroupNameChangeCooldown != null) this.GroupNameChangeCooldownTime = Integer.parseInt(GroupNameChangeCooldown);
    }

    public void Connect() {
        try {
            URI uri = new URI("ws", null, HOST, PORT, "/all", "verifyKey="+AUTH_KEY+"&qq="+QQ_ID, null);
            webSocketClient = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake serverHandshake) {
                    System.out.println("WebSocket opened");
                }

                @Override
                public void onMessage(String s) {
                    System.out.println("ChatBot Received message: " + s);
                    Map data = (Map) new Gson().fromJson(s, HashMap.class).get("data");
                    switch ((String) data.get("type")){
                        case "FriendMessage":
                            
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
        Map<String, Object> message = new HashMap<>();
        message.put("syncId", System.currentTimeMillis());

        Map<String, Object> data = new HashMap<>();
        data.put("target", TARGET_GROUP_ID);

        // Create the messageChain map outside the anonymous class
        Map<String, String> plainText = new HashMap<>();
        plainText.put("type", "Plain");
        plainText.put("text", msg);

        // Wrap it in an array as you were doing before
        data.put("messageChain", new Object[]{plainText});

        message.put("command", "sendGroupMessage");
        message.put("content", data);

        String jsonMessage = new Gson().toJson(message);
        webSocketClient.send(jsonMessage);
    }

    public void changeGroupName(String name) {
        Map<String, Object> request = new HashMap<>();
        request.put("target", TARGET_GROUP_ID);

        Map<String, Object> config = new HashMap<>();
        config.put("name", name);
        request.put("config", config);

        webSocketClient.send(new Gson().toJson(request));
    }
}
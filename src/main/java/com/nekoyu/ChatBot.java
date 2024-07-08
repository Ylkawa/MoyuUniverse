package com.nekoyu;

import com.google.gson.Gson;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.HashMap;

public class ChatBot {
//    private static String BASE_URL;
    private final String HOST;
    private final int PORT;
    private final String AUTH_KEY;
    private final String QQ_ID;
    private final String TARGET_GROUP_ID;

    private String sessionKey;
    private WebSocketClient webSocketClient;

    public ChatBot(String HOST, int PORT, String AUTH_KEY, String QQ_ID, String TARGET_GROUP_ID){
//        this.BASE_URL = BASE_URL;
        this.HOST = HOST;
        this.PORT = PORT;
        this.AUTH_KEY = AUTH_KEY;
        this.QQ_ID = QQ_ID;
        this.TARGET_GROUP_ID = TARGET_GROUP_ID;
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
                    System.out.println("Received message: " + s);
                    if (s.contains("\"code\":0") && s.contains("\"session\"")) {
                        System.out.println(s);
                        sessionKey = s.split("\"session\":\"")[1].split("\"")[0];
                        webSocketClient.send("{\"syncId\": 2, \"command\": \"verify\", \"content\": {\"sessionKey\": \"" + sessionKey + "\", \"qq\": " + QQ_ID + "}}");
                    }
                }

                @Override
                public void onClose(int i, String s, boolean b) {

                }

                @Override
                public void onError(Exception e) {

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
        System.out.println(jsonMessage);
        webSocketClient.send(jsonMessage);
    }
}
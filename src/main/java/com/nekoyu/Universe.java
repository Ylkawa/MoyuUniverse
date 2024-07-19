package com.nekoyu;

import com.google.gson.Gson;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class Universe {
    public static void main(String[] args) {

        ChatBot chatBot = new ChatBot("139.196.112.23",39393, "INITKEY6LWqTW2V", "1697775835", "455284589");
        chatBot.Connect();
        System.out.println("Hello world!");
        WebSocketServer wss = new WebSocketServer(new InetSocketAddress(24430)) {
            @Override
            public void onOpen(WebSocket webSocket, ClientHandshake clientHandshake) {
                System.out.println("Connection open: " + webSocket.getRemoteSocketAddress());
            }

            @Override
            public void onClose(WebSocket webSocket, int i, String s, boolean b) {
                System.out.println("Connection close: " + webSocket.getRemoteSocketAddress());
            }

            @Override
            public void onMessage(WebSocket webSocket, String s) {
                System.out.println(s);
                Map message = new Gson().fromJson(s, HashMap.class);
                String event = message.get("event").toString();
                switch (event) {
                    case "PlayerJoinEvent":
                        chatBot.sendMessage(message.get("PlayerName").toString() + " 加入了游戏.");
                        break;
                    case "PlayerQuitEvent":
                        chatBot.sendMessage(message.get("PlayerName").toString() + " 退出了游戏.");
                        break;
                }
            }

            @Override
            public void onError(WebSocket webSocket, Exception e) {}

            @Override
            public void onStart() {
                System.out.println("WebSocket Server is on start.");
            }
        };
        wss.start();

        //程序退出动作
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                Collection<WebSocket> collection= wss.getConnections();
                for (WebSocket conn : collection) {
                    conn.close();
                }
                System.out.println("Exiting...");
            }
        }));
    }
}
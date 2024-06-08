package com.nekoyu;

import com.google.gson.Gson;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.ArrayList;

public class Main {
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
                System.out.println("Receive message. " + webSocket.getRemoteSocketAddress() + ": " + s);
                ArrayList<String> message = new Gson().fromJson(s, ArrayList.class);
                switch (message.get(0)) {
                    case "PlayerLoginEvent":
                        System.out.println("Receive message. " + webSocket.getRemoteSocketAddress() + ": " + s);
                        chatBot.sendMessage(message.get(1) + " 加入了游戏.");
                }
            }

            @Override
            public void onError(WebSocket webSocket, Exception e) {
                e.getCause().printStackTrace();
            }

            @Override
            public void onStart() {
                System.out.println("WebSocket Server is on start.");
            }
        };
        wss.start();

        //程序退出动作
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                System.out.println("Exiting...");
            }
        }));
    }
}
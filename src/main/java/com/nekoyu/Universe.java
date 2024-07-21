package com.nekoyu;

import com.google.gson.Gson;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

public class Universe {
    static Map<String, Object> properties = null;

    public static void main(String[] args) {
        loadProperties("config.yml");

        ChatBot chatBot = new ChatBot((Map) properties.get("ChatBot"));
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
                Map<String, Object> message = new Gson().fromJson(s, HashMap.class);
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

    public static boolean loadProperties(String path) {
        try {
            properties = new Yaml().loadAs(new FileReader(path), HashMap.class);
            Map<String, Object> chatBot = (HashMap) properties.get("ChatBot");
            if (chatBot != null) {
                if ((boolean) chatBot.get("enable")) {
                    if (chatBot.get("Address") == null) {
                        Scanner sc = new Scanner(System.in);
                        System.out.println("请输入ChatBot的地址");
                        chatBot.put("Address", sc.next());
                    }
                    if (chatBot.get("AuthKey") == null) {
                        Scanner sc = new Scanner(System.in);
                        System.out.println("请输入ChatBot的AuthKey");
                        chatBot.put("AuthKey", sc.next());
                    }
                    if (chatBot.get("QQID") == null) {
                        Scanner sc = new Scanner(System.in);
                        System.out.println("请输入ChatBot的QQ号");
                        chatBot.put("QQID", sc.next());
                    }
                    if (chatBot.get("TargetGroup") == null) {
                        Scanner sc = new Scanner(System.in);
                        System.out.println("请输入ChatBot监听的群聊");
                        chatBot.put("TargetGroup", sc.next());
                    }
                    saveProperties();
                }
            } else {
                chatBot = new HashMap<>();
                chatBot.put("enable", "True");
                properties.put("ChatBot", chatBot);
                saveProperties();
            }
            return true;
        } catch (FileNotFoundException e) {
            System.out.println("配置文件加载失败: FileNotFound");
            return false;
        }
    }

    public static boolean saveProperties(){
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml yaml = new Yaml(options);
        try (FileWriter writer = new FileWriter("config.yml")) {
            yaml.dump(properties, writer);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }
}
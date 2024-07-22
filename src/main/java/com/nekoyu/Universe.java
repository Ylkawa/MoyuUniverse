package com.nekoyu;

import com.google.gson.Gson;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.InetSocketAddress;
import java.util.*;

public class Universe {
    static Map<String, Object> properties = null;

    public static void main(String[] args) {
        if (args.length !=0) {
            try (FileReader fr = new FileReader(args[0])) { //尝试加载用户指定的配置文件
                loadProperties(fr);
            } catch (FileNotFoundException e) { //用户指定的配置文件不存在，直接退出
                System.out.println("Config file not found.");
                System.exit(1);
            } catch (IOException e) { //我不到啊
                throw new RuntimeException(e);
            }
        } else {
            try { //尝试加载默认配置文件
                loadProperties();
            } catch (FileNotFoundException e) {
                try {
                    if ((new File("config.yml").createNewFile())) { //尝试创建配置文件
                        loadProperties(new FileReader("config.yml"));
                    } else {
                        System.out.println("Failed to create config file!");
                        System.exit(1);
                    }
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }

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

    public static void loadProperties() throws FileNotFoundException {
        loadProperties(new FileReader("config.yml"));
    }

    public static void loadProperties(FileReader fr) {
        properties = new Yaml().loadAs(fr, HashMap.class);
        if (properties == null) {
            properties = new HashMap<>();
        }
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
                try (FileWriter fw = new FileWriter("config.yml")) {
                    saveProperties(fw);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        } else {
            chatBot = new HashMap<>();
            chatBot.put("enable", "True");
            properties.put("ChatBot", chatBot);
            try (FileWriter fw = new FileWriter("config.yml")) {
                saveProperties(fw);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static void saveProperties(FileWriter fw){
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml yaml = new Yaml(options);
        yaml.dump(properties, fw);
    }
}
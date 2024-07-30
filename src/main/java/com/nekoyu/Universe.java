package com.nekoyu;

import com.google.gson.*;
import com.nekoyu.MiraiAdapter.Mirai;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.util.*;

public class Universe {
    static WebSocketServer wss = null;
    static ConfigureProcessor config = null;
    static List<WebSocket> comets = new ArrayList<>();

    private static MessageHandler messageHandler = new MessageHandler() {
        @Override
        public void onGroupMessageReceived(String GroupName, long GroupID, long QQID, String QQName, String message) {
            for (WebSocket ws : comets) {
                ReceiveGroupMessageEvent receiveGroupMessageEvent = new ReceiveGroupMessageEvent();
                receiveGroupMessageEvent.setGroupName(GroupName);
                receiveGroupMessageEvent.setGroupID(GroupID);
                receiveGroupMessageEvent.setQQID(QQID);
                receiveGroupMessageEvent.setQQName(QQName);
                receiveGroupMessageEvent.setMessage(message);

                Gson gson = new Gson();
                Event event = new Event();
                event.setType("GroupMessageReceived");
                event.setBody(gson.toJsonTree(receiveGroupMessageEvent));
                ws.send(gson.toJson(event));
            }
        }
    };

    public static void main(String[] args)  {
        if (args.length !=0) {
            for (int i = 0; i < args.length; i++){
                switch (args[i].split("-", 2)[1]){
                    case "config":
                        i++;
                        if (i < args.length) {
                            System.out.println("指令语法有误");
                            System.exit(1);
                        }
                        config = new ConfigureProcessor(new File(args[i]));
                }
            }
        } else {
            config = new ConfigureProcessor(new File("config.yml"));
        }

        assert config != null; //我说不是null就不是
        if (!config.readAsYaml()) System.exit(2); //加载配置文件并处理异常 错误码2: 配置文件读不到

        Mirai mirai = new Mirai((Map) config.getNode("ChatBot"), messageHandler);
        System.out.println("Hello world!");
        wss = new WebSocketServer(new InetSocketAddress(24430)) {
            @Override
            public void onOpen(WebSocket webSocket, ClientHandshake clientHandshake) {
                System.out.println("Connection open: " + webSocket.getRemoteSocketAddress());
            }

            @Override
            public void onClose(WebSocket webSocket, int i, String s, boolean b) {
                if (comets.remove(webSocket)) System.out.println("Comet offline: " + webSocket.getRemoteSocketAddress());
                System.out.println("Connection close: " + webSocket.getRemoteSocketAddress());
            }

            @Override
            public void onMessage(WebSocket webSocket, String s) {
                System.out.println(s);
                Event event = new Gson().fromJson(s, Event.class);
                switch (event.getType()) {
                    case "PlayerJoinEvent" -> {
                        PlayerJoinEvent playerJoinEvent = new Gson().fromJson(event.getBody(), PlayerJoinEvent.class);
                        String result = playerJoinEvent.getPlayerName() + " 进入了服务器.";
                        mirai.sendMessage(result);
                    }
                    case "PlayerLeaveEvent" -> {
                        PlayerJoinEvent playerJoinEvent = new Gson().fromJson(event.getBody(), PlayerJoinEvent.class);
                        String result = playerJoinEvent.getPlayerName() + " 退出了服务器.";
                        mirai.sendMessage(result);
                    }
                    case "StarCloudStatusUpload" -> {
                        StarCloudStatus starCloudStatus = new Gson().fromJson(event.getBody(), StarCloudStatus.class);
                        String template = "末屿ZZZ | %NOP% 人在线";
                        String result = template.replaceAll("%NOP%", String.valueOf(starCloudStatus.getNumOfPlayer()));
                        mirai.changeGroupNameIfNotMatch(result);
                    }
                    case "RegisterComet" -> {
                        comets.add(webSocket);
                        webSocket.send("{\"event\": \"Register Complete\"}");
                    }
                    case "SendGroupMessage" -> {
                        SendGroupMessage sendGroupMessage = new Gson().fromJson(event.getBody(), SendGroupMessage.class);
                        mirai.sendMessage(sendGroupMessage.getMessage(), sendGroupMessage.getTarget());
                    }
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
                mirai.close();
                for (WebSocket conn : collection) {
                    conn.close();
                }
                System.out.println("Exiting...");
            }
        }));
    }


//    public static void loadProperties() throws FileNotFoundException {
//        loadProperties(new FileReader("config.yml"));
//    }
//
//    public static void loadProperties(FileReader fr) {
//        properties = new Yaml().loadAs(fr, HashMap.class);
//        Map<String, Object> chatBot = (HashMap) properties.get("ChatBot");
//        if (chatBot != null) {
//            if ((boolean) chatBot.get("enable")) {
//                if (chatBot.get("Address") == null) {
//                    Scanner sc = new Scanner(System.in);
//                    System.out.println("请输入ChatBot的地址");
//                    chatBot.put("Address", sc.next());
//                }
//                if (chatBot.get("AuthKey") == null) {
//                    Scanner sc = new Scanner(System.in);
//                    System.out.println("请输入ChatBot的AuthKey");
//                    chatBot.put("AuthKey", sc.next());
//                }
//                if (chatBot.get("QQID") == null) {
//                    Scanner sc = new Scanner(System.in);
//                    System.out.println("请输入ChatBot的QQ号");
//                    chatBot.put("QQID", sc.next());
//                }
//                if (chatBot.get("TargetGroup") == null) {
//                    Scanner sc = new Scanner(System.in);
//                    System.out.println("请输入ChatBot监听的群聊");
//                    chatBot.put("TargetGroup", sc.next());
//                }
//                try (FileWriter fw = new FileWriter("config.yml")) {
//                    saveProperties(fw);
//                } catch (IOException e) {
//                    throw new RuntimeException(e);
//                }
//            }
//        } else {
//            chatBot = new HashMap<>();
//            chatBot.put("enable", "True");
//            properties.put("ChatBot", chatBot);
//            try (FileWriter fw = new FileWriter("config.yml")) {
//                saveProperties(fw);
//            } catch (IOException e) {
//                throw new RuntimeException(e);
//            }
//        }
//    }
//
//    public static void saveProperties(FileWriter fw){
//        DumperOptions options = new DumperOptions();
//        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
//        Yaml yaml = new Yaml(options);
//        yaml.dump(properties, fw);
//    }
//
//    public static void qwer(){
//        String yaml = "name: John\n" +
//                "age: \"30\"\n" +
//                "address: \n" +
//                "  street: 123 Main St\n" +
//                "  city: New York\n" +
//                "  postalCode: \"10001\"\n" +
//                "  contact:\n" +
//                "    phone: 555-1234\n" +
//                "    email: john@example.com\n" +
//                "occupation: Software Engineer\n" +
//                "hobbies: \n" +
//                "  indoor: \n" +
//                "    - reading\n" +
//                "    - coding\n" +
//                "  outdoor: \n" +
//                "    - hiking\n" +
//                "    - cycling\n";
//        Map<String, Object> map = new Yaml().loadAs(yaml, HashMap.class);
//
//        // 遍历Map
//        for (Map.Entry<String, Object> entry : map.entrySet()) {
//            String key = entry.getKey();
//            Object value = entry.getValue();
//
//            if (value instanceof Map) {
//                // 处理子Map
//                Map<String, Object> subMapValue = (Map<String, Object>) value;
//                System.out.println("Key: " + key + " contains a subMap: " + subMapValue);
//                // 你可以递归处理子Map
//                traverseSubMap(subMapValue);
//            } else if (value instanceof String) {
//                // 处理String
//                String stringValue = (String) value;
//                System.out.println("Key: " + key + " contains a String: " + stringValue);
//            } else {
//                // 处理其他类型（如果有）
//                System.out.println("Key: " + key + " contains an unknown type: " + value.getClass().getName());
//            }
//        }
//        System.exit(1234);
//    }
//
//    public static void traverseSubMap(Map<String, Object> subMap) {
//        for (Map.Entry<String, Object> entry : subMap.entrySet()) {
//            String key = entry.getKey();
//            Object value = entry.getValue();
//
//            if (value instanceof Map) {
//                Map<String, Object> subSubMap = (Map<String, Object>) value;
//                System.out.println("SubKey: " + key + " contains a subMap: " + subSubMap);
//                traverseSubMap(subSubMap);
//            } else if (value instanceof String) {
//                String stringValue = (String) value;
//                System.out.println("SubKey: " + key + " contains a String: " + stringValue);
//            } else {
//                System.out.println("SubKey: " + key + " contains an unknown type: " + value.getClass().getName());
//            }
//        }
//    }

}
package com.nekoyu.Universe.API;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

public class UniverseChannel {
    private WebSocketServer wsServer;
    private int wsPort;
    private int httpPort;
    private final Multimap<String, UniverseListener> internalListeners = ArrayListMultimap.create();
    private final Multimap<String, String> externalListeners = ArrayListMultimap.create();
    private final Multimap<String, WebSocket> clientGroup = ArrayListMultimap.create();
    private String token = null;
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Map<String, WebSocket> clientList = new HashMap<>();
    private HttpServer httpServer = null;
    private final Map<String, File> fileMounting = new HashMap<>();

    public void setToken(String token) {
        this.token = token;
    }

    public void setWsPort(int port) {
        this.wsPort = port;
    }

    public void load() {
        registerListener("Universe", (planet, message, args, rawJson) -> {
            switch (message) {
                case "RegisterListener":
                    List<String> tag = (ArrayList<String>) args.get("Tag");
                    if (tag == null) return;
                    for (var t : tag) externalListeners.put(t, planet.getID());
                    logger.info("{} 注册了远程消息监听 {}", planet.getID(), tag);
            }
        });
        wsServer = new WebSocketServer(new InetSocketAddress(wsPort)) {
            @Override
            public void onOpen(WebSocket webSocket, ClientHandshake clientHandshake) {
                if (token != null) {
                    if (!Objects.equals(clientHandshake.getFieldValue("Token"), token)) {
                        webSocket.close();
                        logger.info("拒绝了来自 {} 的连接，因为口令校验不通过", webSocket.getRemoteSocketAddress());
                        return;
                    }
                    clientList.put(clientHandshake.getFieldValue("ID"), webSocket);
                    clientGroup.put(clientHandshake.getFieldValue("Type"), webSocket);
                    Map<String, String> attachment = new HashMap<>();
                    attachment.put("ID", clientHandshake.getFieldValue("ID"));
                    attachment.put("Type", clientHandshake.getFieldValue("Type"));
                    webSocket.setAttachment(attachment);
                    logger.info("{} ({}-{}) 通过口令校验并创建了连接", webSocket.getRemoteSocketAddress(), clientHandshake.getFieldValue("Type"), clientHandshake.getFieldValue("ID"));
                }
            }

            @Override
            public void onClose(WebSocket conn, int code, String reason, boolean remote) {
                externalListeners.entries().removeIf(entry -> entry.getValue().equals(((HashMap)conn.getAttachment()).get("ID").toString()));
                logger.info("{} 断开了连接 {}: {}", conn.getRemoteSocketAddress(), code, reason);
            }

            @Override
            public void onMessage(WebSocket webSocket, String rawContent) {
                try {
                    var sender = new Planet();
                    sender.ID = (String) ((HashMap) webSocket.getAttachment()).get("ID");
                    sender.Type = (String) ((HashMap) webSocket.getAttachment()).get("Type");
                    sender.webSocket = webSocket;
                    sender.local = false;
                    UniverseChannelMessage ucm = new Gson().fromJson(rawContent, UniverseChannelMessage.class);
                    if (ucm.tag != null && ucm.args != null) {
                        for (UniverseListener listener : internalListeners.get(ucm.tag)) {
                            listener.onMessage(sender, ucm.message, ucm.args, rawContent);
                        }
                    } else {
                        logger.warn("{} 发送的消息不规范，不会被处理", sender.ID);
                    }
                } catch (JsonSyntaxException e) {
                    logger.error("{} 发送了一段不符合 JSON 规范的消息", webSocket.getRemoteSocketAddress());
                }
            }

            @Override
            public void onError(WebSocket webSocket, Exception e) {

            }

            @Override
            public void onStart() {
                logger.info("宇宙穿隧加载成功");
            }
        };
        wsServer.setReuseAddr(true);
        wsServer.start();

        try {
            httpServer = HttpServer.create(new InetSocketAddress(httpPort), 0);
            httpServer.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        httpServer.createContext("/", exchange -> {
            String response = "400 Bad Request";
            exchange.sendResponseHeaders(400, response.getBytes().length);
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
        });
        addFileMounting("MikuMikuMi", new File("./124679190_p0.jpg"));
        httpServer.createContext("/Universe/", exchange -> {
            String fn = exchange.getRequestURI().getPath().split("/", 3)[2];
            if (fn.isBlank()) {
                String response = """
                        400 Bad Request
                        Please serve file arg
                        
                        Miku the best
                        """;
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
                exchange.sendResponseHeaders(400, 0);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }
            if (!fileMounting.containsKey(fn)) {
                String response = """
                        400 Bad Request
                        Served file arg is invalid
                        
                        Miku Miku Mi チュ!
                        """;
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
                exchange.sendResponseHeaders(400, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes(StandardCharsets.UTF_8));
                }
                return;
            }
            File file = fileMounting.get(fn);
            if (!file.isFile()) {
                String response = """
                        500 Internal Server Error
                        No such file
                        """;
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
                exchange.sendResponseHeaders(500, 0);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes(StandardCharsets.UTF_8));
                }
            }
            exchange.sendResponseHeaders(200, file.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(Files.readAllBytes(file.toPath()));
            }
            logger.info("{} ==> {}", file.getAbsolutePath(), exchange.getRemoteAddress());
        });
    }

    public void addFileMounting(String mountPath, File file) {
        fileMounting.put(mountPath, file);
    }

    public void registerListener(String tag, UniverseListener universeListener) {
        internalListeners.put(tag, universeListener);
    }

    public void unRegisterListener(String tag, UniverseListener universeListener) {
        internalListeners.remove(tag, universeListener);
    }

    public void broadcast(String tag, UniverseChannelMessage ucm) {
        String json = new Gson().toJson(ucm);
        for (String id : externalListeners.get(tag)) {
            clientList.get(id).send(json);
        }
    }

    public Collection<WebSocket> listWebSocketConnections() {
        return wsServer.getConnections();
    }

    public void setHttpPort(int httpPort) {
        this.httpPort = httpPort;
    }

    public void addHttpHandler(String path, HttpHandler hh) {
        httpServer.createContext(path, hh);
    }

    public void removeHttpHandler(String path) {
        httpServer.removeContext(path);
    }
}
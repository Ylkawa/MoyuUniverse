package com.nekoyu.Universe.API;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class UniverseChannel {
    private static WebSocketServer wsServer;
    private static int wsPort;
    private static int httpPort;
    private static final Multimap<String, UniverseListener> internalListeners = ArrayListMultimap.create();
    private static final Multimap<String, UniverseListener> wildcardInternalListeners = ArrayListMultimap.create();
    private static final Multimap<String, String> externalListeners = ArrayListMultimap.create();
    private static final Multimap<String, String> wildcardExternalListeners = ArrayListMultimap.create();
    private static final Multimap<String, WebSocket> clientGroup = ArrayListMultimap.create();
    private static String token = null;
    private static final Logger logger = LoggerFactory.getLogger(UniverseChannel.class);
    private static final Map<String, WebSocket> clientList = new HashMap<>();
    private static HttpServer httpServer = null;
    private static final Map<String, File> fileMounting = new HashMap<>();
    private static final Map<String, CachedFile> remoteUrlCachedFile = new ConcurrentHashMap<>();
    private static final Map<String, CachedFile> repostUrlCachedFile = new ConcurrentHashMap<>();
    private static String outboundHttpAddress;

    public static void setToken(String token) {
        UniverseChannel.token = token;
    }

    public static void setWsPort(int port) {
        UniverseChannel.wsPort = port;
    }

    public static void load() {
        registerListener("Universe", (planet, message, args, rawJson) -> {
            switch (message) {
                case "RegisterListener":
                    List<String> tag = (ArrayList<String>) args.get("Tag");
                    if (tag == null) return;
                    for (var t : tag) {
                        if (isWildcardTag(t)) {
                            wildcardExternalListeners.put(t, planet.getID());
                        } else {
                            externalListeners.put(t, planet.getID());
                        }
                    }
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
                }
                String id = clientHandshake.getFieldValue("ID");
                String type = clientHandshake.getFieldValue("Type");
                if (id == null || type == null) logger.info("无法接受 {} 的连接，因为 ID 或者 Type 未指定", webSocket.getRemoteSocketAddress());
                clientList.put(id, webSocket);
                clientGroup.put(type, webSocket);
                Map<String, String> attachment = new HashMap<>();
                attachment.put("ID", id);
                attachment.put("Type", type);
                webSocket.setAttachment(attachment);
                logger.info("{} ({} - {}) 创建了连接", webSocket.getRemoteSocketAddress(), type, id);
            }

            @Override
            public void onClose(WebSocket conn, int code, String reason, boolean remote) {
                String id = ((HashMap) conn.getAttachment()).get("ID").toString();
                externalListeners.entries().removeIf(entry -> entry.getValue().equals(id));
                wildcardExternalListeners.entries().removeIf(entry -> entry.getValue().equals(id));
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
                        for (var entry : wildcardInternalListeners.entries()) {
                            if (matchesTag(entry.getKey(), ucm.tag)) {
                                entry.getValue().onMessage(sender, ucm.message, ucm.args, rawContent);
                            }
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
            String remoteAddress = exchange.getRemoteAddress().toString();
            if (exchange.getRequestHeaders().get("X-Forwarded-For") != null) { // 易受攻击点，不应将 Universe Http 端口暴露公网
                remoteAddress = exchange.getRequestHeaders().get("X-Forwarded-For").get(0);
            }
            logger.info("{} <== {}", remoteAddress, file.getAbsolutePath());
        });
    }

    /*
    将本地文件映射到 /Universe/ 下，返回值为映射到的公网访问地址
     */
    public static URL addFileMounting(String mountPath, File file) {
        fileMounting.put(mountPath, file);
        try {
            return new URL(outboundHttpAddress + "/Universe/" + mountPath);
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    public static void registerListener(String tag, UniverseListener universeListener) {
        if (isWildcardTag(tag)) {
            wildcardInternalListeners.put(tag, universeListener);
        } else {
            internalListeners.put(tag, universeListener);
        }
    }

    public static void unRegisterListener(String tag, UniverseListener universeListener) {
        if (isWildcardTag(tag)) {
            wildcardInternalListeners.remove(tag, universeListener);
        } else {
            internalListeners.remove(tag, universeListener);
        }
    }

    public static void broadcast(String tag, UniverseChannelMessage ucm) {
        String json = new Gson().toJson(ucm);
        Set<String> targets = new HashSet<>(externalListeners.get(tag));
        for (var entry : wildcardExternalListeners.entries()) {
            if (matchesTag(entry.getKey(), tag)) {
                targets.add(entry.getValue());
            }
        }
        for (String id : targets) {
            WebSocket ws = clientList.get(id);
            if (ws != null) {
                ws.send(json);
            }
        }
    }

    public static URL repostFile(URL url) throws IOException {
        CachedFile cf = remoteUrlCachedFile.get(url.toString());
        if (cf == null) {
            OkHttpClient client = new OkHttpClient();
            Request req = new Request.Builder().url(url.toString()).build();
            try (var resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful()) throw new IOException("Request failed with unexpected code " + resp);
                if (resp.body() == null) throw new IOException("Response body is null");
                var body = resp.body();
                String extension;
                if (body.contentType() != null) switch (body.contentType().toString()) {
                    case "application/json" -> extension = ".json";
                    case "image/jpeg" -> extension = ".jpeg";
                    default -> extension = "";
                }
                else extension = "";
                UUID uuid = UUID.randomUUID();
                cf = new CachedFile("./cache/reposts/" + uuid + extension);
                cf.outboundUrl = new URL(outboundHttpAddress + "/Universe/" + uuid + extension);
                cf.uuid = uuid.toString();
                cf.extension = extension;
                if (!cf.createNewFile()) throw new IOException("Could not create file");
                try (
                        InputStream in = body.byteStream();
                        FileOutputStream out = new FileOutputStream(cf)
                ) {
                    byte[] buffer = new byte[8192];
                    int len;

                    while ((len = in.read(buffer)) != -1) {
                        out.write(buffer, 0, len);
                    }
                    cf.outboundUrl = addFileMounting(uuid + extension, cf);
                }
            }
            remoteUrlCachedFile.put(url.toString(), cf);
            repostUrlCachedFile.put(cf.outboundUrl.toString(), cf);
        }
        cf.ref.incrementAndGet();
        return cf.outboundUrl;
    }

    public static void releaseRepost(URL url) {
        repostUrlCachedFile.get(url.toString()).release();
    }

    public static Collection<WebSocket> listWebSocketConnections() {
        return wsServer.getConnections();
    }

    public static void setHttpPort(int httpPort) {
        UniverseChannel.httpPort = httpPort;
    }

    public static void addHttpHandler(String path, HttpHandler hh) {
        httpServer.createContext(path, hh);
    }

    public static void removeHttpHandler(String path) {
        httpServer.removeContext(path);
    }

    public static void setOutboundHttpAddress(String addr) {
        UniverseChannel.outboundHttpAddress = addr;
    }

    private static boolean isWildcardTag(String tag) {
        return tag != null && tag.indexOf('*') >= 0;
    }

    private static boolean matchesTag(String pattern, String tag) {
        if (pattern == null || tag == null) return false;
        if (!isWildcardTag(pattern)) return pattern.equals(tag);
        if (pattern.equals("*")) return true;
        String[] parts = pattern.split("\\*", -1);
        int index = 0;
        boolean first = true;
        for (String part : parts) {
            if (part.isEmpty()) {
                first = false;
                continue;
            }
            int found = tag.indexOf(part, index);
            if (found < 0) return false;
            if (first && !pattern.startsWith("*") && found != 0) return false;
            index = found + part.length();
            first = false;
        }
        return pattern.endsWith("*") || index == tag.length();
    }

    public static class CachedFile extends File {
        AtomicInteger ref = new AtomicInteger(0);
        AtomicInteger released = new AtomicInteger(0);
        String extension;
        String uuid;
        URL outboundUrl;

        public void release() {
            released.incrementAndGet();
            if (released.get() >= ref.get()) {
                remoteUrlCachedFile.remove(outboundUrl.toString());
                repostUrlCachedFile.remove(outboundUrl.toString());
                fileMounting.remove(uuid + extension);
                if (delete()) logger.debug("已清理 {} 的缓存", outboundUrl);
            }
        }

        public CachedFile(@NonNull String pathname) {
            super(pathname);
        }
    }
}

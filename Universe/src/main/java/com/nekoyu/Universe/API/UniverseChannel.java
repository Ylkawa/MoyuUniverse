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
import java.util.concurrent.Executors;
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
    private static final Map<String, File> fileMounting = new ConcurrentHashMap<>();
    private static final Map<String, CachedFile> remoteUrlCachedFile = new ConcurrentHashMap<>();
    private static final Map<String, CachedFile> repostUrlCachedFile = new ConcurrentHashMap<>();

    private static String outboundHttpAddress;

    public static void setToken(String token) {
        UniverseChannel.token = token;
    }

    public static void setWsPort(int port) {
        UniverseChannel.wsPort = port;
    }

    public static String getOutboundHttpAddress() {
        return outboundHttpAddress;
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
            httpServer.setExecutor(Executors.newFixedThreadPool(
                    Math.max(4, Runtime.getRuntime().availableProcessors() * 2)
            ));
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
            File file = fileMounting.get(fn);
            if (file == null) {
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
        if (cf != null && cf.retain()) return cf.outboundUrl;
        // 已挂载的本地文件直接增加引用，避免从自身 HTTP 接口重复下载。
        cf = repostUrlCachedFile.get(url.toString());
        if (cf != null && cf.retain()) return cf.outboundUrl;
        {
            OkHttpClient client = new OkHttpClient();
            Request req = new Request.Builder().url(url.toString()).build();
            try (var resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful()) throw new IOException("Request failed with unexpected code " + resp);
                if (resp.body() == null) throw new IOException("Response body is null");
                var body = resp.body();
                var contentType = body.contentType();
                // type/subtype 不包含 charset 等参数，且 OkHttp 已将其转为小写。
                String mimeType = contentType == null ? "" : contentType.type() + "/" + contentType.subtype();
                String extension = switch (mimeType) {
                    case "application/json", "text/json" -> ".json";
                    case "application/xml", "text/xml" -> ".xml";
                    case "text/plain" -> ".txt";
                    case "text/html" -> ".html";
                    case "text/css" -> ".css";
                    case "text/csv" -> ".csv";
                    case "text/markdown" -> ".md";
                    case "text/javascript", "application/javascript" -> ".js";
                    case "image/jpeg", "image/pjpeg" -> ".jpeg";
                    case "image/png" -> ".png";
                    case "image/gif" -> ".gif";
                    case "image/webp" -> ".webp";
                    case "image/bmp", "image/x-ms-bmp" -> ".bmp";
                    case "image/svg+xml" -> ".svg";
                    case "image/tiff" -> ".tiff";
                    case "image/x-icon", "image/vnd.microsoft.icon" -> ".ico";
                    case "image/avif" -> ".avif";
                    case "image/heic" -> ".heic";
                    case "image/heif" -> ".heif";
                    case "audio/mpeg" -> ".mp3";
                    case "audio/mp4", "audio/x-m4a" -> ".m4a";
                    case "audio/aac" -> ".aac";
                    case "audio/wav", "audio/wave", "audio/x-wav", "audio/vnd.wave" -> ".wav";
                    case "audio/flac", "audio/x-flac" -> ".flac";
                    case "audio/ogg", "application/ogg" -> ".ogg";
                    case "audio/opus" -> ".opus";
                    case "audio/webm", "video/webm" -> ".webm";
                    case "audio/midi", "audio/x-midi" -> ".mid";
                    case "video/mp4" -> ".mp4";
                    case "video/mpeg" -> ".mpeg";
                    case "video/quicktime" -> ".mov";
                    case "video/x-msvideo" -> ".avi";
                    case "video/x-matroska" -> ".mkv";
                    case "video/ogg" -> ".ogv";
                    case "application/pdf" -> ".pdf";
                    case "application/msword" -> ".doc";
                    case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx";
                    case "application/vnd.ms-excel" -> ".xls";
                    case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx";
                    case "application/vnd.ms-powerpoint" -> ".ppt";
                    case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> ".pptx";
                    case "application/rtf", "text/rtf" -> ".rtf";
                    case "application/zip", "application/x-zip-compressed" -> ".zip";
                    case "application/gzip", "application/x-gzip" -> ".gz";
                    case "application/x-tar" -> ".tar";
                    case "application/x-7z-compressed" -> ".7z";
                    case "application/vnd.rar", "application/x-rar-compressed" -> ".rar";
                    default -> {
                        if (contentType != null && contentType.subtype().endsWith("+json")) yield ".json";
                        if (contentType != null && contentType.subtype().endsWith("+xml")) yield ".xml";
                        yield "";
                    }
                };
                UUID uuid = UUID.randomUUID();
                cf = new CachedFile("./cache/reposts/" + uuid + extension);
                cf.outboundUrl = new URL(outboundHttpAddress + "/Universe/" + uuid + extension);
                cf.uuid = uuid.toString();
                cf.extension = extension;
                cf.sourceUrl = url.toString();
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
        cf.retain();
        return cf.outboundUrl;
    }

    /** 注册生成的本地文件，由调用方将初始引用交给 FileField 管理。 */
    public static synchronized URL cacheLocalFile(File file) throws IOException {
        if (!file.isFile()) throw new IOException("Local cache file does not exist");
        String source = file.getCanonicalFile().toURI().toString();
        CachedFile existing = remoteUrlCachedFile.get(source);
        if (existing != null && existing.retain()) return existing.outboundUrl;
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String extension = dot >= 0 ? name.substring(dot) : "";
        CachedFile cf = new CachedFile(file.getAbsolutePath());
        cf.uuid = UUID.randomUUID().toString();
        cf.extension = extension;
        cf.sourceUrl = source;
        try {
            cf.outboundUrl = new URL(outboundHttpAddress + "/Universe/" + cf.uuid + extension);
            if (!("http".equalsIgnoreCase(cf.outboundUrl.getProtocol()) || "https".equalsIgnoreCase(cf.outboundUrl.getProtocol()))
                    || cf.outboundUrl.getHost().isBlank()) throw new MalformedURLException("HTTP address required");
        } catch (MalformedURLException error) {
            throw new IOException("请先配置 Universe 的对外 HTTP 地址", error);
        }
        cf.retain();
        remoteUrlCachedFile.put(source, cf);
        repostUrlCachedFile.put(cf.outboundUrl.toString(), cf);
        fileMounting.put(cf.uuid + extension, cf);
        return cf.outboundUrl;
    }

    /** 仅当 URL 属于受管文件缓存时增加一次引用。 */
    public static boolean retainRepost(URL url) {
        CachedFile cf = repostUrlCachedFile.get(url.toString());
        return cf != null && cf.retain();
    }

    public static void releaseRepost(URL url) {
        CachedFile cf = repostUrlCachedFile.get(url.toString());
        if (cf != null) cf.release();
    }

    public static Collection<WebSocket> listWebSocketConnections() {
        return wsServer.getConnections();
    }

    public static void setHttpPort(int httpPort) {
        UniverseChannel.httpPort = httpPort;
    }

    public static void addHttpHandler(String path, HttpHandler hh) {
        logger.info("注册 HTTP Handler: {}", path);
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
        String sourceUrl;
        private boolean disposed;

        synchronized boolean retain() {
            if (disposed) return false;
            ref.incrementAndGet();
            return true;
        }

        public synchronized void release() {
            if (disposed) return;
            released.incrementAndGet();
            if (released.get() >= ref.get()) {
                disposed = true;
                if (sourceUrl != null) remoteUrlCachedFile.remove(sourceUrl, this);
                repostUrlCachedFile.remove(outboundUrl.toString(), this);
                fileMounting.remove(uuid + extension, this);
                if (delete()) logger.debug("已清理 {} 的缓存", outboundUrl);
            }
        }

        public CachedFile(@NonNull String pathname) {
            super(pathname);
        }
    }
}

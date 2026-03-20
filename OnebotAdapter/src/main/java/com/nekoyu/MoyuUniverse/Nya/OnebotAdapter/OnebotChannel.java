package com.nekoyu.MoyuUniverse.Nya.OnebotAdapter;

import com.google.gson.*;
import com.google.gson.typeadapters.RuntimeTypeAdapterFactory;
import com.google.gson.annotations.SerializedName;
import com.google.gson.stream.JsonReader;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.JsonMessages.JsonMessage;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.JsonMessages.com_tencent_miniapp_01;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.JsonMessages.com_tencent_miniapp_lua;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.JsonMessages.com_tencent_tuwen_lua;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.Message;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.MessageSegment;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.Meta_Event;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.Notice;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.Notices.FriendRecall;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.Notices.GroupRecall;
import com.nekoyu.Universe.API.MessageChannel.*;
import com.nekoyu.Universe.API.MessageChannel.MessageField.*;
import com.nekoyu.Universe.API.MessageChannel.MessageField.TextField;
import com.nekoyu.Universe.API.MessageSession;
import com.nekoyu.Universe.Universe;
import com.nekoyu.Universe.Utils.ImageUtils;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.openqa.selenium.By;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.devtools.DevTools;
import org.openqa.selenium.devtools.v144.network.Network;
import org.openqa.selenium.devtools.v144.network.model.RequestId;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OnebotChannel extends MessageChannel {
    static final Gson gson;
    static {
        gson = new GsonBuilder()
                .registerTypeAdapterFactory(
                        RuntimeTypeAdapterFactory.of(JsonMessage.class, "app")
                                .registerSubtype(com_tencent_miniapp_01.class, "com.tencent.miniapp_01")
                                .registerSubtype(com_tencent_tuwen_lua.class, "com.tencent.tuwen.lua")
                                .registerSubtype(com_tencent_miniapp_lua.class, "com.tencent.miniapp.lua")
                )
                .create();
    }

    WebSocketClient wsConnection;
    OkHttpClient okHttpClient = new OkHttpClient();
    ExecutorService onMsgEx = Executors.newCachedThreadPool(); // 处理消息事件的线程池
    boolean isReady = true;
    Logger logger = LoggerFactory.getLogger(this.getClass());
    URI uri;
    String token;
    Map<String, Callback> syncActions = new ConcurrentHashMap<>(); // Echoes 和 Actions 的映射
    Map<String, Session> sessionInfos = new ConcurrentHashMap<>(); // 用户账号列表缓存
    boolean good = true; // 实现端健康状态
    boolean online = true; // 实现端在线状态

    public OnebotChannel(String id) {
        super(id);
        new Thread(() -> {
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            QZone qZone = new QZone();
            for (QZone.Feed feed : qZone.getLatestFeeds()) {
                logger.debug(gson.toJson(feed));
            }
        }).start();
    }

    @Override
    public MessageSession getSession(String sessionId) {
        String[] sessionParam = sessionId.split("/", 2);
        return switch (sessionParam[0]) {
            case "private" -> message -> sendPrivateMessage(sessionParam[1], message);
            case "group" -> message -> sendGroupMessage(sessionParam[1], message);
            default -> null;
        };
    }

    private int sendGroupMessage(String id, String message) {
        return sendMessage("group", id, message);
    }

    private int sendPrivateMessage(String id, String message) {
        return sendMessage("user", id, message);
    }

    private int sendMessage(String msgType, String id, String message) {
        OBRequest obr = new OBRequest("send_msg");
        obr.params.put(msgType + "_id", id);
        obr.params.put("message", message);

        return request(obr).data.getAsJsonObject().get("message_id").getAsInt();
    }

    @Override
    public void load() {
        Map<String, String> header = new HashMap<>();
        header.put("Authorization", "Bearer " + token);
        wsConnection = new WebSocketClient(uri, header) {
            @Override
            public void onOpen(ServerHandshake serverHandshake) {
                logger.info("{} 连接成功", ID);

                syncRequest(new OBRequest("get_login_info"), response -> {
                    OBResponse friendListReq = request(new OBRequest("get_friend_list"));
                    AtomicInteger friendCount = new AtomicInteger();
                    friendListReq.data.getAsJsonArray().forEach(friend -> {
                        JsonObject friendObj = friend.getAsJsonObject();
                        Account account = new Account();
                        account.setSex(friendObj.get("sex").getAsString());
                        account.setId(friendObj.get("user_id").getAsString());
                        account.setName(friendObj.get("nickname").getAsString());
                        account.setPlatform("QQ");
                        try {
                            account.setAvatar(new ImageField(new URL("https://q.qlogo.cn/headimg_dl?dst_uin=" + account.getId() + "&spec=640&img_type=jpg")));
                        } catch (MalformedURLException e) {
                            logger.error(e.getMessage(), e);
                        }
                        friendCount.getAndIncrement();
                        sessionInfos.put(account.getId(), account);
                    });
                    OBResponse groupListReq = request(new OBRequest("get_group_list"));
                    int groupCount = groupListReq.data.getAsJsonArray().size();
                    JsonObject responseData = response.data.getAsJsonObject();
                    nickname = responseData.get("nickname").getAsString();
                    accountId = responseData.get("user_id").getAsString();
                    try {
                        mainColor = ImageUtils.getMainColor(new URL("https://q.qlogo.cn/headimg_dl?dst_uin=" + accountId + "&spec=640&img_type=jpg"));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    logger.info("{} 登录的 QQ号 为 {} ({}), {} 个好友  {} 个群聊", ID, nickname, accountId, friendCount.get(), groupCount);
                });
            }

            @Override
            public void onMessage(String s) {
                JsonElement content = gson.fromJson(s, JsonElement.class);
                if (content.getAsJsonObject().get("post_type") != null) {
                    onMsgEx.submit(() -> {
                        try {
                            switch (content.getAsJsonObject().get("post_type").getAsString()) {
                                case "message" -> {
                                    Message message = gson.fromJson(s, Message.class);
                                    MCMessage mcm = new MCMessage();
                                    // 标注消息的基本信息
                                    mcm.time = message.time;
                                    mcm.receiver.setId(String.valueOf(message.self_id));
                                    mcm.receiver.setName(nickname);
                                    mcm.receiver.setPlatform("QQ");
                                    mcm.sender = (Account) getSessionInfo("user/" + message.sender.user_id);
                                    mcm.id = message.message_id;
                                    StringBuilder sessionId = new StringBuilder();
                                    sessionId.append(message.message_type).append("/");
                                    for (MessageSegment ms : message.message) {
                                        switch (ms.type) {
                                            case "text" -> {
                                                mcm.messageFields.add(new TextField(ms.data.get("text")));
                                            }
                                            case "face" -> {
                                                mcm.messageFields.add(new TextField("[QQ表情]"));
                                            }
                                            // 暂时没看到有能和emoji一一对应的表格，先不管
                                            case "image" -> {
                                                try {
                                                    ImageField imageField = new ImageField(new URL(ms.data.get("url")));
                                                    mcm.messageFields.add(imageField);
                                                } catch (MalformedURLException e) {
                                                    logger.error("无法以 {} 创建URL对象", ms.data.get("file"), e);
                                                    mcm.messageFields.add(new TextField("[图片]"));
                                                }
                                            }
                                            // 放不进去文本，先这样
                                            case "record" -> {
                                                try {
                                                    VoiceField voiceField = new VoiceField(new URL(ms.data.get("url")));
                                                    mcm.messageFields.add(voiceField);
                                                } catch (MalformedURLException e) {
                                                    logger.error("无法以 {} 创建URL对象", ms.data.get("url"), e);
                                                    mcm.messageFields.add(new TextField("[语音]"));
                                                }
                                            }
                                            case "video" -> {
                                                try {
                                                    VideoField videoField = new VideoField(new URL(ms.data.get("url")));
                                                    mcm.messageFields.add(videoField);
                                                } catch (MalformedURLException e) {
                                                    logger.error("无法以 {} 创建URL对象", ms.data.get("url"), e);
                                                    mcm.messageFields.add(new TextField("[视频]"));
                                                    // 这里发生过报错，疑似是视频消息里面，file字段本身就不是URL
                                                }
                                            }
                                            case "at" -> {
                                                if (ms.data.get("qq").equals("all")) {
                                                    Session session = new Session(); // 假造一个算了
                                                    session.setPlatform("QQ");
                                                    session.setId("all");
                                                    session.setName("全体成员");
                                                    mcm.messageFields.add(new AtField(session));
                                                } else {
                                                    var account = getSessionInfo("user/" + ms.data.get("qq"));
                                                    mcm.messageFields.add(new AtField(account));
                                                }
                                            }
                                            case "rps" -> {
                                                mcm.messageFields.add(new MetaField("[猜拳魔法表情]"));
                                            }
                                            case "dice" -> {
                                                mcm.messageFields.add(new MetaField("[掷骰子魔法表情]"));
                                            }
                                            case "shake" -> {
                                                mcm.messageFields.add(new MetaField("[窗口抖动]"));
                                            }
                                            case "poke" -> {
                                                mcm.messageFields.add(new MetaField("[戳一戳]"));
                                            }
                                            case "anonymous" -> {
                                                mcm.messageFields.add(new MetaField("(匿名消息)"));
                                            }
                                            case "share" -> {
                                                try {
                                                    mcm.messageFields.add(new ShareUrlField(ms.data.get("title"), new URL(ms.data.get("url"))));
                                                } catch (MalformedURLException e) {
                                                    logger.error("无法以 {} 创建URL对象", ms.data.get("file"), e);
                                                    mcm.messageFields.add(new TextField("[分享链接]"));
                                                }
                                            }
                                            case "contact" -> {
                                                switch (ms.data.get("type")) {
                                                    case "qq":
                                                        mcm.messageFields.add(new ShareContactField("QQ", "group/" + ms.data.get("id")));
                                                        break;
                                                    case "group":
                                                        mcm.messageFields.add(new ShareContactField("QQ", "private/" + ms.data.get("id")));
                                                        break;
                                                }
                                            }
                                            case "location" -> {
                                                mcm.messageFields.add(new LocationField(Double.parseDouble(ms.data.get("lat")), Double.parseDouble(ms.data.get("lon"))));
                                            }
                                            case "music" -> {
                                                switch (ms.data.get("type")) {
                                                    case "163" -> {
                                                        try {
                                                            mcm.messageFields.add(new ShareUrlField("网易云音乐分享", new URL("https://music.163.com/#/song?id=" + ms.data.get("id"))));
                                                        } catch (MalformedURLException e) {
                                                            logger.error("无法以 {} 创建URL对象", ms.data.get("file"), e);
                                                            mcm.messageFields.add(new TextField("[网易云音乐分享]"));
                                                        }
                                                    }
                                                    case "qq" -> {
                                                        try {
                                                            mcm.messageFields.add(new ShareUrlField("QQ音乐分享", new URL("https://y.qq.com/n/ryqq/songDetail/" + ms.data.get("id"))));
                                                        } catch (MalformedURLException e) {
                                                            logger.error("无法以 {} 创建URL对象", ms.data.get("file"), e);
                                                            mcm.messageFields.add(new TextField("[QQ音乐分享]"));
                                                        }
                                                    }
                                                    case "xm" -> {
                                                        mcm.messageFields.add(new TextField("[虾米音乐分享]"));
                                                    }
                                                    case "custom" -> {
                                                        try {
                                                            mcm.messageFields.add(new ShareUrlField("音乐分享", new URL(ms.data.get("url"))));
                                                        } catch (MalformedURLException e) {
                                                            logger.error("无法以 {} 创建URL对象", ms.data.get("file"), e);
                                                            mcm.messageFields.add(new TextField("音乐分享"));
                                                        }
                                                    }
                                                }
                                            }
                                            case "reply" -> {
                                                mcm.messageFields.add(new MetaField("回复消息" + ms.data.get("id")));
                                            }
                                            case "forward" -> {
                                                mcm.messageFields.add(new MetaField("[合并转发]"));
                                            }
                                            case "node" -> {
                                                mcm.messageFields.add(new MetaField("[合并转发节点]"));
                                            }
                                            // 合并转发自定义节点 没做
                                            case "xml" -> {
                                                mcm.messageFields.add(new MetaField("[XML消息]"));
                                                logger.debug(ms.data.get("data"));
                                            }
                                            case "json" -> {
                                                try {
                                                    JsonMessage jm = gson.fromJson(ms.data.get("data"), JsonMessage.class);
                                                    if (jm instanceof com_tencent_miniapp_01 card) {
                                                        mcm.messageFields.add(new ShareUrlField(card.meta.detail_1.title + " - " + card.meta.detail_1.desc, null));
                                                    } else if (jm instanceof com_tencent_tuwen_lua card) {
                                                        ShareUrlField shareUrlField = new ShareUrlField("[" + card.meta.news.tag + "]" + card.meta.news.title + " - " + card.meta.news.desc, new URL(card.meta.news.jumpUrl));
                                                        Request req = new Request.Builder()
                                                                .url(card.meta.news.preview)
                                                                .build();
                                                        try (Response resp = okHttpClient.newCall(req).execute()) {
                                                            String content_type = resp.header("Content-Type");
                                                            if (resp.isSuccessful() && content_type != null && content_type.startsWith("image")) shareUrlField.image = new ImageField(new URL(card.meta.news.preview));
                                                        } catch (IOException ignored) {

                                                        }
                                                        mcm.messageFields.add(shareUrlField);
                                                    } else if (jm instanceof com_tencent_miniapp_lua card) {
                                                        URL url = null;
                                                        if (card.meta.miniapp.jumpUrl.startsWith("http")) url = new URL(card.meta.miniapp.jumpUrl);
                                                        else if (card.meta.miniapp.legacyUrl != null && card.meta.miniapp.legacyUrl.startsWith("http")) url = new URL(card.meta.miniapp.legacyUrl);
                                                        mcm.messageFields.add(new ShareUrlField("[" + card.meta.miniapp.tag + "]" + card.meta.miniapp.title, url));
                                                    }
                                                } catch (Throwable e) {
                                                    JsonElement je = gson.fromJson(ms.data.get("data"), JsonElement.class);
                                                    mcm.messageFields.add(new MetaField(je.getAsJsonObject().get("prompt").getAsString()));
                                                    logger.debug(ms.data.get("data"), e);
                                                }
                                            }
                                        }
                                    }
                                    // 给消息定级
                                    for (MessageSegment seg : message.message) {
                                        if (seg.type.equals("at") && seg.data.get("qq").equals(String.valueOf(accountId))) {
                                            mcm.level = 2;
                                            break;
                                        }
                                    }
                                    switch (message.message_type) {
                                        case "group":
                                            Long groupId = message.group_id;
                                            sessionId.append(groupId);
                                            mcm.session = getSessionInfo(sessionId.toString());
                                            break;
                                        case "private":
                                            Long userId = message.user_id;
                                            sessionId.append(userId);
                                            mcm.session = mcm.sender;
                                            break;
                                    }
                                    broadcastMessage(sessionId.toString(), mcm);
                                    CompletableFuture.runAsync(() -> {
                                        try {
                                            Thread.sleep(60000);
                                            for (var f : mcm.messageFields) {
                                                if (f instanceof FileField ff) ff.repost();
                                            }
                                        } catch (IOException | InterruptedException e) {
                                            throw new RuntimeException(e);
                                        }
                                    });
                                }
                                case "meta_event" -> {
                                    Meta_Event meta_event = gson.fromJson(s, Meta_Event.class);
                                    if (meta_event.sub_type.equals("heartbeat")) { // 解析心跳包
                                        if (meta_event.status.online) {
                                            online = true;
                                        } else if (online) {
                                            online = false;
                                            logger.warn("{} 的 OneBot 实现端离线", ID);
                                        }
                                        if (meta_event.status.good) {
                                            good = true;
                                        } else if (good) {
                                            good = false;
                                            logger.warn("{} 的 OneBot 实现端状态异常", ID);
                                        }
                                    }
                                }
                                case "notice" -> {
                                    Notice notice = gson.fromJson(s, Notice.class);
                                    switch (notice.notice_type) {
                                        case "group_recall" -> {
                                            GroupRecall groupRecall = gson.fromJson(s, GroupRecall.class);
                                            Universe.MessageChannelManager.onMessageRecall(ID + ":group/" + groupRecall.group_id, groupRecall.message_id);
                                        }
                                        case "friend_recall" -> {
                                            FriendRecall friendRecall = gson.fromJson(s, FriendRecall.class);
                                            Universe.MessageChannelManager.onMessageRecall(ID + ":private/" + friendRecall.user_id, friendRecall.message_id);
                                        }
                                    }
                                }
                                default -> {
                                    return;
                                }
                            }
                        } catch (JsonSyntaxException ignored) {

                        }
                    });
                }
                if (content.getAsJsonObject().get("echo") != null) {
                    OBResponse response = gson.fromJson(content, OBResponse.class);
                    if (!response.echo.isEmpty()) {
                        new Thread(() -> syncActions.remove(response.echo).callback(response)).start();
                    }
                }
            }

            @Override
            public void onClose(int i, String s, boolean b) {
                if (i != -1) logger.warn("{} 断开连接", ID);
                Executors.newSingleThreadScheduledExecutor().schedule(() -> reload(), 10, TimeUnit.SECONDS);
            }

            @Override
            public void onError(Exception e) {

            }
        };
        wsConnection.connect();
        Universe.MessageChannelManager.registerChannel(this.ID, this);
    }

    @Override
    public void stop() {
        isReady = false;
        wsConnection.close();
    }

    @Override
    public int sendMessage(String sessionId, String message) {
        String[] target = sessionId.split("/");
        return switch (target[0]) {
            case "group" -> sendGroupMessage(target[1], message);
            case "private" -> sendPrivateMessage(target[1], message);
            default -> {
                logger.warn("前所未闻的会话ID: {}", sessionId);
                yield -1;
            }
        };
    }

    /**
     * @param sessionId group/*******
     */
    @Override
    public void setSessionName(String sessionId, String name) throws UnsupportedAction {
        String[] split = sessionId.split("/");
        if (!split[0].equals("group")) throw new UnsupportedAction("只有Group类型的会话支持此功能");
        OBRequest obr = new OBRequest();
        obr.action = "set_group_name";
        obr.params.put("group_id", split[1]);
        obr.params.put("group_name", name);

        action(obr);
        logger.info("将 {} 的群名称修改为 {}", split[1], name);
    }

    private void reload() {
        load();
    }

    /**
     * 用于直接发送不需要处理结果的请求
     * 如果需要处理请求的结果，请改用 syncRequest 方法
     *
     * @param obr OnebotRequest
     */
    private void action(OBRequest obr) {
        if (!wsConnection.isOpen()) return;
        wsConnection.send(new Gson().toJson(obr));
    }

    private OBResponse request(OBRequest obr) {
        if (!wsConnection.isOpen()) return null;

        CountDownLatch latch = new CountDownLatch(1);
        final OBResponse[] obrResponse = new OBResponse[1];

        syncRequest(obr, response -> {
            obrResponse[0] = response;
            latch.countDown();
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            logger.error(e.getMessage(), e);
        }

        return obrResponse[0];
    }

    /**
     * 此方法用于发送需要处理结果的请求，请求结果交由 Callback 对象中定义的代码处理
     *
     * @param obr      OnebotRequest
     * @param callback 回调函数
     */
    private void syncRequest(OBRequest obr, Callback callback) {
        if (!wsConnection.isOpen()) return;
        UUID uuid = UUID.randomUUID();
        obr.echo = uuid.toString();
        syncActions.put(obr.echo, callback);
        wsConnection.send(new Gson().toJson(obr));
    }

    @Override
    public Session getSessionInfo(String sessionId) {
        String[] acc = sessionId.split("/", 2);
        if (!acc[0].equals("private") && !acc[0].equals("user") && !acc[0].equals("group")) {
            logger.error("getSessionInfo() cannot handle {}", sessionId);
            throw new UnsupportedAction("Unsupported session type");
        }
        Session session = sessionInfos.get(acc[1]);
        if (session == null) {
            OBRequest obr = new OBRequest();
            switch (acc[0]) {
                case "group" -> {
                    obr.action = "get_group_info";
                    obr.params.put("group_id", acc[1]);
                }
                case "user", "private" -> {
                    obr.action = "get_stranger_info";
                    obr.params.put("user_id", acc[1]);
                }
            }
            OBResponse resp = request(obr);
            if (resp.status.equals("failed")) throw new RuntimeException("Request Failed");
            switch (acc[0]) {
                case "group" -> {
                    var groupInfo = new Group();
                    groupInfo.setName(resp.data.getAsJsonObject().get("group_name").getAsString());
                    groupInfo.setId(acc[1]);
                    groupInfo.setPlatform("QQ");
                    try {
                        groupInfo.setAvatar(new ImageField(new URL("https://p.qlogo.cn/gh/" + acc[1] + "/" + acc[1] + "/0")));
                    } catch (MalformedURLException e) {
                        logger.error(e.getMessage(), e);
                    }
                    session = groupInfo;
                }
                case "user", "private" -> {
                    var accountInfo = new QQAccount();
                    accountInfo.setName(resp.data.getAsJsonObject().get("nickname").getAsString());
                    accountInfo.setId(acc[1]);
                    accountInfo.setPlatform("QQ");
                    accountInfo.setSex(resp.data.getAsJsonObject().get("sex").getAsString());
                    try {
                        accountInfo.setAvatar(new ImageField(new URL("https://q.qlogo.cn/headimg_dl?dst_uin=" + accountInfo.getId() + "&spec=640&img_type=jpg")));
                    } catch (MalformedURLException e) {
                        logger.error(e.getMessage(), e);
                    }
                    session = accountInfo;
                }
            }
            sessionInfos.put(acc[1], session);
        }
        return session;
    }

    private interface Callback {
        void callback(OBResponse response);
    }

    public class QQAccount extends Account {
        public void sendLike(int count) { // Max 10 as default and could be 20 with SVIP
            OBRequest obr = new OBRequest("send_like");
            obr.params.put("user_id", getId());
            obr.params.put("times", count);
            request(obr);
        }
    }

    // 使用Selenium访问和简单操作QZone
    public class QZone {
        ChromeDriver driver;
        DevTools devTools;
        ConcurrentHashMap<String, String> requestUrlMap = new ConcurrentHashMap<>();
        AtomicBoolean receiving = new AtomicBoolean(false);
        volatile CountDownLatch feedLatch;
        volatile List<Feed> feedBuffer;
        // 此处通过请求Onebot API get_cookies 获取cookies初始化会话
        public QZone() {
            driver = new ChromeDriver();
            OBRequest obr = new OBRequest("get_cookies");
            obr.params.put("domain", "qzone.qq.com");
            try {
                var resp = request(obr);
                String cookiesString = resp.data.getAsJsonObject().get("cookies").getAsString();
                String bkn = resp.data.getAsJsonObject().get("bkn").getAsString(); // 不知道有什么用
                logger.debug(cookiesString);
                driver.get("https://qzone.qq.com/"); // 先打开这个页面
                new WebDriverWait(driver, Duration.ofSeconds(10)).until(
                        webDriver -> Objects.equals(((JavascriptExecutor) webDriver)
                                .executeScript("return document.readyState"), "complete")
                ); // 加载完页面就 加 cookies 刷新
                for (String item : cookiesString.split("; ")) {
                    String[] key_value = item.split("=");
                    Cookie cookie = new Cookie.Builder(key_value[0], key_value[1])
                            .domain(".qzone.qq.com").build();
                    driver.manage().addCookie(cookie);
                }
                driver.get("https://qzone.qq.com/"); // 然后重新打开
                devTools = driver.getDevTools();
                devTools.createSession();
                devTools.send(Network.enable(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()
                ));
                devTools.addListener(Network.requestWillBeSent(), request -> {
                    String requestId = request.getRequestId().toString();
                    String url = request.getRequest().getUrl();
                    requestUrlMap.put(requestId, url);
                });
                devTools.addListener(Network.loadingFinished(), finished -> {
                    if (!receiving.get()) return;
                    try {
                        RequestId requestId = finished.getRequestId();
                        String url = requestUrlMap.remove(requestId.toString());
                        if (url == null) return;
                        if (!url.startsWith("https://user.qzone.qq.com/proxy/domain/ic2.qzone.qq.com/cgi-bin/feeds/feeds3_html_more?")) {
                            return;
                        }
                        String body = devTools.send(Network.getResponseBody(requestId)).getBody();
                        String cleaned = MessyDataCleaner.cleanToGsonJson(body);
                        FeedResponse fr = gson.fromJson(cleaned, FeedResponse.class);
                        if (fr == null || fr.data == null || fr.data.data == null) return;
                        for (FeedResponse.Data.Feed f : fr.data.data) {
                            if (f == null || f.html == null) continue;
                            FeedParser.QZoneFeed parsed = FeedParser.parseFeed(f.html);
                            if (parsed == null) continue;
                            Feed feed = new Feed();
                            feed.key = f.key;
                            feed.content = parsed.getContent();
                            feed.publishTimestamp = parsed.getPublishTimestamp();
                            feed.device = parsed.getDevice();
                            feed.retweetCount = parsed.getRetweetCount();
                            feed.imageUrls = parsed.getImageUrls() == null ? new ArrayList<>() : new ArrayList<>(parsed.getImageUrls());
                            feed.likeCount = parsed.getLikers() == null ? 0 : parsed.getLikers().size();
                            feed.commentCount = parsed.getComments() == null ? 0 : parsed.getComments().size();
                            if (parsed.getPublisherQQ() != null && !parsed.getPublisherQQ().isEmpty()) {
                                QQAccount account = new QQAccount();
                                account.setId(parsed.getPublisherQQ());
                                account.setName(parsed.getPublisherNick());
                                account.setPlatform("QQ");
                                try {
                                    account.setAvatar(new ImageField(new URL("https://q.qlogo.cn/headimg_dl?dst_uin=" + account.getId() + "&spec=640&img_type=jpg")));
                                } catch (MalformedURLException e) {
                                    logger.error(e.getMessage(), e);
                                }
                                feed.account = account;
                            }
                            if (feedBuffer != null) feedBuffer.add(feed);
                        }
                        if (feedLatch != null) feedLatch.countDown();
                    } catch (Exception e) {
                        logger.debug("Failed to parse QZone feeds response", e);
                    }
                });
                // 至此这个模块初始化完毕可以用了
            } catch (NullPointerException e) {
                throw new RuntimeException("Failed to initialize QZone instance", e);
            }
        }

        // 自动用浏览器翻好友动态页，截取数据包并解析出Feeds
        public List<Feed> getLatestFeeds() {
            feedBuffer = new CopyOnWriteArrayList<>();
            feedLatch = new CountDownLatch(1);
            receiving.set(false);
            try {
                WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(60));
                WebElement element = wait.until(
                        ExpectedConditions.elementToBeClickable(By.id("tab_menu_friend"))
                );
                element.click();
                receiving.set(true);
                feedLatch.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                logger.warn("Failed to fetch latest QZone feeds", e);
            } finally {
                receiving.set(false);
            }
            return new ArrayList<>(feedBuffer);
        }

        public static class Feed {
            String key;
            transient QQAccount account;
            String content;
            long publishTimestamp;
            String device;
            int retweetCount;
            int likeCount;
            int commentCount;
            List<String> imageUrls;
        }
    }

    private static class FeedResponse {
        int code;
        int subcode;
        String message;
        @SerializedName("default")
        int default_;
        Data data;

        public FeedResponse() {
            this.data = new Data();
        }

        public static class Data {
            Main main;
            LinkedList<Feed> data;

            public Data() {
                this.main = new Main();
                this.data = new LinkedList<>();
            }

            public static class Main {
                String attach;
                String searchtype;
                boolean hasMoreFeeds;
                String daylist;
                String uinlist;
                String error;
                String hotkey;
                LinkedList<Object> icGroupData;
                String host_level;
                String friend_level;
                String lastaccesstime;
                String lastAccessRelateTime;
                String begintime;
                String endtime;
                String dayspac;
                LinkedList<Object> hidedNameList;
                String aisortBeginTime;
                String aisortEndTime;
                String aisortOffset;
                String aisortNextTime;
                String owner_bitmap;
                String pagenum;
                String externparam;

                public Main() {
                    this.icGroupData = new LinkedList<>();
                    this.hidedNameList = new LinkedList<>();
                }
            }

            public static class Feed {
                String ver;
                String appid;
                String typeid;
                String key;
                String flag;
                String dataonly;
                String titleTemp;
                String summaryTemp;
                String feedno;
                String title;
                String summary;
                String appiconid;
                String clscFold;
                String abstime;
                String feedstime;
                String userHome;
                String namecardLink;
                String opuin;
                String uin;
                String ouin;
                String foldFeed;
                String foldFeedTitle;
                String showEbtn;
                String scope;
                String hideExtend;
                String nickname;
                LinkedList<Object> emoji;
                String remark;
                String type;
                String vip;
                String bitmap;
                String yybitmap;
                String info_user_name;
                String logimg;
                String bor;
                String lastFeedBor;
                String list_bor2;
                String info_user_display;
                String upernum;
                String oprType;
                String moreflag;
                String otherflag;
                String rightflag;
                SameUser sameuser;
                LinkedList<Object> uper_isfriend;
                LinkedList<Object> uperlist;
                String smallstar;
                String html;
                LinkedList<Object> mergeData;
                String likecnt;
                String relycnt;
                String commentcnt;

                public Feed() {
                    this.emoji = new LinkedList<>();
                    this.uper_isfriend = new LinkedList<>();
                    this.uperlist = new LinkedList<>();
                    this.mergeData = new LinkedList<>();
                    this.sameuser = new SameUser();
                }
            }

            public static class SameUser {
                // empty
            }
        }
    }

    private static class MessyDataCleaner {
        private static String getJsonFromJsonP(String jsonp) {
            if (jsonp == null) return null;
            String s = jsonp.trim();
            int l = s.indexOf('(');
            int r = s.lastIndexOf(')');
            if (l < 0 || r < 0 || r <= l) return s;
            return s.substring(l + 1, r).trim();
        }

        private static final Pattern HEX_ESCAPE = Pattern.compile("\\\\x([0-9A-Fa-f]{2})");

        public static String cleanToGsonJson(String raw) {
            raw = getJsonFromJsonP(raw);
            if (raw == null || raw.trim().isEmpty()) {
                return "{}";
            }

            String s = raw.replace("\uFEFF", "");

            s = hexToUnicodeEscapes(s);

            s = s.replaceAll("\\bundefined\\b", "null")
                    .replaceAll("\\bNaN\\b", "null")
                    .replaceAll("\\bInfinity\\b", "null")
                    .replaceAll("\\b-Infinity\\b", "null")
                    .replaceAll("\\bTrue\\b", "true")
                    .replaceAll("\\bFalse\\b", "false")
                    .replaceAll("\\bNone\\b", "null");

            s = s.replaceAll(",(?=\\s*[}\\]])", "");

            try {
                JsonReader reader = new JsonReader(new StringReader(s));
                reader.setLenient(true);
                JsonElement element = JsonParser.parseReader(reader);
                Gson gson = new GsonBuilder()
                        .disableHtmlEscaping()
                        .create();
                return gson.toJson(element);
            } catch (JsonSyntaxException e) {
                throw new IllegalArgumentException("Cleaned data still not valid for Gson: " + e.getMessage(), e);
            }
        }

        private static String hexToUnicodeEscapes(String input) {
            Matcher m = HEX_ESCAPE.matcher(input);
            StringBuilder sb = new StringBuilder(input.length());
            while (m.find()) {
                m.appendReplacement(sb, "\\\\u00" + m.group(1).toUpperCase());
            }
            m.appendTail(sb);
            return sb.toString();
        }
    }

    private static class FeedParser {

        public static QZoneFeed parseFeed(String html) {
            Document doc = Jsoup.parseBodyFragment(html);
            Element feedItem = doc.selectFirst("li.f-single");
            if (feedItem == null) return null;

            if (isAdvertisement(feedItem)) return null;

            QZoneFeed feed = new QZoneFeed();

            Element userLink = feedItem.selectFirst(".user-pto a");
            if (userLink != null) {
                String qq = extractQQFromUrl(userLink.attr("href"));
                feed.setPublisherQQ(qq);
            }
            Element nickElem = feedItem.selectFirst(".f-nick .f-name");
            if (nickElem != null) {
                feed.setPublisherNick(nickElem.text());
            }

            Element dataElem = feedItem.selectFirst("[data-abstime]");
            if (dataElem != null) {
                String abstime = dataElem.attr("data-abstime");
                if (!abstime.isEmpty()) feed.setPublishTimestamp(Long.parseLong(abstime));
            }

            StringBuilder contentBuilder = new StringBuilder();

            Element feedData = feedItem.selectFirst("i[name=feed_data]");
            String origUin = feedData != null ? feedData.attr("data-origuin") : "";
            boolean isRepost = origUin != null && !origUin.isEmpty() && !origUin.equals(feed.getPublisherQQ());

            Element infoDiv = feedItem.selectFirst(".f-info");
            if (infoDiv != null) {
                infoDiv.select("a[data-cmd=qz_toggle]").remove();
                String infoText = infoDiv.text().trim();
                if (!infoText.isEmpty()) contentBuilder.append(infoText);
            }

            if (isRepost) {
                Element txtBox = feedItem.selectFirst(".f-ct-txtimg .txt-box");
                if (txtBox != null) {
                    String txtBoxText = txtBox.text().trim();
                    if (!txtBoxText.isEmpty()) {
                        if (contentBuilder.length() > 0) contentBuilder.append("\n");
                        contentBuilder.append(txtBoxText);
                    }
                }
            }

            if (!isRepost && contentBuilder.length() == 0) {
                Element txtBox = feedItem.selectFirst(".f-ct-txtimg .txt-box");
                if (txtBox != null) contentBuilder.append(txtBox.text().trim());
            }

            feed.setContent(contentBuilder.toString().trim());

            Element deviceSpan = feedItem.selectFirst(".f-reprint span.phone-style");
            if (deviceSpan != null) feed.setDevice(deviceSpan.text());

            if (dataElem != null) {
                String retweet = dataElem.attr("data-retweetcount");
                if (!retweet.isEmpty()) feed.setRetweetCount(Integer.parseInt(retweet));
            }

            Elements likeItems = feedItem.select(".f-like-list .user-list a");
            List<QZoneFeed.Liker> likers = new ArrayList<>();
            for (Element a : likeItems) {
                String qq = extractQQFromUrl(a.attr("href"));
                String nick = a.text();
                likers.add(new QZoneFeed.Liker(qq, nick));
            }
            feed.setLikers(likers);

            Elements commentRoots = feedItem.select(".mod-comments .comments-list > ul > li.comments-item[data-type=commentroot]");
            List<QZoneFeed.Comment> comments = new ArrayList<>();
            for (Element rootLi : commentRoots) comments.add(parseComment(rootLi));
            feed.setComments(comments);

            Elements imgItems = feedItem.select(".img-box a.img-item[data-pickey]");
            List<String> imageUrls = new ArrayList<>();
            for (Element a : imgItems) {
                String pickey = a.attr("data-pickey");
                if (pickey != null && pickey.contains(",")) {
                    String[] parts = pickey.split(",", 2);
                    if (parts.length > 1) {
                        String url = parts[1].trim().replace("&amp;", "&");
                        imageUrls.add(url);
                    }
                } else {
                    Element img = a.selectFirst("img");
                    if (img != null) {
                        String src = img.attr("src");
                        if (src != null && !src.isEmpty()) imageUrls.add(src);
                    }
                }
            }
            feed.setImageUrls(imageUrls);

            return feed;
        }

        private static boolean isAdvertisement(Element feedItem) {
            if (feedItem.hasClass("f-single-biz")) return true;
            if (feedItem.selectFirst("[data-advfeed-click-url]") != null) return true;
            Element dataElem = feedItem.selectFirst("i[name=feed_data][data-fkey]");
            if (dataElem != null) {
                String fkey = dataElem.attr("data-fkey");
                if (fkey != null && fkey.startsWith("advertisement")) return true;
            }
            return feedItem.selectFirst(".f-single-top span:contains(广告)") != null;
        }

        private static QZoneFeed.Comment parseComment(Element li) {
            QZoneFeed.Comment comment = new QZoneFeed.Comment();
            comment.setPublisherQQ(li.attr("data-uin"));

            Element nickLink = li.selectFirst(".comments-content .nickname");
            if (nickLink != null) comment.setPublisherNick(nickLink.text());

            Element contentDiv = li.selectFirst(".comments-content");
            if (contentDiv != null) {
                StringBuilder sb = new StringBuilder();
                for (org.jsoup.nodes.Node node : contentDiv.childNodes()) {
                    if (node instanceof Element) {
                        Element e = (Element) node;
                        if (e.hasClass("comments-op")) {
                            break;
                        }
                    }
                    if (node instanceof TextNode) {
                        sb.append(((TextNode) node).text());
                    } else if (node instanceof Element) {
                        Element e = (Element) node;
                        if (!e.hasClass("nickname") && !e.hasClass("name")) {
                            sb.append(e.text());
                        }
                    }
                }
                comment.setContent(sb.toString().trim());
            }

            Element timeSpan = li.selectFirst(".comments-op .state");
            if (timeSpan != null) comment.setTimeStr(timeSpan.text());

            Element subList = li.selectFirst(".mod-comments-sub > ul");
            if (subList != null) {
                List<QZoneFeed.Comment> replies = new ArrayList<>();
                for (Element replyLi : subList.select("> li.comments-item"))
                    replies.add(parseComment(replyLi));
                comment.setReplies(replies);
            }
            return comment;
        }

        private static String extractQQFromUrl(String url) {
            if (url == null || url.isEmpty()) return "";
            for (String part : url.split("/"))
                if (part.matches("\\d+")) return part;
            return "";
        }

        public static class QZoneFeed {
            private String publisherNick, publisherQQ, content, device;
            private long publishTimestamp;
            private int retweetCount;
            private List<Liker> likers;
            private List<Comment> comments;
            private List<String> imageUrls;

            public String getPublisherNick() { return publisherNick; }
            public void setPublisherNick(String publisherNick) { this.publisherNick = publisherNick; }
            public String getPublisherQQ() { return publisherQQ; }
            public void setPublisherQQ(String publisherQQ) { this.publisherQQ = publisherQQ; }
            public long getPublishTimestamp() { return publishTimestamp; }
            public void setPublishTimestamp(long publishTimestamp) { this.publishTimestamp = publishTimestamp; }
            public String getContent() { return content; }
            public void setContent(String content) { this.content = content; }
            public String getDevice() { return device; }
            public void setDevice(String device) { this.device = device; }
            public int getRetweetCount() { return retweetCount; }
            public void setRetweetCount(int retweetCount) { this.retweetCount = retweetCount; }
            public List<Liker> getLikers() { return likers; }
            public void setLikers(List<Liker> likers) { this.likers = likers; }
            public List<Comment> getComments() { return comments; }
            public void setComments(List<Comment> comments) { this.comments = comments; }
            public List<String> getImageUrls() { return imageUrls; }
            public void setImageUrls(List<String> imageUrls) { this.imageUrls = imageUrls; }

            public static class Liker {
                private final String qq;
                private final String nick;
                public Liker(String qq, String nick) { this.qq = qq; this.nick = nick; }
                public String getQq() { return qq; }
                public String getNick() { return nick; }
            }

            public static class Comment {
                private String publisherQQ, publisherNick, content, timeStr;
                private List<Comment> replies;
                public String getPublisherQQ() { return publisherQQ; }
                public void setPublisherQQ(String publisherQQ) { this.publisherQQ = publisherQQ; }
                public String getPublisherNick() { return publisherNick; }
                public void setPublisherNick(String publisherNick) { this.publisherNick = publisherNick; }
                public String getContent() { return content; }
                public void setContent(String content) { this.content = content; }
                public String getTimeStr() { return timeStr; }
                public void setTimeStr(String timeStr) { this.timeStr = timeStr; }
                public List<Comment> getReplies() { return replies; }
                public void setReplies(List<Comment> replies) { this.replies = replies; }
            }
        }
    }
}

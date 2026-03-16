package com.nekoyu.MoyuUniverse.Nya.OnebotAdapter;

import com.google.gson.*;
import com.google.gson.typeadapters.RuntimeTypeAdapterFactory;
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
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

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
    Map<String, SessionInfo> sessionInfos = new ConcurrentHashMap<>(); // 用户账号列表缓存
    boolean good = true; // 实现端健康状态
    boolean online = true; // 实现端在线状态

    public OnebotChannel(String id) {
        super(id);
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
                                                    SessionInfo sessionInfo = new SessionInfo(); // 假造一个算了
                                                    sessionInfo.setPlatform("QQ");
                                                    sessionInfo.setId("all");
                                                    sessionInfo.setName("全体成员");
                                                    mcm.messageFields.add(new AtField(sessionInfo));
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
                                            mcm.sessionInfo = getSessionInfo(sessionId.toString());
                                            break;
                                        case "private":
                                            Long userId = message.user_id;
                                            sessionId.append(userId);
                                            mcm.sessionInfo = mcm.sender;
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
    public SessionInfo getSessionInfo(String sessionId) {
        String[] acc = sessionId.split("/", 2);
        if (!acc[0].equals("private") && !acc[0].equals("user") && !acc[0].equals("group")) {
            logger.error("getSessionInfo() cannot handle {}", sessionId);
            throw new UnsupportedAction("Unsupported session type");
        }
        SessionInfo sessionInfo = sessionInfos.get(acc[1]);
        if (sessionInfo == null) {
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
                    sessionInfo = groupInfo;
                }
                case "user", "private" -> {
                    var accountInfo = new Account();
                    accountInfo.setName(resp.data.getAsJsonObject().get("nickname").getAsString());
                    accountInfo.setId(acc[1]);
                    accountInfo.setPlatform("QQ");
                    accountInfo.setSex(resp.data.getAsJsonObject().get("sex").getAsString());
                    try {
                        accountInfo.setAvatar(new ImageField(new URL("https://q.qlogo.cn/headimg_dl?dst_uin=" + accountInfo.getId() + "&spec=640&img_type=jpg")));
                    } catch (MalformedURLException e) {
                        logger.error(e.getMessage(), e);
                    }
                    sessionInfo = accountInfo;
                }
            }
            sessionInfos.put(acc[1], sessionInfo);
        }
        return sessionInfo;
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
}
package com.nekoyu.MoyuUniverse.Nya.OnebotAdapter;

import com.google.gson.*;
import com.google.gson.typeadapters.RuntimeTypeAdapterFactory;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.MsgFields.Image;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.MsgFields.Text;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.JsonMessages.JsonMessage;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.JsonMessages.com_tencent_miniapp_01;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.JsonMessages.com_tencent_miniapp_lua;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.JsonMessages.com_tencent_tuwen_lua;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.Message;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.MsgFields.MessageSegment;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.Meta_Event;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.Notice;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.Notices.FriendRecall;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.Notices.GroupRecall;
import com.nekoyu.Universe.API.MessageChannel.*;
import com.nekoyu.Universe.API.MessageChannel.Features.Administration;
import com.nekoyu.Universe.API.MessageChannel.Features.PostChat;
import com.nekoyu.Universe.API.MessageChannel.Features.SessionChat;
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
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OnebotChannel extends MessageChannel implements SessionChat, PostChat, Administration {
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
    Map<String, Session> cachedSessions = new ConcurrentHashMap<>(); // 用户账号列表缓存
    boolean good = true; // 实现端健康状态
    boolean online = true; // 实现端在线状态
    QZone qZone = new QZone();

    public OnebotChannel(String id) {
        super(id);
        new Thread(() -> {
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }).start();
    }

    @Override
    public MessageSession getChatSession(String sessionId) {
        String[] sessionParam = sessionId.split("/", 2);
        return switch (sessionParam[0]) {
            case "private", "user" -> message -> sendPrivateMessage(sessionParam[1], message);
            case "group" -> message -> sendGroupMessage(sessionParam[1], message);
            default -> null;
        };
    }

    private int sendGroupMessage(String id, LinkedList<MsgField> message) {
        return sendMessage("group", id, message);
    }

    private int sendPrivateMessage(String id, LinkedList<MsgField> message) {
        return sendMessage("user", id, message);
    }

    private int sendMessage(String msgType, String id, LinkedList<MsgField> message) {
        OBRequest obr = new OBRequest("send_msg");
        obr.params.put(msgType + "_id", id);
        LinkedList<MessageSegment> obMsg = new LinkedList<>();
        for (MsgField field : message) {
            if (field instanceof ImageField imgF) {
                obMsg.add(new Image(imgF.getUrl().toString()));
            } else obMsg.add(new Text(field.toString()));
        }
        obr.params.put("message", obMsg);

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
                    JsonArray friendsList = friendListReq.data.getAsJsonArray();
                    int friendCount = friendsList.size();
                    friendsList.forEach(friend -> {
                        JsonObject friendObj = friend.getAsJsonObject();
                        QQAccount account = new QQAccount();
                        account.setSex(friendObj.get("sex").getAsString());
                        account.setId(friendObj.get("user_id").getAsString());
                        account.setName(friendObj.get("nickname").getAsString());
                        cachedSessions.put(account.getId(), account);
                    });
                    OBResponse groupListReq = request(new OBRequest("get_group_list"));
                    JsonArray groupList = groupListReq.data.getAsJsonArray();
                    int groupCount = groupList.size();
                    groupList.forEach(group -> {
                        JsonObject groupObj = group.getAsJsonObject();
                        QQGroup groupInfo = new QQGroup();
                        groupInfo.setName(groupObj.get("group_name").getAsString());
                        groupInfo.setId(groupObj.get("group_id").getAsString());
                        cachedSessions.put(groupInfo.getId(), groupInfo);
                    });
                    JsonObject responseData = response.data.getAsJsonObject();
                    nickname = responseData.get("nickname").getAsString();
                    accountId = responseData.get("user_id").getAsString();
                    try {
                        mainColor = ImageUtils.getMainColor(new URL("https://q.qlogo.cn/headimg_dl?dst_uin=" + accountId + "&spec=640&img_type=jpg"));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    logger.info("{} 登录的 QQ号 为 {} ({}), {} 个好友  {} 个群聊", ID, nickname, accountId, friendCount, groupCount);
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
                                    mcm.sender = (Account) getSession("user/" + message.sender.user_id);
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
                                                } catch (RuntimeException e) {
                                                    logger.error(e.getMessage(), e);
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
                                                    var account = getSession("user/" + ms.data.get("qq"));
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
                                                            if (resp.isSuccessful() && content_type != null && content_type.startsWith("image"))
                                                                shareUrlField.image = new ImageField(new URL(card.meta.news.preview));
                                                        } catch (IOException ignored) {

                                                        }
                                                        mcm.messageFields.add(shareUrlField);
                                                    } else if (jm instanceof com_tencent_miniapp_lua card) {
                                                        URL url = null;
                                                        if (card.meta.miniapp.jumpUrl.startsWith("http"))
                                                            url = new URL(card.meta.miniapp.jumpUrl);
                                                        else if (card.meta.miniapp.legacyUrl != null && card.meta.miniapp.legacyUrl.startsWith("http"))
                                                            url = new URL(card.meta.miniapp.legacyUrl);
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
                                            mcm.session = getSession(sessionId.toString());
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
                                            logger.error("转储文件失败", e);
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
    public int sendMessage(String sessionId, MessageChain message) {
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

        if (obrResponse[0].status.equals("failed")) {
            throw new OBException(obrResponse[0].retcode, obrResponse[0].message);
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
    public Session getSession(String sessionId) {
        String[] acc = sessionId.split("/", 2);
        if (!acc[0].equals("private") && !acc[0].equals("user") && !acc[0].equals("group")) {
            logger.error("getSession() cannot handle {}", sessionId);
            throw new UnsupportedAction("Unsupported session type");
        }
        Session session = cachedSessions.get(acc[1]);
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
                    var groupInfo = new QQGroup();
                    groupInfo.setName(resp.data.getAsJsonObject().get("group_name").getAsString());
                    groupInfo.setId(acc[1]);
                    session = groupInfo;
                }
                case "user", "private" -> {
                    var accountInfo = new QQAccount();
                    accountInfo.setName(resp.data.getAsJsonObject().get("nickname").getAsString());
                    accountInfo.setId(acc[1]);
                    accountInfo.setSex(resp.data.getAsJsonObject().get("sex").getAsString());
                    session = accountInfo;
                }
            }
            cachedSessions.put(acc[1], session);
        }
        return session;
    }

    @Override
    public void sendLike(String sessionId) {
        String[] split = sessionId.split("/");
        qZone.addTask(new QZone.Task.SendLikeTask(split[split.length - 1]));
    }

    @Override
    public void replyPost(String sessionId, MessageChain message) {
        String[] split = sessionId.split("/");
        qZone.addTask(new QZone.Task.SendCommentTask(split[split.length - 1], message));
    }

    @Override
    public void repost(String sessionId, MessageChain message) {
        String[] split = sessionId.split("/");
        qZone.addTask(new QZone.Task.RepostTask(split[split.length - 1], message));
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

        public QQAccount() {
            super.setPlatform("QQ");
        }

        @Override
        public void setId(String id) {
            super.setId(id);
            try {
                setAvatar(new ImageField(new URL("https://q.qlogo.cn/headimg_dl?dst_uin=" + id + "&spec=640&img_type=jpg")));
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public class QQGroup extends Group {
        public QQGroup() {
            super.setPlatform("QQ");
        }

        public void setId(String id) {
            super.setId(id);
            try {
                super.setAvatar(new ImageField(new URL("https://p.qlogo.cn/gh/" + id + "/" + id + "/0")));
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        }

        public void administration_setName(String name) {
            setSessionName("group/" + getId(), name);
        }
    }

    /*
     使用Selenium访问和简单操作QZone
     由于Selenium和浏览器进程占用资源过多，请及时释放实例
     建议搭配try with resources使用
     */
    public class QZone {
        List<String> availablePostKeys = new ArrayList<>();
        ChromeDriver driver;
        private volatile boolean workerRunning = false;
        final BlockingDeque<Task> tasks = new LinkedBlockingDeque<>(); // 使用队列机制逐个执行任务

        public static class Task {
            enum Type {
                fetchPosts, sendLike, sendComment, repost
            }

            Type type;

            public static class FetchPosts extends Task {
                public FetchPosts() {
                    type = Type.fetchPosts;
                }
            }

            public static class SendLikeTask extends Task {
                String postKey;

                public SendLikeTask(String postKey) {
                    type = Type.sendLike;
                    this.postKey = postKey;
                }
            }

            public static class SendCommentTask extends Task {
                String postKey;
                MessageChain comment;

                public SendCommentTask(String postKey, MessageChain comment) {
                    type = Type.sendComment;
                    this.postKey = postKey;
                    this.comment = comment;
                }
            }

            public static class RepostTask extends Task {
                String postKey;
                MessageChain repostMsg;

                public RepostTask(String postKey, MessageChain repostMsg) {
                    type = Type.repost;
                    this.postKey = postKey;
                    this.repostMsg = repostMsg;
                }
            }
        }

        public void addTask(Task task) {
            synchronized (tasks) {
                tasks.addLast(task);

                // 如果没有 worker 在跑，就启动一个
                if (!workerRunning) {
                    workerRunning = true;
                    init();
                    new Thread(this::process).start();
                }
            }
        }

        // 此处通过请求Onebot API get_cookies 获取cookies初始化会话
        public void init() {
            String driverPath = System.getProperty("webdriver.chrome.driver");
            if (driverPath == null || driverPath.isBlank()) {
                String envDriverPath = System.getenv("CHROMEDRIVER_PATH");
                if (envDriverPath != null && !envDriverPath.isBlank()) {
                    System.setProperty("webdriver.chrome.driver", envDriverPath);
                }
            }
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
                driver.get("https://qzone.qq.com/"); // 然后刷新
                // 至此这个模块初始化完毕可以用了
            } catch (NullPointerException e) {
                throw new RuntimeException("Failed to initialize QZone instance", e);
            }
        }

        public void process() {
            Task task;
            while (true) {
                while ((task = tasks.pollFirst()) != null) {
                    if (task instanceof Task.FetchPosts) {
                        fetchLatestPosts();
                    } else if (task instanceof Task.SendCommentTask sendCommentTask) {
                        sendComment(sendCommentTask.postKey, sendCommentTask.comment);
                    } else if (task instanceof Task.SendLikeTask sendLikeTask) {
                        sendLike(sendLikeTask.postKey);
                    } else if (task instanceof Task.RepostTask repostTask) {
                        repost(repostTask.postKey, repostTask.repostMsg);
                    }
                    try {
                        Thread.sleep(2000 + new Random().nextInt(1000));
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
                for (int i = 0; i <= 600; i++) { // 等待新任务如果没有就退出了
                    if (!tasks.isEmpty()) break;
                    else if (i == 600) {
                        release();
                        return;
                    }
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }

        public void fetchLatestPosts() {
            driver.get("https://qzone.qq.com/");
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));
            wait.until(
                    ExpectedConditions.elementToBeClickable(By.id("tab_menu_friend"))
            ).click(); // 点进好友动态的页面
            wait.until(ExpectedConditions.invisibilityOfElementLocated(
                    By.className("feed-fn-loading")
            )); // 等待页面加载
            // 好了加载完了
            List<WebElement> list = driver.findElements(
                    By.cssSelector("#feed_friend_list li.f-single.f-s-s:not(.f-single-biz)")
            ); // 所有的好友动态容器
            for (WebElement item : list) {
                try {
                    String key;
                    MCPost post = new MCPost();
                    @SuppressWarnings("DataFlowIssue") // 前面列表列出来的怎么可能是null
                    Document doc = org.jsoup.Jsoup.parse(item.getAttribute("outerHTML"));
                    String poster_id = doc.getElementsByClass("f-name q_namecard ").get(0).attr("link").split("_")[1]; // QQ号
                    QQAccount poster;
                    try {
                        poster = (QQAccount) getSession("user/" + poster_id);
                    } catch (Exception e) { // 一般直接用统一的获取用户信息的方法，但是如果出问题就fallback到直接填充
                        poster = new QQAccount();
                        poster.setId(poster_id);
                        poster.setName(doc.getElementsByClass("f-name q_namecard ").get(0).text());
                    }
                    post.poster = poster;
                    post.timestamp = Long.parseLong(doc.selectFirst("[name=feed_data]").attr("data-abstime"));
                    // 正文
                    Element div = doc.selectFirst(".f-info");
                    div.select("br").append("\\n"); // 直接转换的话换行会丢失，所以这里用\n代表换行，也就是说这里其实可以被原有的\n注入，不过不想管
                    String text = div.text().replace("\\n", "\n");
                    post.messageFields.add(new TextField(text));
                    // 附图
                    Element img_box = doc.selectFirst(".img-box");
                    if (img_box != null) for (Element a : img_box.getElementsByTag("a")) {
                        String[] split = a.attr("data-pickey").split(",", 2);
                        String url = split[1];
                        try {
                            post.messageFields.add(new ImageField(new URL(url)));
                        } catch (MalformedURLException e) {
                            logger.error("无法实例化URL: {}", url, e);
                        }
                    }
                    // 基本信息
                    Element data_ele = doc.selectFirst(".qz_summary i.none");
                    key = data_ele.attr("data-tid");
                    availablePostKeys.add(key);
                    // 点赞列表
                    Element likes_ele = doc.selectFirst(".user-list");
                    if (likes_ele != null) {
                        for (Element li : likes_ele.getElementsByTag("a")) {
                            Matcher href = Pattern.compile("(?<=/)\\d+$").matcher(li.attr("href"));
                            href.find();
                            String liker_id = href.group();
                            String name = li.text();
                            if (liker_id.equals(accountId)) name = name.substring(0, name.length() - 1); // 删掉末尾的“、”
                            QQAccount liker = new QQAccount(); // 这里直接用原地就有的信息，防风控
                            liker.setId(liker_id);
                            liker.setName(name);
                            post.likers.add(liker);
                        }
                        Element countEle = likes_ele.selectFirst(".f-like-cnt");
                        if (countEle == null) post.likeCount = 0;
                        else {
                            String countEleText = countEle.text();
                            if (text.isBlank()) post.likeCount = 0;
                            else {
                                String digits = countEleText.replaceAll("\\D+", "");
                                if (digits.isEmpty()) post.likeCount = 0;
                                try {
                                    post.likeCount = Integer.parseInt(digits);
                                } catch (NumberFormatException e) {
                                    logger.warn("Failed to parse like count: {}", text, e);
                                    post.likeCount = 0;
                                }
                            }
                        }
                    } else logger.debug("likes_ele is null");
                    // 评论列表
                    Element comments_list_ele = doc.selectFirst(".comments-list ");
                    if (comments_list_ele != null) for (Element li : comments_list_ele.getElementsByTag("li")) {
                        MCMessage comment = new MCMessage();
                        Element comment_content = li.selectFirst(".comments-content");
                        comment_content.select(".comments-op").remove();
                        comment_content.select(".nickname").remove();
                        String nickname = li.attr("data-nick");
                        String uin = li.attr("data-uin");
                        String content = comment_content.text().substring(1);
                        if (content.startsWith(" ")) content = content.substring(1); // 如果还有空格得再裁一下
                        QQAccount commentSender = new QQAccount();
                        commentSender.setName(nickname);
                        commentSender.setId(uin);
                        comment.sender = commentSender;
                        comment.messageFields.add(new TextField(content));
                        Element img_r = comment_content.selectFirst(".comments-thumbnails"); // 评论的附图
                        if (img_r != null) for (Element ele : img_r.getElementsByTag("img")) {
                            try {
                                comment.messageFields.add(new ImageField(new URL(ele.attr("src"))));
                            } catch (MalformedURLException e) {
                                throw new RuntimeException(e);
                            }
                        }
                        post.replies.add(comment);
                    }
                    // 广播到宇宙
                    broadcastMessage("post/" + post.poster.getId() + "/" + key, post);
                } catch (Throwable e) {
                    logger.error("Failed to prase", e);
                    logger.error(item.getAttribute("outerHTML"));
                }
            }
        }

        public void sendLike(String key) {
            // 通过模拟点击按钮点赞
            WebElement btn = driver.findElement(By.cssSelector(
                    "div.f-item[data-key='" + key + "'] .qz_like_btn_v3"
            ));

            driver.executeScript(
                    "arguments[0].scrollIntoView({block: 'center'});", btn
            );

            // 用 Actions 模拟点击
            new Actions(driver)
                    .moveToElement(btn)
                    .pause(Duration.ofMillis(100))
                    .click()
                    .perform();
        }

        public void sendComment(String key, MessageChain comment) {
            // 找到正文块（有 data-key）
            WebElement data_ele = driver.findElement(By.cssSelector(
                    "div.f-item.f-s-i[data-key='" + key + "']"
            ));

            // 往上找到 li（整条动态）
            WebElement li = data_ele.findElement(By.xpath("./ancestor::li"));

            // 在这个 li 里找评论输入框
            WebElement textInput = li.findElement(By.cssSelector(
                    ".textinput[contenteditable='true']"
            ));

            // 点击输入框
            textInput.click();

            // 输入评论内容
            textInput.sendKeys(comment.toString());

            // 发送（Ctrl + Enter）
            textInput.sendKeys(Keys.CONTROL, Keys.ENTER);
        }

        public void repost(String key, MessageChain repostMsg) {
            // 找到正文块（有 data-key）
            WebElement data_ele = driver.findElement(By.cssSelector(
                    "div.f-item.f-s-i[data-key='" + key + "']"
            ));

            // 往上找到 li（整条动态）
            WebElement li = data_ele.findElement(By.xpath("./ancestor::li"));

            // 在这个 li 里找评论输入框
            WebElement repostBtn = li.findElement(By.cssSelector(
                    ".textinput[contenteditable='true']"
            ));

            // 点击输入框
            repostBtn.click();

            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
            WebElement textInput = wait.until(
                    ExpectedConditions.elementToBeClickable(By.cssSelector(".qz_dialog_layer_main div.textinput.textarea.c_tx2[contenteditable=\\\"true\\\"]"))
            );
            textInput.sendKeys(repostMsg.toString()); // 只支持单文字
            textInput.sendKeys(Keys.CONTROL, Keys.ENTER);
        }

        public void release() {
            availablePostKeys.clear();
            if (driver != null) {
                driver.quit();
                driver = null;
            }
        }
    }
}

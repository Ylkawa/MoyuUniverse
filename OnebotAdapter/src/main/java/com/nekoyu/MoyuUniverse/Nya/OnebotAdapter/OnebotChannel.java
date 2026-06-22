package com.nekoyu.MoyuUniverse.Nya.OnebotAdapter;

import com.google.gson.*;
import com.google.gson.typeadapters.RuntimeTypeAdapterFactory;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.MsgFields.Image;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.MsgFields.Text;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.message.JsonMessages.JsonMessage;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.message.JsonMessages.com_tencent_miniapp_01;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.message.JsonMessages.com_tencent_miniapp_lua;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.message.JsonMessages.com_tencent_tuwen_lua;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.message.Message;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.MsgFields.MessageSegment;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.meta_event.Meta_Event;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.notice.Notice;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.notice.FriendRecall;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.notice.GroupRecall;
import com.nekoyu.Universe.API.MessageChannel.*;
import com.nekoyu.Universe.API.MessageChannel.Features.Administration;
import com.nekoyu.Universe.API.MessageChannel.Features.SessionManagement;
import com.nekoyu.Universe.API.MessageChannel.Features.PostChat;
import com.nekoyu.Universe.API.MessageChannel.Features.SessionChat;
import com.nekoyu.Universe.API.MessageChannel.MessageField.*;
import com.nekoyu.Universe.API.MessageChannel.MessageField.TextField;
import com.nekoyu.Universe.API.MessageChannel.events.AddGroupRequest;
import com.nekoyu.Universe.API.MessageChannel.events.AddFriendRequest;
import com.nekoyu.Universe.API.MessageChannel.events.InviteGroupRequest;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OnebotChannel extends MessageChannel implements SessionChat, PostChat, Administration, SessionManagement {
    static final Gson gson;
    private static final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

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
    static Logger logger = LoggerFactory.getLogger(OnebotChannel.class);
    URI uri;
    String token;
    boolean enableQZone;
    URL remoteWebDriverURL = null;
    Map<String, Callback> syncActions = new ConcurrentHashMap<>(); // Echoes 和 Actions 的映射
    Map<String, Session> cachedSessions = new ConcurrentHashMap<>(); // 用户账号列表缓存
    boolean good = true; // 实现端健康状态
    boolean online = true; // 实现端在线状态
    Set<Long> blockedUsers;
    Set<Long> degradedUsers;
    QZone qZone = null;

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
            if (field instanceof StickerField stickerField) {
                Image e = new Image(stickerField.getUrl().toString());
                e.data.put("sub_type", 1);
                obMsg.add(e);
            } else if (field instanceof ImageField imgF) {
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
                    loginAccount = (QQAccount) getSession("user/" + accountId);
                    mainColor = loginAccount.getColor();
                    logger.info("{} 登录的 QQ号 为 {} ({}), {} 个好友  {} 个群聊", ID, nickname, accountId, friendCount, groupCount);
                });

                if (enableQZone) qZone = new QZone();
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
                                    if (blockedUsers.contains(message.sender.user_id)) return; // 不理会屏蔽的用户
                                    MCMessage mcm = new MCMessage();
                                    // 标注消息的基本信息
                                    mcm.time = message.time;
                                    mcm.receiver = loginAccount;
                                    mcm.sender = (Account) getSession("user/" + message.sender.user_id);
                                    mcm.id = message.message_id;
                                    StringBuilder sessionId = new StringBuilder();
                                    sessionId.append(message.message_type).append("/");
                                    for (MessageSegment ms : message.message) {
                                        switch (ms.type) {
                                            case "text" -> {
                                                mcm.messageFields.add(new TextField((String) ms.data.get("text")));
                                            }
                                            case "face" -> {
                                                mcm.messageFields.add(new TextField("[QQ表情]"));
                                            }
                                            // 暂时没看到有能和emoji一一对应的表格，先不管
                                            case "image" -> {
                                                if (ms.data.get("sub_type") != null && (double) ms.data.get("sub_type") == 1) {
                                                    try {
                                                        StickerField stickerField = new StickerField(new URL((String) ms.data.get("url")));
                                                        mcm.messageFields.add(stickerField);
                                                    } catch (MalformedURLException e) {
                                                        logger.error("无法以 {} 创建URL对象", ms.data.get("url"), e);
                                                        mcm.messageFields.add(new TextField("[动画表情]"));
                                                    }
                                                } else {
                                                    try {
                                                        ImageField imageField = new ImageField(new URL((String) ms.data.get("url")));
                                                        mcm.messageFields.add(imageField);
                                                    } catch (MalformedURLException e) {
                                                        logger.error("无法以 {} 创建URL对象", ms.data.get("url"), e);
                                                        mcm.messageFields.add(new TextField("[图片]"));
                                                    }
                                                }
                                            }
                                            // 放不进去文本，先这样
                                            case "record" -> {
                                                try {
                                                    VoiceField voiceField = new VoiceField(new URL((String) ms.data.get("url")));
                                                    mcm.messageFields.add(voiceField);
                                                } catch (MalformedURLException e) {
                                                    logger.error("无法以 {} 创建URL对象", ms.data.get("url"), e);
                                                    mcm.messageFields.add(new TextField("[语音]"));
                                                }
                                            }
                                            case "video" -> {
                                                try {
                                                    VideoField videoField = new VideoField(new URL((String) ms.data.get("url")));
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
                                                    mcm.messageFields.add(new ShareUrlField((String) ms.data.get("title"), new URL((String) ms.data.get("url"))));
                                                } catch (MalformedURLException e) {
                                                    logger.error("无法以 {} 创建URL对象", ms.data.get("url"), e);
                                                    mcm.messageFields.add(new TextField("[分享链接]"));
                                                }
                                            }
                                            case "contact" -> {
                                                switch ((String) ms.data.get("type")) {
                                                    case "qq":
                                                        mcm.messageFields.add(new ShareContactField("QQ", "group/" + ms.data.get("id")));
                                                        break;
                                                    case "group":
                                                        mcm.messageFields.add(new ShareContactField("QQ", "private/" + ms.data.get("id")));
                                                        break;
                                                }
                                            }
                                            case "location" -> {
                                                mcm.messageFields.add(new LocationField(Double.parseDouble((String) ms.data.get("lat")), Double.parseDouble((String) ms.data.get("lon"))));
                                            }
                                            case "music" -> {
                                                switch ((String) ms.data.get("type")) {
                                                    case "163" -> {
                                                        try {
                                                            mcm.messageFields.add(new ShareUrlField("网易云音乐分享", new URL("https://music.163.com/#/song?id=" + ms.data.get("id"))));
                                                        } catch (MalformedURLException e) {
                                                            mcm.messageFields.add(new TextField("[网易云音乐分享]"));
                                                        }
                                                    }
                                                    case "qq" -> {
                                                        try {
                                                            mcm.messageFields.add(new ShareUrlField("QQ音乐分享", new URL("https://y.qq.com/n/ryqq/songDetail/" + ms.data.get("id"))));
                                                        } catch (MalformedURLException e) {
                                                            mcm.messageFields.add(new TextField("[QQ音乐分享]"));
                                                        }
                                                    }
                                                    case "xm" -> {
                                                        mcm.messageFields.add(new TextField("[虾米音乐分享]"));
                                                    }
                                                    case "custom" -> {
                                                        try {
                                                            mcm.messageFields.add(new ShareUrlField("音乐分享", new URL((String) ms.data.get("url"))));
                                                        } catch (MalformedURLException e) {
                                                            logger.error("无法以 {} 创建URL对象", ms.data.get("url"), e);
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
                                                logger.debug((String) ms.data.get("data"));
                                            }
                                            case "json" -> {
                                                try {
                                                    JsonMessage jm = gson.fromJson((String) ms.data.get("data"), JsonMessage.class);
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
                                                    JsonElement je = gson.fromJson((String) ms.data.get("data"), JsonElement.class);
                                                    mcm.messageFields.add(new MetaField(je.getAsJsonObject().get("prompt").getAsString()));
                                                    logger.debug((String) ms.data.get("data"), e);
                                                }
                                            }
                                        }
                                    }
                                    // 给消息定级
                                    for (MessageSegment seg : message.message) {
                                        if (!degradedUsers.contains(message.user_id) && seg.type.equals("at") && seg.data.get("qq").equals(String.valueOf(accountId))) {
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
                                    if (meta_event.sub_type != null && meta_event.sub_type.equals("heartbeat")) { // 解析心跳包
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
                                            MessageChannelManager.onMessageRecall(ID + ":group/" + groupRecall.group_id, groupRecall.message_id);
                                        }
                                        case "friend_recall" -> {
                                            FriendRecall friendRecall = gson.fromJson(s, FriendRecall.class);
                                            MessageChannelManager.onMessageRecall(ID + ":private/" + friendRecall.user_id, friendRecall.message_id);
                                        }
                                    }
                                }
                                case "request" -> {
                                    var request = gson.fromJson(s, com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.request.Request.class);
                                    switch (request.request_type) {
                                        case "friend" -> { // 加好友请求
                                            AddFriendRequest addFriendRequest = new AddFriendRequest();
                                            addFriendRequest.requestor = (Account) getSession("user/" + request.user_id);
                                            addFriendRequest.target = loginAccount;
                                            addFriendRequest.commit = request.comment;
                                            String eventId = "addFriend/" + URLEncoder.encode(request.flag, StandardCharsets.UTF_8);
                                            broadcastEvent(eventId, addFriendRequest);
                                        }
                                        case "group" -> {
                                            if (request.sub_type.equals("add")) { // 入群请求
                                                AddGroupRequest addGroupRequest = new AddGroupRequest();
                                                addGroupRequest.requestor = (Account) getSession("user/" + request.user_id);
                                                addGroupRequest.target = (Group) getSession("group/" + request.group_id);
                                                addGroupRequest.commit = request.comment;
                                                String eventId = "addGroup/" + URLEncoder.encode(request.flag, StandardCharsets.UTF_8);
                                                broadcastEvent(eventId, addGroupRequest);
                                            } else if (request.sub_type.equals("invite")) { // 邀请入群
                                                InviteGroupRequest inviteGroupRequest = new InviteGroupRequest();
                                                inviteGroupRequest.requestor = (Account) getSession("user/" + request.user_id);
                                                inviteGroupRequest.target = (Group) getSession("group/" + request.group_id);
                                                inviteGroupRequest.commit = request.comment;
                                                String eventId = "inviteGroup/" + URLEncoder.encode(request.flag, StandardCharsets.UTF_8);
                                                broadcastEvent(eventId, inviteGroupRequest);
                                            }
                                        }
                                    }
                                }
                                default -> {
                                    return;
                                }
                            }
                        } catch (Throwable e) {
                            logger.error(e.getMessage(), e);
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
                scheduler.schedule(() -> reload(), 10, TimeUnit.SECONDS);
            }

            @Override
            public void onError(Exception e) {

            }
        };
        wsConnection.connect();
        MessageChannelManager.registerChannel(this.ID, this);
    }

    @Override
    public void stop() {
        isReady = false;
        wsConnection.close();
        if (qZone != null) qZone.release();
    }

    @Override
    public int sendMessage(String sessionId, MFChain message) {
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
        if (qZone != null) qZone.addTask(new QZone.Task.SendLikeTask(split[split.length - 1]));
    }

    @Override
    public void replyPost(String sessionId, MFChain message) {
        String[] split = sessionId.split("/");
        if (qZone != null) qZone.addTask(new QZone.Task.SendCommentTask(split[split.length - 1], message));
    }

    @Override
    public void repost(String sessionId, MFChain message) {
        String[] split = sessionId.split("/");
        if (qZone != null) qZone.addTask(new QZone.Task.RepostTask(split[split.length - 1], message));
    }

    @Override
    public void approvalAddGroup(AddGroupRequest request, boolean agree, String reason) {
        OBRequest obr = new OBRequest("set_group_add_request");
        obr.params.put("approve", agree);
        obr.params.put("flag", URLDecoder.decode(request.eventId.split("/")[1], StandardCharsets.UTF_8));
        obr.params.put("reason", reason);
        obr.params.put("sub_type", "add");
    }

    @Override
    public void approvalAddFriend(AddFriendRequest request, boolean agree) {
        OBRequest obr = new OBRequest("set_friend_add_request");
        obr.params.put("approve", agree);
        obr.params.put("flag", URLDecoder.decode(request.eventId.split("/")[1], StandardCharsets.UTF_8));
    }

    @Override
    public void approvalInviteGroup(InviteGroupRequest request, boolean agree) {
        OBRequest obr = new OBRequest("set_group_add_request");
        obr.params.put("approve", agree);
        obr.params.put("flag", URLDecoder.decode(request.eventId.split("/")[1], StandardCharsets.UTF_8));
        obr.params.put("sub_type", "invite");
    }

    private interface Callback {
        void callback(OBResponse response);
    }

    public class QQAccount extends Account {
        public QQAccount() {
            super.setPlatform("QQ");
        }

        public void sendLike(int count) { // Max 10 as default and could be 20 with SVIP
            OBRequest obr = new OBRequest("send_like");
            obr.params.put("user_id", getId());
            obr.params.put("times", count);
            request(obr);
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
        public static final Random RANDOM = new Random();
        final BlockingDeque<Task> tasks = new LinkedBlockingDeque<>(); // 使用队列机制逐个执行任务
        List<String> availablePostKeys = new ArrayList<>();
        WebDriver driver;
        private volatile boolean workerRunning = false;
        private long lastFetch = System.currentTimeMillis();

        public QZone() {
            Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
                if (online && tasks.isEmpty()) {
                    addTask(new Task.FetchPosts());
                }
            }, 20, 60 * 60, TimeUnit.SECONDS);
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
            logger.debug("create chrome instance");
            ChromeOptions options = new ChromeOptions();
            options.addArguments("--start-maximized");

            try {
                if (remoteWebDriverURL != null) {
                    driver = new RemoteWebDriver(
                            remoteWebDriverURL,
                            options
                    );
                } else driver = new ChromeDriver(options);
                logger.debug("get driver");
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
            OBRequest obr = new OBRequest("get_cookies");
            obr.params.put("domain", "qzone.qq.com");
            try {
                var resp = request(obr);
                String cookiesString = resp.data.getAsJsonObject().get("cookies").getAsString();
//                String bkn = resp.data.getAsJsonObject().get("bkn").getAsString(); // 不知道有什么用
                driver.get("https://qzone.qq.com/"); // 先打开这个页面
                logger.debug("open qzone");
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
                // 至此这个模块初始化完毕可以用了
                logger.debug("instance created");
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
                        Thread.sleep(2000 + RANDOM.nextInt(1000));
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
                for (int i = 0; i <= 240; i++) { // 等待新任务如果没有就退出了
                    if (!tasks.isEmpty()) break;
                    else if (i == 240) {
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
            int newPosts = 0;

            logger.debug("begin fetching latest posts");
            driver.get("https://qzone.qq.com/");
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));
            wait.until(
                    ExpectedConditions.elementToBeClickable(By.id("tab_menu_friend"))
            ).click();
            wait.until(ExpectedConditions.invisibilityOfElementLocated(
                    By.className("feed-fn-loading")
            ));
            logger.debug("page loaded");

            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            List<WebElement> list = driver.findElements(
                    By.cssSelector("#feed_friend_list li.f-single.f-s-s:not(.f-single-biz)")
            );
            int numOfPost = list.size();
            boolean directExitLoop = false;

            if (list.isEmpty()) {
                logger.warn("未加载出任何动态");
                return;
            }

            // ===== 安全翻页 =====
            while (true) {
                WebElement last = list.get(list.size() - 1);

                WebElement feedData;
                try {
                    feedData = last.findElement(By.cssSelector("[name=feed_data]"));
                } catch (Exception e) {
                    logger.warn("最后一条动态没有 feed_data，停止翻页");
                    break;
                }

                String abstimeStr = feedData.getAttribute("data-abstime");
                if (abstimeStr == null || abstimeStr.isBlank()) {
                    logger.warn("data-abstime 为空，停止翻页");
                    break;
                }

                long abstime;
                try {
                    abstime = Long.parseLong(abstimeStr);
                } catch (NumberFormatException e) {
                    logger.warn("data-abstime 非法: {}", abstimeStr);
                    break;
                }

                if (abstime * 1000 <= lastFetch) break;

                logger.debug("rolling page to get more posts");
                ((JavascriptExecutor) driver).executeScript("window.scrollTo(0, document.body.scrollHeight);");

                boolean loaded = false;
                for (int i = 0; i < 5; i++) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }

                    List<WebElement> newList = driver.findElements(
                            By.cssSelector("#feed_friend_list li.f-single.f-s-s:not(.f-single-biz)")
                    );

                    if (newList.size() != numOfPost) {
                        list = newList;
                        numOfPost = list.size();
                        loaded = true;
                        break;
                    }

                    if (i == 4) {
                        logger.debug("unable to roll page");
                        directExitLoop = true;
                    }
                }

                if (!loaded || directExitLoop) break;
            }

            // ===== 展开全文 =====
            for (WebElement item : driver.findElements(By.cssSelector("div.f-info.qz_info_cut > a[data-cmd=qz_toggle]"))) {
                ((JavascriptExecutor) driver).executeScript(
                        "arguments[0].scrollIntoView({block: 'center'});", item
                );
                try {
                    item.click();
                } catch (Exception ignored) {
                }

                logger.debug("unfolded a post");

                try {
                    Thread.sleep(4000 + RANDOM.nextInt(2000));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }

            logger.debug("start to fetch");

            for (WebElement item : list) {
                try {
                    Document doc = org.jsoup.Jsoup.parse(item.getAttribute("outerHTML"));

                    Element feedData = doc.selectFirst("[name=feed_data]");
                    if (feedData == null) {
                        logger.warn("跳过：没有 feed_data");
                        continue;
                    }

                    String abstimeStr = feedData.attr("data-abstime");
                    if (abstimeStr == null || abstimeStr.isBlank()) {
                        logger.warn("跳过：data-abstime 为空");
                        continue;
                    }

                    long timestamp;
                    try {
                        timestamp = Long.parseLong(abstimeStr);
                    } catch (NumberFormatException e) {
                        logger.warn("跳过：非法 abstime {}", abstimeStr);
                        continue;
                    }

                    if (timestamp * 1000 < lastFetch) {
                        lastFetch = System.currentTimeMillis();
                        logger.debug("fetched all {} new post", newPosts);
                        return;
                    }

                    String key;
                    MCPost post = new MCPost();

                    Element nameEle = doc.getElementsByClass("f-name q_namecard ").first();
                    if (nameEle == null) {
                        logger.warn("跳过：没有用户信息");
                        continue;
                    }

                    String link = nameEle.attr("link");
                    if (!link.contains("_")) {
                        logger.warn("跳过：link异常 {}", link);
                        continue;
                    }

                    String poster_id = link.split("_")[1];

                    QQAccount poster;
                    try {
                        poster = (QQAccount) getSession("user/" + poster_id);
                    } catch (Exception e) {
                        poster = new QQAccount();
                        poster.setId(poster_id);
                        poster.setName(nameEle.text());
                    }

                    post.poster = poster;
                    post.timestamp = timestamp;

                    // ===== 正文 =====
                    Element div = doc.selectFirst(".f-info");
                    if (div != null) {
                        div.select("br").append("\\n");
                        String text = div.text().replace("\\n", "\n");
                        post.messageFields.add(new TextField(text));
                    }

                    // ===== 图片 =====
                    Element img_box = doc.selectFirst(".img-box");
                    if (img_box != null) {
                        List<ImageField> imgs = new ArrayList<>();
                        for (Element a : img_box.getElementsByTag("a")) {
                            String dataPickey = a.attr("data-pickey");
                            if (dataPickey == null || !dataPickey.contains(",")) continue;

                            String[] split = dataPickey.split(",", 2);
                            if (split.length < 2) continue;

                            try {
                                imgs.add(new ImageField(new URL(split[1])));
                            } catch (MalformedURLException e) {
                                for (Element img : img_box.getElementsByTag("img")) {
                                    try {
                                        imgs.add(new ImageField(new URL(img.attr("src"))));
                                    } catch (Exception ignored) {
                                    }
                                }
                            }
                        }
                        post.messageFields.addAll(imgs);
                    }

                    // ===== 基本信息 =====
                    Element data_ele = doc.selectFirst(".qz_summary i.none");
                    if (data_ele == null) {
                        logger.warn("跳过：没有 data_ele");
                        continue;
                    }

                    key = data_ele.attr("data-tid");
                    if (key == null || key.isBlank()) {
                        logger.warn("跳过：data-tid 为空");
                        continue;
                    }

                    availablePostKeys.add(key);

                    // ===== 点赞 =====
                    Element likes_ele = doc.selectFirst(".user-list");
                    if (likes_ele != null) {
                        for (Element li : likes_ele.getElementsByTag("a")) {
                            Matcher href = Pattern.compile("(?<=/)\\d+$").matcher(li.attr("href"));
                            if (!href.find()) continue;

                            String liker_id = href.group();
                            String name = li.text();

                            if (liker_id.equals(accountId) && name.length() > 0) {
                                name = name.substring(0, name.length() - 1);
                            }

                            QQAccount liker = new QQAccount();
                            liker.setId(liker_id);
                            liker.setName(name);
                            post.likers.add(liker);
                        }

                        Element countEle = likes_ele.selectFirst(".f-like-cnt");
                        if (countEle == null) post.likeCount = 0;
                        else {
                            String digits = countEle.text().replaceAll("\\D+", "");
                            if (digits.isEmpty()) post.likeCount = 0;
                            else {
                                try {
                                    post.likeCount = Integer.parseInt(digits);
                                } catch (NumberFormatException e) {
                                    post.likeCount = 0;
                                }
                            }
                        }
                    }

                    // ===== 评论 =====
                    Element comments_list_ele = doc.selectFirst(".comments-list");
                    if (comments_list_ele != null) {
                        for (Element li : comments_list_ele.getElementsByTag("li")) {
                            Element comment_content = li.selectFirst(".comments-content");
                            if (comment_content == null) continue;

                            comment_content.select(".comments-op").remove();
                            comment_content.select(".nickname").remove();

                            String content = comment_content.text();
                            if (content.length() > 0) content = content.substring(1).trim();

                            MCMessage comment = new MCMessage();

                            QQAccount commentSender = new QQAccount();
                            commentSender.setName(li.attr("data-nick"));
                            commentSender.setId(li.attr("data-uin"));

                            comment.sender = commentSender;
                            comment.messageFields.add(new TextField(content));

                            Element img_r = comment_content.selectFirst(".comments-thumbnails");
                            if (img_r != null) {
                                for (Element ele : img_r.getElementsByTag("img")) {
                                    try {
                                        comment.messageFields.add(new ImageField(new URL(ele.attr("src"))));
                                    } catch (Exception ignored) {
                                    }
                                }
                            }

                            post.replies.add(comment);
                        }
                    }

                    broadcastMessage("post/" + post.poster.getId() + "/" + key, post);
                    newPosts++;

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

            if (Objects.equals(btn.getAttribute("data-islike"), "1")) return; // 点过了，直接return

            ((JavascriptExecutor) driver).executeScript(
                    "arguments[0].scrollIntoView({block: 'center'});", btn
            );

            // 用 Actions 模拟点击
            new Actions(driver)
                    .moveToElement(btn)
                    .pause(Duration.ofMillis(100))
                    .click()
                    .perform();
        }

        public void sendComment(String key, MFChain comment) {
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

        public void repost(String key, MFChain repostMsg) {
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
            workerRunning = false;
        }

        public static class Task {
            Type type;

            enum Type {
                fetchPosts, sendLike, sendComment, repost
            }

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
                MFChain comment;

                public SendCommentTask(String postKey, MFChain comment) {
                    type = Type.sendComment;
                    this.postKey = postKey;
                    this.comment = comment;
                }
            }

            public static class RepostTask extends Task {
                String postKey;
                MFChain repostMsg;

                public RepostTask(String postKey, MFChain repostMsg) {
                    type = Type.repost;
                    this.postKey = postKey;
                    this.repostMsg = repostMsg;
                }
            }
        }
    }
}

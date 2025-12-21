package com.nekoyu.MoyuUniverse.Nya.OnebotAdapter;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.Message;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.MessageSegment;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.Meta_Event;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.Notice;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.Notices.FriendRecall;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.Notices.GroupRecall;
import com.nekoyu.Universe.API.MessageChannel.*;
import com.nekoyu.Universe.API.MessageChannel.MessageField.*;
import com.nekoyu.Universe.API.MessageSession;
import com.nekoyu.Universe.Universe;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class OnebotChannel extends MessageChannel {
    WebSocketClient wsConnection;
    ExecutorService onMsgEx = Executors.newCachedThreadPool();
    ExecutorService callbackEx = Executors.newCachedThreadPool();
    boolean isReady = true;
    Logger logger = LoggerFactory.getLogger(this.getClass());
    URI uri;
    String token;
    Map<String, Callback> syncActions = new HashMap<>();
    Map<String, Account> userAccounts = new HashMap<>();

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
                        account.setNickname(friendObj.get("nickname").getAsString());
                        friendCount.getAndIncrement();
                        userAccounts.put(account.getId(), account);
                    });
                    OBResponse groupListReq = request(new OBRequest("get_group_list"));
                    int groupCount = groupListReq.data.getAsJsonArray().size();
                    JsonObject responseData = response.data.getAsJsonObject();
                    nickname = responseData.get("nickname").getAsString();
                    accountId = responseData.get("user_id").getAsString();
                    logger.info("{} 登录的 QQ号 为 {} ({}), {} 个好友  {} 个群聊", ID, nickname, accountId, friendCount.get(), groupCount);
                });
            }

            @Override
            public void onMessage(String s) {
                logger.debug(s);
                Gson gson = new Gson();
                JsonElement content = gson.fromJson(s, JsonElement.class);
                onMsgEx.submit(() -> {
                    try {
                        if (content.getAsJsonObject().get("post_type") != null) {
                            switch (content.getAsJsonObject().get("post_type").getAsString()) {
                                case "message" -> {
                                    Message message = gson.fromJson(s, Message.class);
                                    MCMessage mcm = new MCMessage();
                                    // 标注消息的基本信息
                                    mcm.time = message.time;
                                    mcm.receiver.setId(String.valueOf(message.self_id));
                                    mcm.receiver.setNickname(nickname);
                                    mcm.receiver.setPlatform("QQ");
                                    mcm.sender.setId(String.valueOf(message.sender.user_id));
                                    mcm.sender.setNickname(message.sender.nickname);
                                    mcm.sender.setPlatform("QQ");
                                    mcm.sender.setSex(message.sender.sex);
                                    mcm.id = message.message_id;
                                    StringBuilder sessionId = new StringBuilder();
                                    sessionId.append(message.message_type).append("/");
                                    StringBuilder msg = new StringBuilder();
                                    for (MessageSegment ms : message.message) {
                                        switch (ms.type) {
                                            case "text" -> {
                                                msg.append(ms.data.get("text"));
                                                mcm.messageFields.add(new TextField(ms.data.get("text")));
                                            }
                                            case "face" -> {
                                                msg.append("[QQ表情]");
                                                mcm.messageFields.add(new TextField("[QQ表情]"));
                                            }
                                            // 暂时没看到有能和emoji一一对应的表格，先不管
                                            case "image" -> {
                                                msg.append("[图片]");
                                                try {
                                                    var imageField = new ImageField(new URL(ms.data.get("url")));
                                                    mcm.messageFields.add(imageField);
                                                } catch (MalformedURLException e) {
                                                    logger.error("无法以 {} 创建URL对象", ms.data.get("file"), e);
                                                    mcm.messageFields.add(new TextField("[图片]"));
                                                }
                                            }
                                            // 放不进去文本，先这样
                                            case "record" -> {
                                                msg.append("[语音]");
                                                try {
                                                    mcm.messageFields.add(new VoiceField(new URL(ms.data.get("url"))));
                                                } catch (MalformedURLException e) {
                                                    logger.error("无法以 {} 创建URL对象", ms.data.get("url"), e);
                                                    mcm.messageFields.add(new TextField("[语音]"));
                                                }
                                            }
                                            case "video" -> {
                                                msg.append("[视频]");
                                                try {
                                                    mcm.messageFields.add(new VideoField(new URL(ms.data.get("url"))));
                                                } catch (MalformedURLException e) {
                                                    logger.error("无法以 {} 创建URL对象", ms.data.get("url"), e);
                                                    mcm.messageFields.add(new TextField("[视频]"));
                                                    // 这里发生过报错，疑似是视频消息里面，file字段本身就不是URL
                                                }
                                            }
                                            case "at" -> {
                                                msg.append("[@qq:user/").append(ms.data.get("qq")).append("]");
                                                var account = getAccount("user/" + ms.data.get("qq"));
                                                mcm.messageFields.add(new AtField(account));
                                            }
                                            case "rps" -> {
                                                msg.append("[猜拳魔法表情]");
                                                mcm.messageFields.add(new MetaField("[猜拳魔法表情]"));
                                            }
                                            case "dice" -> {
                                                msg.append("[掷骰子魔法表情]");
                                                mcm.messageFields.add(new MetaField("[掷骰子魔法表情]"));
                                            }
                                            case "shake" -> {
                                                msg.append("[窗口抖动]");
                                                mcm.messageFields.add(new MetaField("[窗口抖动]"));
                                            }
                                            case "poke" -> {
                                                msg.append("[戳一戳]");
                                                mcm.messageFields.add(new MetaField("[戳一戳]"));
                                            }
                                            case "anonymous" -> {
                                                msg.append("(匿名消息)");
                                                mcm.messageFields.add(new MetaField("(匿名消息)"));
                                            }
                                            case "share" -> {
                                                msg.append("[分享链接, ").append(ms.data.get("title")).append(" : ").append(ms.data.get("url")).append(" ]");
                                                try {
                                                    mcm.messageFields.add(new ShareUriField(ms.data.get("title"), new URI(ms.data.get("url"))));
                                                } catch (URISyntaxException e) {
                                                    logger.error("无法以 {} 创建URL对象", ms.data.get("file"), e);
                                                    mcm.messageFields.add(new TextField("[分享链接]"));
                                                }
                                            }
                                            case "contact" -> {
                                                switch (ms.data.get("type")) {
                                                    case "qq":
                                                        msg.append("[分享QQ群, ");
                                                        mcm.messageFields.add(new ShareContactField("QQ", "group/" + ms.data.get("id")));
                                                        break;
                                                    case "group":
                                                        msg.append("[分享QQ好友, ");
                                                        mcm.messageFields.add(new ShareContactField("QQ", "private/" + ms.data.get("id")));
                                                        break;
                                                }
                                                msg.append(ms.data.get("id")).append("]");
                                            }
                                            case "location" -> {
                                                msg.append("[分享一处位置, ").append("纬度").append(ms.data.get("lat")).append(", 经度").append(ms.data.get("lon"));
                                                mcm.messageFields.add(new LocationField(Double.parseDouble(ms.data.get("lat")), Double.parseDouble(ms.data.get("lon"))));
                                            }
                                            case "music" -> {
                                                switch (ms.data.get("type")) {
                                                    case "163" -> {
                                                        msg.append("[网易云音乐, ").append(ms.data.get("id")).append("]");
                                                        try {
                                                            mcm.messageFields.add(new ShareUriField("网易云音乐分享", new URI("https://music.163.com/#/song?id=" + ms.data.get("id"))));
                                                        } catch (URISyntaxException e) {
                                                            logger.error("无法以 {} 创建URL对象", ms.data.get("file"), e);
                                                            mcm.messageFields.add(new TextField("[网易云音乐分享]"));
                                                        }
                                                    }
                                                    case "qq" -> {
                                                        msg.append("[QQ音乐, ").append(ms.data.get("id")).append("]");
                                                        try {
                                                            mcm.messageFields.add(new ShareUriField("QQ音乐分享", new URI("https://y.qq.com/n/ryqq/songDetail/" + ms.data.get("id"))));
                                                        } catch (URISyntaxException e) {
                                                            logger.error("无法以 {} 创建URL对象", ms.data.get("file"), e);
                                                            mcm.messageFields.add(new TextField("[QQ音乐分享]"));
                                                        }
                                                    }
                                                    case "xm" -> {
                                                        msg.append("[虾米音乐, ").append(ms.data.get("id")).append("]");
                                                        mcm.messageFields.add(new TextField("[虾米音乐分享]"));
                                                    }
                                                    case "custom" -> {
                                                        msg.append("[音乐分享, ").append(ms.data.get("title")).append(", ").append(ms.data.get("url"));
                                                        try {
                                                            mcm.messageFields.add(new ShareUriField("音乐分享", new URI(ms.data.get("url"))));
                                                        } catch (URISyntaxException e) {
                                                            logger.error("无法以 {} 创建URL对象", ms.data.get("file"), e);
                                                            mcm.messageFields.add(new TextField("音乐分享"));
                                                        }
                                                    }
                                                }
                                            }
                                            case "reply" -> {
                                                msg.append("[回复消息 ").append(ms.data.get("id")).append("]");
                                                mcm.messageFields.add(new MetaField("回复消息" + ms.data.get("id")));
                                            }
                                            case "forward" -> {
                                                msg.append("[合并转发, ").append(ms.data.get("id")).append("]");
                                                mcm.messageFields.add(new MetaField("[合并转发]"));
                                            }
                                            case "node" -> {
                                                msg.append("[合并转发节点, ").append(ms.data.get("id")).append("]");
                                                mcm.messageFields.add(new MetaField("[合并转发节点]"));
                                            }
                                            // 合并转发自定义节点 没做
                                            case "xml" -> {
                                                msg.append("[XML消息]");
                                                mcm.messageFields.add(new MetaField("[XML消息]"));
                                            }
                                            case "json" -> {
                                                msg.append("[JSON消息]");
                                                mcm.messageFields.add(new MetaField("[JSON消息]"));
                                            }
                                        }
                                    }
                                    mcm.messageString = msg.toString();
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
                                            break;
                                        case "private":
                                            Long userId = message.user_id;
                                            sessionId.append(userId);
                                            break;
                                    }
                                    broadcastMessage(sessionId.toString(), mcm);
                                }
                                case "meta_event" -> {
                                    Meta_Event meta_event = gson.fromJson(s, Meta_Event.class);
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
                        }
                    } catch (JsonSyntaxException ignored) {

                    }
                });
                if (content.getAsJsonObject().get("echo") != null) {
                    OBResponse response = gson.fromJson(content, OBResponse.class);
                    if (!response.echo.isEmpty()) {
                        callbackEx.submit(() -> {
                            syncActions.get(response.echo).callback(response);
                            syncActions.remove(response.echo);
                        });
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
        String[] target = sessionId.split("\\/");
        switch (target[0]) {
            case "group":
                return sendGroupMessage(target[1], message);
            case "private":
                return sendPrivateMessage(target[1], message);
            default:
                logger.warn("前所未闻的会话ID: {}", sessionId);
                return -1;
        }
    }

    /**
     *
     * @param sessionId group/*******
     * @param name
     * @throws UnsupportedAction
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
     * @param obr OnebotRequest
     * @param callback 回调函数
     */
    private void syncRequest(OBRequest obr, Callback callback) {
        if (!wsConnection.isOpen()) return;
        UUID uuid = UUID.randomUUID();
        obr.echo = uuid.toString();
        syncActions.put(obr.echo, callback);
        wsConnection.send(new Gson().toJson(obr));
    }

    private interface Callback {
        void callback(OBResponse response);
    }

    @Override
    public Account getAccount(String sessionId) {
        String[] acc = sessionId.split("/", 2);
        if (!acc[0].equals("private") && !acc[0].equals("user")) {
            logger.error("getAccount() cannot handle {}", sessionId);
            throw new UnsupportedAction("Only support user account");
        }
        Account account = userAccounts.get(acc[1]);
        if (account == null) {
            OBRequest obr = new OBRequest();
            obr.action = "get_stranger_info";
            obr.params.put("user_id", acc[1]);
            OBResponse resp = request(obr);
            if (resp.status.equals("failed")) throw new UnsupportedAction("Request Failed");
            account = new Account();
            account.setNickname(resp.data.getAsJsonObject().get("nickname").getAsString());
            account.setId(acc[1]);
            account.setPlatform("QQ");
            account.setSex(resp.data.getAsJsonObject().get("sex").getAsString());
            userAccounts.put(acc[1], account);
        }
        return account;
    }
}
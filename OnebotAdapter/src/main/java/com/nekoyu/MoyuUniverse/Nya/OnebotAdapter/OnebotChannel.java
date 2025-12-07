package com.nekoyu.MoyuUniverse.Nya.OnebotAdapter;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.Message;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.MessageSegment;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.Meta_Event;
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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class OnebotChannel extends MessageChannel {
    WebSocketClient wsConnection;
    boolean isReady = true;
    Logger logger = LoggerFactory.getLogger(this.getClass());
    URI uri;
    String token;
    Map<String, Callback> syncActions = new HashMap<>();

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

        // 按道理这里得返回消息ID，有抽风的情况就先取消了
        action(obr);

        return -1;
    }

    @Override
    public void load() {
        Map<String, String> header = new HashMap<>();
        header.put("Authorization", "Bearer " + token);
        wsConnection = new WebSocketClient(uri, header) {
            @Override
            public void onOpen(ServerHandshake serverHandshake) {
                logger.info("{} 连接成功", ID);

                syncAction(new OBRequest("get_login_info"), response -> {
                    JsonObject responseData = response.data.getAsJsonObject();
                    nickname = responseData.get("nickname").getAsString();
                    accountId = responseData.get("user_id").getAsString();
                    logger.info("{} 登录的 QQ号 为 {} ({})", ID, nickname, accountId);
                });
            }

            @Override
            public void onMessage(String s) {
                try {
                    Gson gson = new Gson();
                    JsonElement content = gson.fromJson(s, JsonElement.class);
                    if (content.getAsJsonObject().get("post_type") != null) {
                        switch (content.getAsJsonObject().get("post_type").getAsString()) {
                            case "message":
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
                                        case "text":
                                            msg.append(ms.data.get("text"));
                                            mcm.messageFields.add(new TextField(ms.data.get("text")));
                                            break;
                                        case "face":
                                            msg.append("[QQ表情]");
                                            mcm.messageFields.add(new TextField("[QQ表情]"));
                                            break;
                                        // 暂时没看到有能和emoji一一对应的表格，先不管
                                        case "image":
                                            msg.append("[图片]");
                                            try {
                                                var imageField = new ImageField(new URL(ms.data.get("url")));
                                                mcm.messageFields.add(imageField);
                                            } catch (MalformedURLException e) {
                                                logger.error("无法以 {} 创建URL对象", ms.data.get("file"), e);
                                                mcm.messageFields.add(new TextField("[图片]"));
                                            }
                                            break;
                                        // 放不进去文本，先这样
                                        case "record":
                                            msg.append("[语音]");
                                            try {
                                                mcm.messageFields.add(new VoiceField(new URL(ms.data.get("url"))));
                                            } catch (MalformedURLException e) {
                                                logger.error("无法以 {} 创建URL对象", ms.data.get("url"), e);
                                                mcm.messageFields.add(new TextField("[语音]"));
                                            }
                                            break;
                                        case "video":
                                            msg.append("[视频]");
                                            try {
                                                mcm.messageFields.add(new VideoField(new URL(ms.data.get("url"))));
                                            } catch (MalformedURLException e) {
                                                logger.error("无法以 {} 创建URL对象", ms.data.get("url"), e);
                                                mcm.messageFields.add(new TextField("[视频]"));
                                                // 这里发生过报错，疑似是视频消息里面，file字段本身就不是URL
                                            }
                                            break;
                                        case "at":
                                            msg.append("[@qq:user/").append(ms.data.get("qq")).append("]");
                                            var account = new Account();
                                            account.setPlatform("QQ");
                                            account.setId(ms.data.get("qq"));
                                            mcm.messageFields.add(new AtField(account));
                                            break;
                                        case "rps":
                                            msg.append("[猜拳魔法表情]");
                                            mcm.messageFields.add(new MetaField("[猜拳魔法表情]"));
                                            break;
                                        case "dice":
                                            msg.append("[掷骰子魔法表情]");
                                            mcm.messageFields.add(new MetaField("[掷骰子魔法表情]"));
                                            break;
                                        case "shake":
                                            msg.append("[窗口抖动]");
                                            mcm.messageFields.add(new MetaField("[窗口抖动]"));
                                            break;
                                        case "poke":
                                            msg.append("[戳一戳]");
                                            mcm.messageFields.add(new MetaField("[戳一戳]"));
                                            break;
                                        case "anonymous":
                                            msg.append("(匿名消息)");
                                            mcm.messageFields.add(new MetaField("(匿名消息)"));
                                            break;
                                        case "share":
                                            msg.append("[分享链接, ").append(ms.data.get("title")).append(" : ").append(ms.data.get("url")).append(" ]");
                                            try {
                                                mcm.messageFields.add(new ShareUriField(ms.data.get("title"), new URI(ms.data.get("url"))));
                                            } catch (URISyntaxException e) {
                                                logger.error("无法以 {} 创建URL对象", ms.data.get("file"), e);
                                                mcm.messageFields.add(new TextField("[分享链接]"));
                                            }
                                            break;
                                        case "contact":
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
                                            break;
                                        case "location":
                                            msg.append("[分享一处位置, ").append("纬度").append(ms.data.get("lat")).append(", 经度").append(ms.data.get("lon"));
                                            mcm.messageFields.add(new LocationField(Double.parseDouble(ms.data.get("lat")), Double.parseDouble(ms.data.get("lon"))));
                                            break;
                                        case "music": // 音乐分享，普通的卡片要和自定义的卡片分开讨论
                                            switch (ms.data.get("type")) {
                                                case "163":
                                                    msg.append("[网易云音乐, ").append(ms.data.get("id")).append("]");
                                                    try {
                                                        mcm.messageFields.add(new ShareUriField("网易云音乐分享", new URI("https://music.163.com/#/song?id=" + ms.data.get("id"))));
                                                    } catch (URISyntaxException e) {
                                                        logger.error("无法以 {} 创建URL对象", ms.data.get("file"), e);
                                                        mcm.messageFields.add(new TextField("[网易云音乐分享]"));
                                                    }
                                                    break;
                                                case "qq":
                                                    msg.append("[QQ音乐, ").append(ms.data.get("id")).append("]");
                                                    try {
                                                        mcm.messageFields.add(new ShareUriField("QQ音乐分享", new URI("https://y.qq.com/n/ryqq/songDetail/" + ms.data.get("id"))));
                                                    } catch (URISyntaxException e) {
                                                        logger.error("无法以 {} 创建URL对象", ms.data.get("file"), e);
                                                        mcm.messageFields.add(new TextField("[QQ音乐分享]"));
                                                    }
                                                    break;
                                                case "xm":
                                                    msg.append("[虾米音乐, ").append(ms.data.get("id")).append("]");
                                                    mcm.messageFields.add(new TextField("[虾米音乐分享]"));
                                                    break;
                                                case "custom":
                                                    msg.append("[音乐分享, ").append(ms.data.get("title")).append(", ").append(ms.data.get("url"));
                                                    try {
                                                        mcm.messageFields.add(new ShareUriField("音乐分享", new URI(ms.data.get("url"))));
                                                    } catch (URISyntaxException e) {
                                                        logger.error("无法以 {} 创建URL对象", ms.data.get("file"), e);
                                                        mcm.messageFields.add(new TextField("音乐分享"));
                                                    }
                                                    break;
                                            }
                                            break;
                                        case "reply":
                                            msg.append("[回复消息 ").append(ms.data.get("id")).append("]");
                                            mcm.messageFields.add(new MetaField("回复消息" + ms.data.get("id")));
                                            break;
                                        case "forward":
                                            msg.append("[合并转发, ").append(ms.data.get("id")).append("]");
                                            mcm.messageFields.add(new MetaField("[合并转发]"));
                                            break;
                                        case "node":
                                            msg.append("[合并转发节点, ").append(ms.data.get("id")).append("]");
                                            mcm.messageFields.add(new MetaField("[合并转发节点]"));
                                            break;
                                        // 合并转发自定义节点 没做
                                        case "xml":
                                            msg.append("[XML消息]");
                                            mcm.messageFields.add(new MetaField("[XML消息]"));
                                            break;
                                        case "json":
                                            msg.append("[JSON消息]");
                                            mcm.messageFields.add(new MetaField("[JSON消息]"));
                                            break;
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
                                break;
                            case "meta_event":
                                Meta_Event meta_event = gson.fromJson(s, Meta_Event.class);
                                break;
                            default:
                                return;
                        }
                    }
                    if (content.getAsJsonObject().get("echo") != null) {
                        OBResponse response = gson.fromJson(content, OBResponse.class);
                        if (!response.echo.isEmpty()) {
                            syncActions.get(response.echo).callback(response);
                            syncActions.remove(response.echo);
                        }
                    }
                } catch (JsonSyntaxException ignored) {

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

    private void sendRequest(String action, Map params) {
        OBRequest obr = new OBRequest();
        obr.action = action;
        obr.params = params;

        action(obr);
    }

    private void reload() {
        load();
    }

    /**
     * 用于直接发送不需要处理结果的请求
     * 如果需要处理请求的结果，请改用 syncAction 方法
     * @param obr OnebotRequest
     */
    private void action(OBRequest obr) {
        if (!wsConnection.isOpen()) return;
        wsConnection.send(new Gson().toJson(obr));
    }

    /**
     * 此方法用于发送需要处理结果的请求，请求结果交由 Callback 对象中定义的代码处理
     * @param obr OnebotRequest
     * @param callback 回调函数
     */
    private void syncAction(OBRequest obr, Callback callback) {
        if (!wsConnection.isOpen()) return;
        UUID uuid = UUID.randomUUID(); // 实现方法非常简单，发过去一个唯一的文本字符串，然后 Onebot实现端 把响应发回来的时候依照它原封不动发回来的字符串匹配回对应的回调函数
        obr.echo = uuid.toString();
        syncActions.put(obr.echo, callback);
        wsConnection.send(new Gson().toJson(obr));
    }

    private interface Callback {
        void callback(OBResponse response);
    }
}
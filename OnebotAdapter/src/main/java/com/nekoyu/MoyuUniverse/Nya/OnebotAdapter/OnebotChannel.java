package com.nekoyu.MoyuUniverse.Nya.OnebotAdapter;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.Message;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.Meta_Event;
import com.nekoyu.Universe.API.MessageChannel.MCMessage;
import com.nekoyu.Universe.API.MessageChannel.MessageChannel;
import com.nekoyu.Universe.API.MessageChannel.QuickAction;
import com.nekoyu.Universe.API.MessageChannel.UnsupportedAction;
import com.nekoyu.Universe.API.MessageSession;
import com.nekoyu.Universe.Universe;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class OnebotChannel extends MessageChannel {
    WebSocketClient wsConnection;
    boolean isReady = true;
    Logger logger = LoggerFactory.getLogger(this.getClass());
    URI uri;
    String token;
    Map<String, Callback> syncActions = new HashMap<>();
    String nickname = null;
    long qqId;

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

    private void sendGroupMessage(String id, String message) {
        Map<String, String> params = new HashMap<>();
        params.put("group_id", id);
        params.put("message", message);

        sendRequest("send_group_msg", params);
    }

    private void sendPrivateMessage(String id, String message) {
        Map<String, String> params = new HashMap<>();
        params.put("user_id", id);
        params.put("message", message);

        sendRequest("send_private_msg", params);
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
                    qqId = responseData.get("user_id").getAsLong();
                    logger.info("{} 登录的 QQ号 为 {} ({})", ID, nickname, qqId);
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
                                mcm.time = message.time;
                                mcm.message = message.getMessageString();
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
                                switch (message.message_type) {
                                    case "group":
                                        Long groupId = message.group_id;
                                        sessionId.append(groupId);
                                        mcm.action = new QuickAction() {
                                            @Override
                                            public void reply(String message) {
                                                sendGroupMessage(String.valueOf(groupId), message);
                                            }
                                        };
                                        break;
                                    case "private":
                                        Long userId = message.user_id;
                                        sessionId.append(userId);
                                        mcm.action = new QuickAction() {
                                            @Override
                                            public void reply(String message) {
                                                sendPrivateMessage(String.valueOf(userId), message);
                                            }
                                        };
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
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
                reload();
            }

            @Override
            public void onError(Exception e) {
                logger.error(e.getMessage());
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
    public void sendMessage(String sessionId, String message) {
        String[] target = sessionId.split("\\/");
        switch (target[0]) {
            case "group":
                sendGroupMessage(target[1], message);
            case "private":
                sendPrivateMessage(target[1], message);
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
        logger.info("{} 已重载", ID);
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
package com.nekoyu.MoyuUniverse.Nya.OnebotAdapter;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.Message;
import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.Meta_Event;
import com.nekoyu.Universe.API.MessageChannel.MCMessage;
import com.nekoyu.Universe.API.MessageChannel.MessageChannel;
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
    int falls = 0;
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
                falls = 0;

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
                                switch (message.message_type) {
                                    case "group":
                                        StringBuilder sessionId = new StringBuilder();
                                        sessionId.append(ID);
                                        sessionId.append(":group/");
                                        sessionId.append(message.group_id);
                                        MCMessage mcm = new MCMessage();
                                        mcm.message = message.raw_message;
                                        mcm.receiver.setId(String.valueOf(message.self_id));
                                        mcm.receiver.setNickname(nickname);
                                        mcm.receiver.setPlatform("QQ");
                                        mcm.sender.setId(String.valueOf(message.sender.user_id));
                                        mcm.sender.setNickname(message.sender.nickname);
                                        mcm.sender.setPlatform("QQ");
                                        broadcastMessage("group/" + message.group_id, mcm);
                                }
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
                falls ++;
                if (falls <= 10) {
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                    reload();
                }
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

    private void sendRequest(String action, Map params) {
        OBRequest obr = new OBRequest();
        obr.action = action;
        obr.params = params;

        wsConnection.send(new Gson().toJson(obr));
    }

    private void reload() {
        load();
        logger.info("{} 已重载", ID);
    }

    private void action(OBRequest obr) {
        wsConnection.send(new Gson().toJson(obr));
    }

    private void syncAction(OBRequest obr, Callback callback) {
        UUID uuid = UUID.randomUUID();
        obr.echo = uuid.toString();
        syncActions.put(obr.echo, callback);
        wsConnection.send(new Gson().toJson(obr));
    }

    private interface Callback {
        public void callback(OBResponse response);
    }
}
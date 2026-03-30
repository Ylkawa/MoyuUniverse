package com.nekoyu.Universe.API.MessageChannel;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.nekoyu.Universe.API.MessageChannel.MessageField.TextField;
import com.nekoyu.Universe.Utils.ColorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MessageChannelManager {
    int MESSAGE_LIST_MAX_SIZE = 20;
    public Map<String, MessageChannel> MessageChannels = new HashMap<>();
    Multimap<String, MCMListener> sessionListeners = ArrayListMultimap.create();
    Multimap<String, MCPListener> mcpListeners = ArrayListMultimap.create();
    List<MCMListener> mcmListenersToAll = new ArrayList<>();
    List<MCPListener> mcpListenersToAll = new ArrayList<>();
    Logger logger = LoggerFactory.getLogger(getClass());
    Logger messagingLogger = LoggerFactory.getLogger("Messaging");
    Map<String, MessageList> messageHistory = new HashMap<>();

    public void registerChannel(String id, MessageChannel mc) {
        MessageChannels.put(id, mc);
    }

    public MessageChannel getChannel(String id) {
        return MessageChannels.get(id);
    }

    public void listenToSession(String sessionId, MCMListener mcmL) {
        if (sessionId.equals("*")) {
            mcmListenersToAll.add(mcmL);
        } else {
            sessionListeners.put(sessionId, mcmL);
        }
    }

    public void listenToSession(String sessionId, MCPListener mcpL) {
        if (sessionId.equals("*")) {
            mcpListenersToAll.add(mcpL);
        } else {
            mcpListeners.put(sessionId, mcpL);
        }
    }

    /**
     * 处理接收到的消息
     */
    public void onMessage(MessageChannel mc, MCMessage mcm) {
        if (mcm.sessionId != null && !mcm.sessionId.isEmpty()) {
            mcm.messageString = mcm.solveAll(false);
            String fg = "";
            if (mcm.sender.getAvatar() != null) {
                fg = ColorUtils.fg(mcm.sender.getAvatar().getMainColor());
            } else logger.debug("sender avatar is null");
            if (mcm.session instanceof Group group) {
                Color groupAvatarColor = null;
                if (group.getAvatar() != null) {
                    groupAvatarColor = group.getAvatar().getMainColor();
                } else logger.debug("group avatar is null");
                String groupFg = "";
                if (groupAvatarColor != null) {
                    groupFg = ColorUtils.fg(groupAvatarColor);
                }
                messagingLogger.info("{}{}{}[{}{}{}] {}{}{}({}) -> {}",
                        ColorUtils.fg(mc.mainColor),
                        mc.ID,
                        ColorUtils.RESET,
                        groupFg,
                        mcm.session.name,
                        ColorUtils.RESET,
                        fg,
                        mcm.sender.name,
                        ColorUtils.RESET,
                        mcm.sender.id,
                        mcm.messageString);
            } else {
                messagingLogger.info("{}{}{} {}{}{}({}) -> {}",
                        ColorUtils.fg(mc.mainColor),
                        mc.ID,
                        ColorUtils.RESET,
                        fg,
                        mcm.sender.name,
                        ColorUtils.RESET,
                        mcm.sender.id,
                        mcm.messageString);
            }
            messageHistory.computeIfAbsent(mcm.sessionId, k -> new MessageList());
            MessageList messageList = messageHistory.get(mcm.sessionId);
            messageList.add(mcm);
            messageList.clean(MESSAGE_LIST_MAX_SIZE);

            for (MCMListener mcl : sessionListeners.get(mcm.sessionId)) {
                mcl.onMessage(mcm);
            }
            for (MCMListener mcl : mcmListenersToAll) {
                mcl.onMessage(mcm);
            }
        }
    }

    public void onMessage(MessageChannel mc, MCPost mcp) {
        for (MCPListener mcpListener : mcpListeners.get(mcp.sessionId)) {
            mcpListener.onMessage(mcp);
        }
        for (MCPListener mcpListener : mcpListenersToAll) {
            mcpListener.onMessage(mcp);
        }
    }

    /**
     * 处理消息撤回的事件
     */
    public void onMessageRecall(String sessionId, long msgId) {
        MessageList messageList = messageHistory.get(sessionId);
        if (messageList == null) return;
        MCMessage mcm = messageList.getMsg(msgId);
        if (mcm != null && System.currentTimeMillis() / 1000 >= mcm.time + 5) {
            mcm.isRecalled = true;
        } else messageList.remove(mcm);
    }

    /**
     * 设置会话名称
     */
    public void setSessionName(String sessionId, String name) throws UnsupportedAction {
        String[] split = sessionId.split(":");
        MessageChannel mc = MessageChannels.get(split[0]);
        if (mc == null) throw new UnsupportedAction("无此MessageChannel");
        mc.setSessionName(split[1], name);
    }

    /**
     * 发送消息
     * sessionId 必须为全局sessionId
     */
    public void sendMessage(String sessionId, String message) {
        if (message.isBlank()) {
            logger.warn("严肃谴责发空白消息的情况");
            return;
        }
        String[] target = sessionId.split(":");
        MessageChannel mc = getChannel(target[0]);
        if (mc == null) {
            logger.warn("不存在此Channel {}", target[0]);
            throw new RuntimeException(target[0] + "is not exist");
        } else {
            Session session = mc.getSession(target[1]);
            String fg = "";
            if (session.avatar != null) {
                fg = ColorUtils.fg(session.avatar.getMainColor());
            }
            messagingLogger.info("{}{}{} {}{}{} <- {}",
                    ColorUtils.fg(mc.mainColor),
                    mc.ID,
                    ColorUtils.RESET,
                    fg,
                    session.name,
                    ColorUtils.RESET,
                    message);
            MCMessage mcm = new MCMessage();
            mcm.id = mc.sendMessage(target[1], message.strip()); //将sessionId转换成局部形式传给MessageChannel处理，同时把聊天记录对象传过去
            mcm.messageFields.add(new TextField(message));
            mcm.time = System.currentTimeMillis() / 1000;
            mcm.sender.id = mc.accountId;
            mcm.sender.name = mc.nickname;
            mcm.universe = true;
            // 应该没别的必须的参数了，留空算了

            MessageList messageList = messageHistory.get(sessionId);
            if (messageList == null) {
                messageList = new MessageList();
                messageHistory.put(sessionId, messageList);
            }
            messageList.add(mcm);
            messageList.clean(20);
        }
    }

    /**
     * 严重警告！！可能返回null
     * 返回的消息最大长度为20
     *
     */
    public MessageList getMessageHistory(String sessionId) {
        return messageHistory.get(sessionId);
    }

    public Session getSessionInfo(String sessionId) {
        String[] split = sessionId.split(":", 2);
        return getChannel(split[0]).getSession(split[1]);
    }
}

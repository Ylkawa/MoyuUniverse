package com.nekoyu.Universe.API.MessageChannel;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.nekoyu.Universe.API.MessageChannel.Features.Administration;
import com.nekoyu.Universe.API.MessageChannel.Features.SessionChat;
import com.nekoyu.Universe.API.MessageChannel.MessageField.TextField;
import com.nekoyu.Universe.Utils.ColorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.util.*;
import java.util.regex.Pattern;

public class MessageChannelManager {
    int MESSAGE_LIST_MAX_SIZE = 20;
    public Map<String, MessageChannel> MessageChannels = new HashMap<>();
    Multimap<String, MCMListener> sessionListeners = ArrayListMultimap.create();
    Multimap<String, MCPListener> postListeners = ArrayListMultimap.create();
    Logger logger = LoggerFactory.getLogger(getClass());
    Logger messagingLogger = LoggerFactory.getLogger("Messaging");
    Map<String, MessageList> messageHistory = new HashMap<>();

    public void registerChannel(String id, MessageChannel mc) {
        MessageChannels.put(id, mc);
    }

    public MessageChannel getChannel(String id) {
        return MessageChannels.get(id);
    }

    public void listenToSession(String regex, MCMListener mcmL) {
        sessionListeners.put(regex, mcmL);
    }

    public void listenToPost(String regex, MCPListener mcpL) {
        postListeners.put(regex, mcpL);
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

            for (String regex : sessionListeners.keySet()) {
                if (Pattern.matches(regex, mcm.sessionId)) for (MCMListener mcmL : sessionListeners.get(regex)) {
                    try {
                        mcmL.onMessage(mcm);
                    } catch (Exception e) {
                        logger.error("{} 处理消息时出错", mcmL, e);
                    }
                }
            }
        }
    }

    public void onMessage(MessageChannel mc, MCPost mcp) {
        String preview = mcp.messageFields.toString();
        preview = preview.replaceAll("\n", " ");
        if (preview.length() > 20) preview = preview.substring(0, 20) + "...";
        logger.info(
                "{}{}{}[POST]{}{}{}({}): {}",
                ColorUtils.fg(mc.mainColor),
                mc.ID,
                ColorUtils.RESET,
                ColorUtils.fg(mcp.poster.getColor()),
                mcp.poster.name,
                ColorUtils.RESET,
                mcp.poster.id,
                preview
        );

        for (String regex : postListeners.keySet()) {
            if (Pattern.matches(regex, mcp.sessionId)) for (MCPListener mcpL : postListeners.get(regex)) {
                try {
                    mcpL.onMessage(mcp);
                } catch (Exception e) {
                    logger.error("{} 处理消息时出错", mcpL, e);
                }
            }
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
        if (mc instanceof Administration administration) {
            administration.setSessionName(split[1], name);
        } else throw new UnsupportedAction(mc.ID + " doesn't support administration");
    }

    public void sendMessage(String sessionId, String message) {
        MessageChain msg = new MessageChain();
        msg.add(new TextField(message));
        sendMessage(sessionId, msg);
    }

    /**
     * 发送消息
     * sessionId 必须为全局sessionId
     */
    public void sendMessage(String sessionId, MessageChain message) {
        String[] target = sessionId.split(":");
        MessageChannel mc = getChannel(target[0]);
        if (mc == null) throw new RuntimeException("不存在此MessageChannel");
        if (mc instanceof SessionChat sc) {
            if (message.isEmpty() && message.toString().isEmpty()) {
                logger.warn("严肃谴责发空白消息的情况");
                return;
            }
            Session session = mc.getSession(target[1]);
            String fg = "";
            if (session.avatar != null) {
                fg = ColorUtils.fg(session.avatar.getMainColor());
            }
            //noinspection UnnecessaryToStringCall
            messagingLogger.info("{}{}{} {}{}{} <- {}",
                    ColorUtils.fg(mc.mainColor),
                    mc.ID,
                    ColorUtils.RESET,
                    fg,
                    session.name,
                    ColorUtils.RESET,
                    message.toString());
            MCMessage mcm = new MCMessage();
            mcm.id = sc.sendMessage(target[1], message); //将sessionId转换成局部形式传给MessageChannel处理，同时把聊天记录对象传过去
            mcm.messageFields = message;
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
        } else throw new UnsupportedAction(mc.ID + " doesn't support session chat");
    }

    public void replyPost(String sessionId, MessageChain message) {
        logger.error("快别reply了没写完");
        // TODO PostChat 相关
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

package com.nekoyu.Universe.API.MessageChannel;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.nekoyu.Universe.API.MessageChannel.MessageField.TextField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class MessageChannelManager {
    public Map<String, MessageChannel> MessageChannels = new HashMap<>();
    Multimap<String, MessageChannelListener> sessionListeners = ArrayListMultimap.create();
    List<MessageChannelListener> listenersToAll = new ArrayList<>();
    Logger logger = LoggerFactory.getLogger(getClass());
    Map<String, MessageList> messageHistory = new HashMap();

    public void registerChannel(String id, MessageChannel mc) {
        MessageChannels.put(id, mc);
    }

    public MessageChannel getChannel(String id) {
        return MessageChannels.get(id);
    }

    public void listenToSession(String sessionId, MessageChannelListener mcl) {
        if (sessionId.equals("*")) {
            listenersToAll.add(mcl);
        } else {
            sessionListeners.put(sessionId, mcl);
        }
    }

    /**
     * 处理接收到的消息
     */
    public void onMessage(MessageChannel mc, MCMessage mcm) {
        if (mcm.sessionId != null && !mcm.sessionId.isEmpty()) {
            mcm.messageString = mcm.solveAll(false);
            logger.info("接收到来自会话 {} 的消息 {} ({}): {}", mcm.sessionId, mcm.sender.nickname, mcm.sender.id, mcm.messageString);
            messageHistory.computeIfAbsent(mcm.sessionId, k -> new MessageList());
            MessageList messageList = messageHistory.get(mcm.sessionId);
            messageList.add(mcm);
            messageList.clean(20);

            for (MessageChannelListener mcl : sessionListeners.get(mcm.sessionId)) {
                mcl.onMessage(mcm);
            }
            for (MessageChannelListener mcl : listenersToAll) {
                mcl.onMessage(mcm);
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
            // TODO: 不存在这个Channel，之后做异常处理
            logger.warn("不存在此Channel {}", target[0]);
        } else {
            logger.info("向会话 {} 发送消息: {}", sessionId, message);
            MCMessage mcm = new MCMessage();
            mcm.id = mc.sendMessage(target[1], message.strip()); //将sessionId转换成局部形式传给MessageChannel处理，同时把聊天记录对象传过去
            mcm.messageFields.add(new TextField(message));
            mcm.time = System.currentTimeMillis() / 1000;
            mcm.sender.id = mc.accountId;
            mcm.sender.nickname = mc.nickname;
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

    public Account getAccount(String sessionId) {
        String[] split = sessionId.split(":", 2);
        return getChannel(split[0]).getAccount(split[1]);
    }
}

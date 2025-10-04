package com.nekoyu.Universe.API.MessageChannel;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MessageChannelManager {
    public Map<String, MessageChannel> MessageChannels = new HashMap<>();
    Multimap<String, MessageChannelListener> sessionListeners = ArrayListMultimap.create();
    List<MessageChannelListener> listenersToAll = new ArrayList<>();
    Logger logger = LoggerFactory.getLogger(getClass());

    public void registerChannel(String id, MessageChannel mc) {
        MessageChannels.put(id, mc);
    }

    public MessageChannel getChannel(String id) {
        return MessageChannels.get(id);
    }

    public void listenToSession(String sessionId, MessageChannelListener mcl) {
        if (sessionId.equals("*")){
            listenersToAll.add(mcl);
        } else {
            sessionListeners.put(sessionId, mcl);
        }
    }

    public void onMessage(MessageChannel mc, MCMessage mcm) {
        if (mcm.sessionId != null && !mcm.sessionId.isEmpty()) {
            logger.info("{} 接收到来自会话 {} 的消息 {} ({}): {}", mc.ID, mcm.sessionId, mcm.sender.nickname, mcm.sender.id, mcm.messageString);
            mcm.action = new QuickAction() {
                @Override
                public void reply(String message) {
                    mc.sendMessage(message, mcm.sessionId);
                }
            };
            for (MessageChannelListener mcl : sessionListeners.get(mcm.sessionId)) {
                mcl.onMessage(mcm);
            }
            for (MessageChannelListener mcl : listenersToAll) {
                mcl.onMessage(mcm);
            }
        }
    }

    public void setSessionName(String sessionId, String name) throws UnsupportedAction {
        String[] split = sessionId.split(":");
        MessageChannel mc = MessageChannels.get(split[0]);
        if (mc == null) throw new UnsupportedAction("无此MessageChannel");
        mc.setSessionName(split[1], name);
    }

    public void sendMessage(String sessionId, String message) {
        String[] target = sessionId.split(":");
        MessageChannel mc = getChannel(target[0]);
        if (mc == null) {
            // 不存在这个Channel，之后做异常处理
            logger.warn("不存在此Channel {}", target[0]);
        } else {
            // 灵羽『θPlvme』 接收到来自会话 lingyu:group/771886810 的消息 冷艳 (703964965): 边上飞过去好几辆电车
            logger.info("{} 向会话 {} 发送消息: {}", mc.ID, sessionId, message);
            mc.sendMessage(target[1], message);
        }
    }
}

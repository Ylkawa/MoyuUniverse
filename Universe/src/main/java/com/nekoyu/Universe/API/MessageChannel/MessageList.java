package com.nekoyu.Universe.API.MessageChannel;

import java.util.ArrayList;
import java.util.List;

public class MessageList {
    List<MCMessage> messages = new ArrayList<>();

    public void add(MCMessage message) {
        messages.add(message);
        clean(20);
    }

    public void clean(int maxSize) {
        if (messages.size() <= maxSize) {
            return; // 如果消息数量未超过限制，无需清理
        }

        // 计算需要删除的消息数量
        int removeCount = messages.size() - maxSize;
        messages.subList(0, removeCount).clear();
    }
}

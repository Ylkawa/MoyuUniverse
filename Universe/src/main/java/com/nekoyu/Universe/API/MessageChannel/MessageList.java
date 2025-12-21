package com.nekoyu.Universe.API.MessageChannel;

import java.util.ArrayList;
import java.util.List;

public class MessageList extends ArrayList<MCMessage> {
    public void clean(int maxSize) {
        if (size() <= maxSize) {
            return; // 如果消息数量未超过限制，无需清理
        }

        // 计算需要删除的消息数量
        int removeCount = size() - maxSize;
        subList(0, removeCount).clear();
    }

    public MCMessage getMsg(long msgId) {
        for (MCMessage mcm : this) {
            if (mcm.id == msgId) return mcm;
        }
        return null;
    }
}

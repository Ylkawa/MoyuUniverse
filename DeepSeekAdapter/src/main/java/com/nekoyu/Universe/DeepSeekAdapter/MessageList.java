package com.nekoyu.Universe.DeepSeekAdapter;

import java.util.LinkedList;
import java.util.List;

public class MessageList {
    List<Message> messageList = new LinkedList<>();

    public void addMessage(String content) {
        var msg = new Message();
        msg.content = content;
        msg.role = "user";
        messageList.add(msg);
    }

    public List<Message> getMessageList() {
        return messageList;
    }

    public void addMessage(String role, String content) {
        var msg = new Message();
        msg.content = content;
        msg.role = role;
        messageList.add(msg);
    }

    public void clean() {
        if (messageList.size() > 20) {
            messageList.subList(0, messageList.size() - 20).clear(); // 删除前 N-20 个元素
        }
    }
}

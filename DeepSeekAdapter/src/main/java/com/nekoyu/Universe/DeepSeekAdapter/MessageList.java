package com.nekoyu.Universe.DeepSeekAdapter;

import java.util.LinkedList;
import java.util.List;

public class MessageList {
    List<Message> messageList = new LinkedList<>();

    public void setSystemPrompt(String systemPrompt) {
        if (!messageList.isEmpty() && messageList.get(0).role.equals("system")){
            messageList.get(0).content = systemPrompt;
        } else {
            Message prompt = new Message();
            prompt.role = "system";
            prompt.content = systemPrompt;
            messageList.add(0, prompt);
        }
    }

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
        int maxSize = 20;
        if (messageList.size() <= maxSize) {
            return; // 如果消息数量未超过限制，无需清理
        }

        // 计算需要删除的消息数量
        int removeCount = messageList.size() - maxSize;

        // 检查第一条消息是否为 system prompt
        if (!messageList.isEmpty() && "system".equals(messageList.get(0).role)) {
            // 保留第一条 system prompt + 后续的 (maxSize-1) 条最新消息
            int startIndex = 1; // 从索引 1 开始删除
            int endIndex = startIndex + removeCount; // 删除范围 [1, 1+removeCount)
            if (endIndex > messageList.size()) {
                endIndex = messageList.size();
            }
            messageList.subList(startIndex, endIndex).clear();
        } else {
            // 没有 system prompt，直接删除最旧的消息
            messageList.subList(0, removeCount).clear();
        }
    }

    public void addToolResponse(String content, String tool_call_id) {
        var msg = new Message();
        msg.content = content;
        msg.role = "tool";
        msg.tool_call_id = tool_call_id;
        messageList.add(msg);
    }

    public void addToolRequest(String content, Tool_call[] tool_calls) {
        var msg = new Message();
        msg.role = "assistant";
        msg.content = content;
        msg.tool_calls = tool_calls;
        messageList.add(msg);
    }
}

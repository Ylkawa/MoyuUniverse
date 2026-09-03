package com.nekoyu.Universe.AIChat;

import com.nekoyu.Universe.API.MessageChannel.MessageList;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Context;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Message;

import java.util.List;

public class ChatContext implements Context {
    String systemPromptFirst = null;
    String systemPromptLast = null;
    MessageList messageList = new MessageList();

    @Override
    public List<Message> getMessageList() {
        return List.of();
    }

    @Override
    public void toolMsg(Message message) {

    }

    @Override
    public void assistantMsg(Message message) {

    }

    @Override
    public void userMsg(Message message) {

    }
}

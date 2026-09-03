package com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies;

import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ContentPiece.ContentPiece;

import java.util.List;

public interface Context {
    List<Message> getMessageList();
    void toolMsg(Message message);
    void assistantMsg(Message message);
    void userMsg(Message message);
}

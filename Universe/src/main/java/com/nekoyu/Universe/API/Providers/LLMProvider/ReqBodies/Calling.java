package com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies;

public interface Calling {
    void calling(Message message); // 分次返回消息
}
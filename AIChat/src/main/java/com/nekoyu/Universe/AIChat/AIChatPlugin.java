package com.nekoyu.Universe.AIChat;

import com.nekoyu.Universe.AIChat.Event.RequestEvent;

public abstract class AIChatPlugin {
    public AIChatPlugin() {} // 留一个空的构造函数备用

    public void onEnable() {}
    public void onDisable() {}

    public void onRequest(RequestEvent event) {}
}

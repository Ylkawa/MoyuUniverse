package com.nekoyu.Universe.AIChat;

import com.nekoyu.Universe.AIChat.Event.RequestEvent;

import java.io.File;

public abstract class AIChatPlugin {
    public String id;
    public AIChatPlugin() {} // 留一个空的构造函数备用

    public void onEnable() {}
    public void onDisable() {}

    public void onRequest(RequestEvent event) {}

    public File getConfigDir() {
        File file = new File("./config/AIChat/Plugins/" + id + "/");
        if (!file.isDirectory()) file.mkdirs();
        return file;
    }
}

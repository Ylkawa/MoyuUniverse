package com.nekoyu.Universe.AIChat.Config;

import java.util.List;

public class SessionCFG {
    String ChannelId;
    String Prompt;
    String Provider;
    String Trigger;
    String Model;
    String Keyword;
    List<String> Functions;

    public String getChannelId() {
        return ChannelId;
    }

    public void setChannelId(String channelId) {
        ChannelId = channelId;
    }

    public String getPrompt() {
        return Prompt;
    }

    public void setPrompt(String prompt) {
        Prompt = prompt;
    }

    public String getProvider() {
        return Provider;
    }

    public void setProvider(String provider) {
        Provider = provider;
    }

    public String getTrigger() {
        return Trigger;
    }

    public void setTrigger(String trigger) {
        Trigger = trigger;
    }

    public String getModel() {
        return Model;
    }

    public void setModel(String model) {
        Model = model;
    }

    public String getKeyword() {
        return Keyword;
    }

    public void setKeyword(String keyword) {
        Keyword = keyword;
    }

    public List<String> getFunctions() {
        return Functions;
    }

    public void setFunctions(List<String> functions) {
        Functions = functions;
    }
}

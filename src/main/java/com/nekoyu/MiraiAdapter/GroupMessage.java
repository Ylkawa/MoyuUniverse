package com.nekoyu.MiraiAdapter;

import com.google.gson.JsonObject;

public class GroupMessage {
    String type;
    JsonObject[] messageChain;
    Sender sender;

    public String getTextMessage(){
        for (JsonObject jsonObject : messageChain){
            if (jsonObject.get("type").getAsString().equals("Plain")) return jsonObject.get("text").toString();
        }
        return null;
    }

    public String getSenderName() {
        return sender.memberName;
    }

    public long getGroupID() {
        return sender.group.id;
    }

    public String getGroupName() {
        return sender.group.name;
    }

    public long getSenderID() {
        return sender.id;
    }
}
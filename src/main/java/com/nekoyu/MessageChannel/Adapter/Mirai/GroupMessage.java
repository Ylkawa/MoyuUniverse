package com.nekoyu.MessageChannel.Adapter.Mirai;

import com.google.gson.JsonObject;

public class GroupMessage {
    String type;
    JsonObject[] messageChain;
    Sender sender;

    public String getTextMessage(){
        StringBuilder stringBuilder = new StringBuilder();
        for (JsonObject jsonObject : messageChain){
            if (jsonObject.get("type").getAsString().equals("Plain")) stringBuilder.append(jsonObject.get("text").toString());
        }
        return stringBuilder.toString();
    }

    public String getSenderName() {
        return sender.memberName;
    }

    public String getGroupID() {
        return sender.group.id;
    }

    public String getGroupName() {
        return sender.group.name;
    }

    public String getSenderID() {
        return sender.id;
    }

    public boolean isBeAted(String id){
        for (JsonObject jsonObject : messageChain) {
            if (jsonObject.get("type").getAsString().equals("At") && jsonObject.get("target").getAsString().equals(id)) return true;
        }
        return false;
    }
}
package com.nekoyu;

public interface MessageHandler {
    void onGroupMessageReceived(String GroupName, long GroupID, long QQID, String QQName, String message);
}

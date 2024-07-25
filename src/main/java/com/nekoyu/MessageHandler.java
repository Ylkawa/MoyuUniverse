package com.nekoyu;

public interface MessageHandler {
    void onGroupMessageReceived(Long QQID, String QQName, String message);
}

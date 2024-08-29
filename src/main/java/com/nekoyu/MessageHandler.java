package com.nekoyu;

import com.google.gson.JsonObject;
import com.nekoyu.MiraiAdapter.GroupMessage;

public interface MessageHandler {
    void onGroupMessageReceived(JsonObject JSON);
}

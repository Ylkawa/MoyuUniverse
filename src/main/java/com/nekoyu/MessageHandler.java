package com.nekoyu;

import com.google.gson.JsonObject;

public interface MessageHandler {
    void onGroupMessageReceived(JsonObject JSON);
}

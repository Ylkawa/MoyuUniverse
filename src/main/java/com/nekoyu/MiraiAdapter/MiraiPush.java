package com.nekoyu.MiraiAdapter;

import com.google.gson.JsonObject;

public class MiraiPush {
    String syncId;
    JsonObject data;

    public String getSyncId() {
        return syncId;
    }

    public JsonObject getData() {
        return data;
    }
}

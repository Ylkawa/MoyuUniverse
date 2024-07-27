package com.nekoyu.MiraiAdapter;

import com.google.gson.JsonElement;

public class MiraiRequest {
    int syncId;
    String command;
    String subCommand;
    JsonElement content;
}

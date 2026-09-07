package com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.MsgFields;

public class Reply extends MessageSegment {
    public Reply(String id) {
        type = "reply";
        data.put("id", id);
    }
}

package com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.MsgFields;

public class At extends MessageSegment {
    public At(String qq) {
        type = "at";
        data.put("qq", qq);
    }
}

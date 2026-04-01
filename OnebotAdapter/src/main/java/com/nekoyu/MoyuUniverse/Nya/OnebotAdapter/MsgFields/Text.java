package com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.MsgFields;

public class Text extends MessageSegment {
    public Text(String content) {
        type = "text";
        data.put("text", content);
    }
}

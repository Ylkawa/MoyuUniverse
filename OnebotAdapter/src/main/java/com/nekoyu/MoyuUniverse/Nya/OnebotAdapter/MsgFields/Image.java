package com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.MsgFields;

public class Image extends MessageSegment {
    public Image(String url) {
        type = "image";
        data.put("url", url);
    }
}

package com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.MsgFields;

public class Text extends OBMsgField {
    public Text(String content) {
        type = "text";
        data.put("text", content);
    }
}

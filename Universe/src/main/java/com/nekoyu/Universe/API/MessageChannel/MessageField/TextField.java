package com.nekoyu.Universe.API.MessageChannel.MessageField;

public class TextField extends MsgField{
    String text;

    public TextField(String text) {
        super.type = "Text";
        this.text = text;
    }
}

package com.nekoyu.Universe.API.MessageChannel.MessageField;

public class TextField extends MsgField{
    String text;

    public TextField(String text) {
        super.type = "text";
        this.text = text;
        super.isSolved = true;
    }

    public String getAsString() {
        return text;
    }
}

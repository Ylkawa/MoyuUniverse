package com.nekoyu.Universe.API.MessageChannel.MessageField;

public class ReplyField extends MsgField {
    public String reply;

    public ReplyField(String reply) {
        super.type = "reply";
        this.reply = reply;
    }

    @Override
    public String toString() {
        return "[回复消息, " + reply + "]";
    }
}

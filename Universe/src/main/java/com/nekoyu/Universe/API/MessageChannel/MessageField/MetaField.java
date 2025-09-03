package com.nekoyu.Universe.API.MessageChannel.MessageField;

public class MetaField extends MsgField {
    String meta;

    public MetaField(String meta) {
        super.type = "Meta";
        this.meta = meta;
    }
}

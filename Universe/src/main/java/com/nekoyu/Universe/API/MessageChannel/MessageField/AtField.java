package com.nekoyu.Universe.API.MessageChannel.MessageField;

import com.nekoyu.Universe.API.MessageChannel.Session;

public class AtField extends MsgField {
    public Session target;

    public AtField(Session target) {
        super.type = "at";
        this.target = target;
    }

    @Override
    public String toString() {
        return "@"+target.getName();
    }
}

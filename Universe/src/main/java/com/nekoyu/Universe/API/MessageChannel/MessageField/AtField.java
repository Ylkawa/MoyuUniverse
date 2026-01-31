package com.nekoyu.Universe.API.MessageChannel.MessageField;

import com.nekoyu.Universe.API.MessageChannel.SessionInfo;

public class AtField extends MsgField {
    public SessionInfo target;

    public AtField(SessionInfo target) {
        super.type = "at";
        this.target = target;
    }

    public String getAsString() {
        return "@"+target.getName();
    }
}

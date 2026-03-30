package com.nekoyu.Universe.API.MessageChannel.MessageField;

public class ShareContactField extends MsgField {
    String platform;
    String sessionId;

    public ShareContactField(String platform, String sessionId) {
        super.type = "shareContact";
        this.platform = platform;
        this.sessionId = sessionId;
    }

    @Override
    public String toString() {
        return "[分享会话: "+sessionId+"]";
    }
}

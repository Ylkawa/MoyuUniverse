package com.nekoyu.Universe.API.MessageChannel.MessageField;

public class ShareContactField extends MsgField {
    String platform;
    String sessionId;

    public ShareContactField(String platform, String sessionId) {
        super.type = "ShareContact";
        this.platform = platform;
        this.sessionId = sessionId;
    }
}

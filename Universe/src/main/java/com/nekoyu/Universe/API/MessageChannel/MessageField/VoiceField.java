package com.nekoyu.Universe.API.MessageChannel.MessageField;

public class VoiceField extends FileField {
    public VoiceField() {
        super.type = "voice";
    }

    public String getAsString() {
        return "[语音]";
    }
}

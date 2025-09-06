package com.nekoyu.Universe.API.MessageChannel.MessageField;

import java.net.URL;

public class VoiceField extends FileField {
    public VoiceField(URL url) {
        super(url);
        super.type = "voice";
    }

    public String getAsString() {
        return "[语音]";
    }
}

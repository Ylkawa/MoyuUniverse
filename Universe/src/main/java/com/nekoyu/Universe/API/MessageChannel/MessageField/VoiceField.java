package com.nekoyu.Universe.API.MessageChannel.MessageField;

import com.nekoyu.Universe.Universe;

import java.io.IOException;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

public class VoiceField extends FileField {
    public VoiceField(URL url) {
        super(url);
        super.type = "voice";
    }

    @Override
    public void solve() {
        if (isSolved) return;
        isSolved = true;
        if (Universe.voiceSolver == null) return;
        try {
            description = Universe.voiceSolver.getDescription(url);
        } catch (IOException ignored) {}
    }

    public String getAsString() {
        if (description != null) return description;
        return "[语音]";
    }
}

package com.nekoyu.Universe.AIChat;

import com.nekoyu.Universe.API.MessageChannel.MFChain;

public interface MarkDecoupler {
    MFChain process(String args);
}

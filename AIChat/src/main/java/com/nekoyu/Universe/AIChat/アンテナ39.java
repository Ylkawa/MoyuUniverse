package com.nekoyu.Universe.AIChat;

import com.nekoyu.Universe.API.MessageChannel.MCMessage;
import com.nekoyu.Universe.DeepSeekAdapter.StringMessage;

public class アンテナ39 extends StringMessage {
    transient MCMessage mcMessage;

    public アンテナ39(MCMessage mcMessage) {
        this.content = null;
        this.mcMessage = mcMessage;
    }

    public void prepare() {
        if (content == null) this.content = mcMessage.solveAll();
    }
}

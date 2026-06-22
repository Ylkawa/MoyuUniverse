package com.nekoyu.Universe.API.MessageChannel;

import com.nekoyu.Universe.API.MessageChannel.events.MCEvent;

public interface MCEListener {
    void onEvent(MCEvent event);
}

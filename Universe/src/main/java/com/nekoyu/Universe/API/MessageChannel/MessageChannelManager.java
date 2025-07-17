package com.nekoyu.Universe.API.MessageChannel;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;

import java.util.HashMap;
import java.util.Map;

public class MessageChannelManager {
    public Map<String, MessageChannel> MessageChannels = new HashMap<>();
    Multimap<String, MessageChannelListener> sessionListeners = ArrayListMultimap.create();

    public void registerChannel(String id, MessageChannel mc) {
        MessageChannels.put(id, mc);
    }

    public MessageChannel getChannel(String id) {
        return MessageChannels.get(id);
    }

    public void listenToSession(String id, MessageChannelListener mcl) {
        sessionListeners.put(id, mcl);
    }

    public void onMessage(MCMessage mcm) {

    }
}

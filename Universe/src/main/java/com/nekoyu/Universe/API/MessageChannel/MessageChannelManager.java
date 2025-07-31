package com.nekoyu.Universe.API.MessageChannel;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MessageChannelManager {
    public Map<String, MessageChannel> MessageChannels = new HashMap<>();
    Multimap<String, MessageChannelListener> sessionListeners = ArrayListMultimap.create();
    List<MessageChannelListener> listenersToAll = new ArrayList<>();

    public void registerChannel(String id, MessageChannel mc) {
        MessageChannels.put(id, mc);
    }

    public MessageChannel getChannel(String id) {
        return MessageChannels.get(id);
    }

    public void listenToSession(String sessionId, MessageChannelListener mcl) {
        if (sessionId.equals("*")){
            listenersToAll.add(mcl);
        } else {
            sessionListeners.put(sessionId, mcl);
        }
    }

    public void onMessage(MCMessage mcm) {
        if (mcm.sessionId != null && !mcm.sessionId.isEmpty()) {
            for (MessageChannelListener mcl : sessionListeners.get(mcm.sessionId)) {
                mcl.onMessage(mcm);
            }
            for (MessageChannelListener mcl : listenersToAll) {
                mcl.onMessage(mcm);
            }
        }
    }
}

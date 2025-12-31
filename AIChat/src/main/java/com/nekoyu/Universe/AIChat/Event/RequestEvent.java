package com.nekoyu.Universe.AIChat.Event;

import com.nekoyu.Universe.API.MessageChannel.MessageList;

import java.util.HashMap;
import java.util.Map;

public class RequestEvent {
    public Map<String, String> placeholders = new HashMap<>();
    public MessageList messageList;
    public String sessionId;
    public String locationId;
}

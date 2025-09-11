package com.nekoyu.Universe.AIChat.Event;

import com.nekoyu.Universe.DeepSeekAdapter.MessageList;

import java.util.HashMap;
import java.util.Map;

public class RequestEvent {
    public Map<String, String> placeholders = new HashMap<>();
    public MessageList messageList;
}

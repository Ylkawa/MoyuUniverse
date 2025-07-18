package com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event;

import java.util.List;
import java.util.Map;

public class Message {
    public long time;
    public long self_id;
    public String post_type;
    public String message_type;
    public String sub_type;
    public int message_id;
    public long user_id;
    public List<MessageSegment> message;
    public String raw_message;
    public int font;
    public Sender sender;
    public long group_id;
    public Anonymous anonymous;

    private class MessageSegment {
        public String type;
        public Map<String, String> data;
    }
}

package com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event;

import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.MsgFields.MessageSegment;

import java.util.List;

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

    private transient String stringMsg = null;
}

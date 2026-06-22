package com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.meta_event;

public class Meta_Event {
    public long time;
    public long self_if;
    public String post_type;
    public String meta_event_type;
    public String sub_type;
    public long interval;
    public Status status;

    public static class Status {
        public boolean online;
        public boolean good;
    }
}

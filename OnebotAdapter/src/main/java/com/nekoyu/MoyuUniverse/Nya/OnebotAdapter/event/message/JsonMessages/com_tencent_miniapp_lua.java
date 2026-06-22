package com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.message.JsonMessages;

import java.util.List;

public class com_tencent_miniapp_lua extends JsonMessage {
    public String desc;
    public String bizsrc;
    public String appID;
    public String sourceName;
    public String actionData;
    public String actionData_A;
    public String sourceUrl;
    public Meta meta;
    public Config config;
    public String text;
    public List<Object> extraApps;
    public String sourceAd;
    public String extra;

    public static class Meta {
        public Miniapp miniapp;

        public static class Miniapp {
            public String jumpUrl;
            public String preview;
            public String source;
            public String sourcelogo;
            public String tag;
            public String tagIcon;
            public String title;
            public String legacyUrl;
            public String legacyVersion;
            public String legacyToast;
            public String pcJumpUrl;
        }
    }

    public static class Config {
        public short autosize;
        public short collect;
        public long ctime;
        public int forward;
        public int height;
        public int reply;
        public int round;
        public String token;
        public String type;
        public int width;
    }
}

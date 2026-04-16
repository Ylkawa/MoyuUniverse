package com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.JsonMessages;

import com.google.gson.JsonElement;

public class com_tencent_tuwen_lua extends JsonMessage {
    public String bizsrc;
    public Config config;
    public JsonElement extra;
    public Meta meta;

    public static class Config {
        public long ctime;
        public short forward;
        public String token;;
        public String type;
    }

    public static class Extra {
        public int app_type;
        public long appid;
        public long msg_seq;
        public long uin;
    }

    public static class Meta {
        public News news;

        public static class News {
            public short app_type;
            public int appid;
            public long ctime;
            public String desc;
            public String jumpUrl;
            public String preview;
            public String tag;
            public String tagIcon;
            public String title;
            public String uin;
        }
    }
}

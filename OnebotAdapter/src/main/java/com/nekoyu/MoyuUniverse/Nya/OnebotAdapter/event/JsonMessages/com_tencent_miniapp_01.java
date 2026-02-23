package com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.event.JsonMessages;

import java.util.Map;

public class com_tencent_miniapp_01 extends JsonMessage {
    public boolean needShareCallBack;
    public Meta meta;
    public Config config;

    public static class Meta {
        public Detail_1 detail_1;

        public static class Detail_1 {
            public String appid;
            public short appType;
            public String title;
            public String desc;
            public String icon;
            public String preview;
            public String url;
            public int scene;
            public Host host;
            public String shareTemplateId;
            public Map<String, Object> shareTemplateData;
            public String qqdocurl;
            public String showLittleTail;
            public String gamePoints;
            public String gamePointsUrl;
            public int shareOrigin;

            public static class Host {
                public long uin;
                public String nick;
            }
        }
    }

    public static class Config {
        public String token;
        public long ctime;
        public int forward;
        public String type;
        public int width;
        public int height;
        public int autoSize;
    }
}

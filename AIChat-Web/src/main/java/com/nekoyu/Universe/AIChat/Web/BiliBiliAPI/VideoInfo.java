package com.nekoyu.Universe.AIChat.Web.BiliBiliAPI;

public class VideoInfo {
    public int code;
    public String message;
    public int ttl;
    public Data data;

    public static class Data {
        public String bvid;
        public long aid;
        public int videos;
        public int tid;
        public int tid_v2;
        public String tname;
        public String tname_v2;
        public int copyright;
        public String pic;
        public String title;
        public long pubdate;
        public long ctime;
        public String desc;
        public Desc_v2[] desc_v2;
        public int state;
        public int duration;
        public Rights rights;
        public Owner owner;
        public Stat stat;
        public Argue_info argue_info;
        public String dynamic;
        public long cid;
        public Dimension dimension;
        // 响应原文这里还有premiere = null，看不懂什么意思不加了省得Gson报错
        public short teenage_mode;
        public boolean is_chargeable_session;
        public boolean is_story;
        public boolean is_upower_exclusive;
        public boolean is_upower_play;
        public boolean is_upower_preview;
        public short enable_vt;
        public boolean is_upower_exclusive_with_qa;
        public boolean no_cache;
        public Page[] pages;
        public Subtitle subtitle;
        public boolean is_season_display;
        public User_garb user_garb;
        public Honor_reply honor_reply;
        public String like_icon;
        public boolean need_jump_bv;
        public boolean disable_show_up_info;
        public short is_story_play;
        public boolean is_view_self;

        public static class Desc_v2 {
            public String raw_text;
            public int type;
            public int biz_id;
        }

        public static class Rights {
            public short bp;
            public short elec;
            public short download;
            public short movie;
            public short pay;
            public short hd5;
            public short no_reprint;
            public short autoplay;
            public short ugc_pay;
            public short is_cooperation;
            public short ugc_pay_preview;
            public short no_background;
            public short clean_mode;
            public short is_stein_gate;
            public short is_360;
            public short no_share;
            public short arc_pay;
            public short free_watch;
        }

        public static class Owner {
            public long mid;
            public String name;
            public String face;
        }

        public static class Stat {
            public long aid;
            public long view;
            public long danmaku;
            public long reply;
            public long favorite;
            public long coin;
            public long share;
            public int now_rank;
            public int his_rank;
            public long like;
            public long dislike;
            public String evaluation;
            public int vt;
        }

        public static class Argue_info {
            public String argue_msg;
            public int argue_type;
            public String argue_link;
        }

        public static class Dimension {
            public int width;
            public int height;
            public int rotate;
        }

        public static class Page {
            public long cid;
            public int page;
            public String from;
            public String part;
            public int duration;
            public String vid;
            public String weblink;
            public Dimension dimension;
            public String first_frame;
            public int ctime;
        }

        public static class Subtitle {
            public boolean allow_submit;
            public St[] list;

            public static class St {
                public int id;
                public String lan;
                public String lan_doc;
                public boolean is_lock;
                public long author_mid;
                public String subtitle_url;
                public Author author;

                public static class Author {
                    public long mid;
                    public String name;
                    public String sex;
                    public String face;
                    public String sign;
                    public short rank;
                    public int birthday;
                    public short is_fake_account;
                    public short is_deleted;
                }
            }
        }

        public static class User_garb {
            public String url_image_ani_cut;
        }

        public static class Honor_reply {
            public Honor[] honor;

            public static class Honor {
                public String aid;
                public short type; //1：入站必刷收录 2：第?期每周必看 3：全站排行榜最高第?名 4：热门
                public String desc;
                public int weekly_recommend_num;
            }
        }
    }
}

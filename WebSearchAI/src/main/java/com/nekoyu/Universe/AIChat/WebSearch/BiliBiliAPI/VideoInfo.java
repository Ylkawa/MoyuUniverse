package com.nekoyu.Universe.AIChat.WebSearch.BiliBiliAPI;

public class VideoInfo {
    int code;
    String message;
    int ttl;
    Data data;

    public static class Data {
        String bvid;
        long aid;
        int videos;
        int tid;
        int tid_v2;
        String tname;
        String tname_v2;
        int copyright;
        String pic;
        String title;
        long pubdate;
        long ctime;
        String desc;
        Desc_v2[] desc_v2;
        int state;
        int duration;
        Rights rights;
        Owner owner;
        Stat stat;
        Argue_info argueInfo;
        String dynamic;
        long cid;
        Dimension dimension;
        // 响应原文这里还有premiere = null，看不懂什么意思不加了省得Gson报错
        short teenage_mode;
        boolean is_chargeable_session;
        boolean is_story;
        boolean is_upower_exclusive;
        boolean is_upower_play;
        boolean is_upower_preview;
        short enable_vt;
        boolean is_upower_exclusive_with_qa;
        boolean no_cache;
        Page[] pages;
        Subtitle subtitle;
        boolean is_season_display;
        User_garb user_garb;
        Honor_reply honor_reply;
        String like_icon;
        boolean need_jump_bv;
        boolean disable_show_up_info;
        short is_story_play;
        boolean is_view_self;

        public static class Desc_v2 {
            String raw_text;
            int type;
            int biz_id;
        }

        public static class Rights {
            short bp;
            short elec;
            short download;
            short movie;
            short pay;
            short hd5;
            short no_reprint;
            short autoplay;
            short ugc_pay;
            short is_cooperation;
            short ugc_pay_preview;
            short no_background;
            short clean_mode;
            short is_stein_gate;
            short is_360;
            short no_share;
            short arc_pay;
            short free_watch;
        }

        public static class Owner {
            long mid;
            String name;
            String face;
        }

        public static class Stat {
            long aid;
            long view;
            long danmaku;
            long reply;
            long favorite;
            long coin;
            long share;
            int now_rank;
            int his_rank;
            long like;
            long dislike;
            String evaluation;
            int vt;
        }

        public static class Argue_info {
            String argue_msg;
            int argue_type;
            String argue_link;
        }

        public static class Dimension {
            int width;
            int height;
            int rotate;
        }

        public static class Page {
            long cid;
            int page;
            String from;
            String part;
            int duration;
            String vid;
            String weblink;
            Dimension dimension;
            String first_frame;
            int ctime;
        }

        public static class Subtitle {
            boolean allow_submit;
            St[] list;

            public static class St {
                int id;
                String lan;
                String lan_doc;
                boolean is_lock;
                long author_mid;
                String subtitle_url;
                Author author;

                public static class Author {
                    long mid;
                    String name;
                    String sex;
                    String face;
                    String sign;
                    short rank;
                    int birthday;
                    short is_fake_account;
                    short is_deleted;
                }
            }
        }

        public static class User_garb {
            String url_image_ani_cut;
        }

        public static class Honor_reply {
            Honor[] honor;

            public static class Honor {
                String aid;
                short type; //1：入站必刷收录 2：第?期每周必看 3：全站排行榜最高第?名 4：热门
                String desc;
                int weekly_recommend_num;
            }
        }
    }
}

package com.nekoyu.Universe.AIChat.WebSearch.BiliBiliAPI;

import com.google.gson.annotations.SerializedName;

public class UserInfo {
    public int code;
    public String message;
    public int ttl;
    public Data data;

    public static class Data {
        public long mid; // UID
        public String name; // 用户名
        public String sex; // 性别
        public String face; // 头像
        public int face_nft;
        public int face_nft_type;
        public String sign; // 个性签名
        public int rank;
        public short level; // 等级
        public long jointime; // 滚木
        public int moral; // 滚木
        public int silence; // 滚木
        public int coins; // 滚木
        public boolean fans_badge;
        public Fans_medal fans_medal;
        public Official official;
        public Vip vip;
        public Pendant pendant;
        public Nameplate nameplate;
        public User_honour_info user_honour_info;
        public boolean is_followed;
        public String top_photo;
        public Sys_notice sys_notice;
        public Live_room live_room;
        public String birthday;
        public School school;
        public Profession profession;
        public Object tags;
        public Series series;
        public short is_senior_member;
        public MCN_info mcn_info;
        public short gaia_res_type;
        public Object gaia_data;
        public boolean is_risk;
        public Elec elec;
        // contract
        public boolean certificate_show;
        // name_render
        public Top_photo_v2 top_photo_v2;
        // theme
        // attestation

        public static class Fans_medal {
            public boolean show;
            public boolean wear;
            public Medal medal;
            public Detail detail;

            public static class Medal {
                public int level;
                public int guard_level;
                public int medal_color;
                public String medal_name;
                public int medal_color_border;
                public int medal_color_start;
                public int medal_color_end;
            }
            public static class Detail {
                public long uid;
                public String medal_color_end;
                public int level;
                public int guard_level;
                public String first_icon;
                public String second_icon;
                public String medal_color_level;
                public String medal_color_name;
                public int medal_level_bg_color;
                public String medal_name;
                public int medal_id;
                public String medal_color;
                public String medal_color_border;
            }
        }
        public static class Official {
            public short role;
            public String title;
            public String desc;
            public short type;
        }
        public static class Vip {
            public short type;
            public short status;
            public long due_date;
            public int vip_pay_type;
            public short theme_type;
            public Label label;
            public int avatar_subscript;
            public String nickname_color;
            public short role;
            public String avatar_subscript_url;
            public short tv_vip_status;
            public short tv_vip_pay_type;
            public long tv_due_date;
            public Avatar_icon avatar_icon;
            public Ott_info ott_info;
            public Super_vip super_vip;

            public static class Label {
                public String path;
                public String text;
                public String label_theme;
                public String text_color;
                public short bg_style;
                public String bg_color;
                public String border_color;
                public boolean use_img_label;
                public String img_label_uri_hans;
                public String img_label_uri_hant;
                public String img_label_uri_hans_static;
                public String img_label_uri_hant_static;
                public int label_id;
                public Label_goto label_goto;

                public static class Label_goto {
                    public String mobile;
                    public String pc_web;
                }
            }

            public static class Avatar_icon {
                public short icon_type;
                public Icon_resource icon_resource;

                public static class Icon_resource {}
            }

            public static class Ott_info {
                public short vip_type;
                public short pay_type;
                public String pay_channel_id;
                public short status;
                public long overdue_time;

            }

            public static class Super_vip {
                public boolean is_super_vip;
            }
        }
        public static class Pendant {
            public int pid;
            public String name;
            public String image;
            public int expire;
            public String image_enhance;
            public String image_enhance_frame;
            public int n_pid;
        }
        public static class Nameplate {
            public int nid;
            public String name;
            public String image;
            public String image_small;
            public String level;
            public String condition;
        }
        public static class User_honour_info {
            public long mid;
            public Object colour;
            public Object[] tags;
            public short is_latest_100honour;
        }
        public static class Sys_notice {} // 该用户存在争议，我觉得没啥用就没写
        public static class Live_room {
            public short roomStatus;
            public short liveStatus;
            public String url;
            public String title;
            public String cover;
            public long roomid;
            public short roundStatus;
            public short broadcast_type;
            public Watched_show watched_show;

            public static class Watched_show {
                @SerializedName("switch")
                public boolean switch_;
                public int num;
                public int text_small;
                public String text_large;
                public String icon;
                public String icon_location;
                public String icon_web;
            }
        }
        public static class School {
            public String name;
        }
        public static class Profession {
            public String name;
            public String department;
            public String title;
            public short is_show;
        }
        public static class Series {
            public short user_upgrade_status;
            public boolean show_upgrade_window;
        }
        public static class MCN_info {} // 没必要
        public static class Elec {} // 充电 有用吗
        public static class Top_photo_v2 {
            public int sid;
            public String l_img;
            public String l_200h_img;
        }
    }
}

package com.nekoyu.Universe.AIChat.Web.BiliBiliAPI;

import java.util.ArrayList;
import java.util.Map;

public class CommentList {
    public int code;
    public String message;
    public int ttl; // 1
    public Data data;

    public static class Data {
        public Page page;
        public Config config;
        public ArrayList<Reply> replies;
        public Upper upper;
        public Object top;
        public short vote;
        public short blacklist;
        public short assist;
        public short mode;
        public short[] support_mode;
        public Control control;
        public Folder folder;

        public static class Page {
            public int num;
            public int size;
            public int count;
            public int acount;
        }
        public static class Config {
            public short showtopic;
            public boolean show_up_flag;
            public boolean read_only;
        }
        public static class Reply {
            public long rpid;
            public long oid;
            public short type;
            public long mid;
            public int root;
            public int parent;
            public int dialog;
            public int count;
            public int rcount;
            public int state;
            public int fansgrade;
            public int attr;
            public int ctime;
            public String mid_str;
            public String oid_str;
            public String rpid_str;
            public String root_str;
            public String parent_str;
            public String dialog_str;
            public int like;
            public int action;
            public Member member;
            public Content content;
            public Object replies;
            public int assist;
            public Up_action up_action;
            public boolean invisible;
            public Card_label[] card_label;
            public Reply_control reply_control;
            public Folder folder;
            public String dynamic_id_str;
            public String note_cvid_str;
            public String track_info;

            public static class Member {
                public long mid;
                public String uname;
                public String sex;
                public String sign;
                public String avatar;
                public String rank;
                public int face_nft_new;
                public short is_senior_member;
                public Object senior;
                public Level_info level_info;
                public Pendant pendant;
                public UserInfo.Data.Nameplate nameplate;
                public Official_verify official_verify;
                public Vip vip;
                public Object fans_detail;
                public User_sailing user_sailing;
                public Object user_sailing_v2;
                public boolean is_contractor;
                public String contract_desc;
                public Object nft_interaction;
                public Avatar_item avatar_item;

                public static class Level_info {
                    public int current_level;
                    public int current_min;
                    public int current_exp;
                    public int next_exp;
                }
                public static class Pendant {
                    public long pid;
                    public String name;
                    public String image;
                    public int expire;
                    public String image_enhance;
                    public String image_enhance_frame;
                    public long n_pid;
                }
                public static class Official_verify {
                    public short type;
                    public String desc;
                }
                public static class Vip {
                    public short vipType;
                    public long vipDueDate;
                    public String dueRemark;
                    public int accessStatus;
                    public String accessStatusWarn;
                    public short themeType;
                    public UserInfo.Data.Vip.Label label;
                    public int avatar_subscript;
                    public String nickname_color;
                }
                public static class User_sailing {
                    public Object pendant;
                    public Object cardbg;
                    public Object cardbg_with_focus;
                }
                public static class Avatar_item {
                    public Container_size container_size;
                    public Fallback_layers fallback_layers;
                    public long mid;

                    public static class Container_size {
                        public double width;
                        public double height;
                    }
                    public static class Fallback_layers {
                        public Layer[] layers;
                        public boolean is_critical_group;

                        public static class Layer {
                            public boolean visible;
                            public General_spec general_spec;
                            public Layer_config layer_config;
                            public Resource resource;

                            public static class General_spec {
                                public Pos_spec pos_spec;
                                public Size_spec size_spec;
                                public Render_spec render_spec;

                                public static class Pos_spec {
                                    public double coordinate_pos;
                                    public double axis_x;
                                    public double axis_y;
                                }
                                public static class Size_spec {
                                    public double width;
                                    public double height;
                                }
                                public static class Render_spec     {
                                    public double opacity;
                                }
                            }
                            public static class Layer_config {
                                public Tags tags;
                                public boolean is_critical;

                                public static class Tags {
                                    public Object AVATAR_LAYER;
                                    public General_cfg GENERAL_CFG;

                                    public static class General_cfg {
                                        public short config_type;
                                        public General_config general_config;

                                        public static class General_config {
                                            public Web_css_style web_css_style;

                                            public static class Web_css_style {
                                                public String borderRadius;
                                            }
                                        }
                                    }
                                }
                            }
                            public static class Resource {
                                public short res_type;
                                public Res_image res_image;

                                public static class Res_image {
                                    public Image_src image_src;

                                    public static class Image_src {
                                        public short src_type;
                                        public short placeholder;
                                        public Remote remote;

                                        public static class Remote {
                                            public String url;
                                            public String bfs_style;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            public static class Content {
                public String message;
                public Object members;
                public Map<String, Emote> emote;
                public Object jump_url;
                public int max_line;

                public static class Emote {
                    public int id;
                    public int package_id;
                    public int state;
                    public short type;
                    public int attr;
                    public String text;
                    public String url;
                    public Meta meta;
                    public long mtime;
                    public String jump_title;

                    public static class Meta {
                        public int size;
                        public String[] suggest;
                    }
                }
            }
            public static class Up_action {
                public boolean like;
                public boolean reply;
            }
            public static class Card_label {
                public long rpid;
                public String text_content;
                public String text_color_day;
                public String text_color_night;
                public String label_color_day;
                public String label_color_night;
                public String image;
                public short type;
                public String background;
                public String background_width;
                public String background_height;
                public String jump_url;
                public int effect;
                public int effect_start_time;
            }
            public static class Reply_control {
                public boolean following;
                public int max_line;
                public boolean is_contractor;
                public String contract_desc;
                public String time_desc;
                public String location;
                public short translation_switch;
                public boolean support_share;
            }
        }
        public static class Upper {
            public long mid;
            public Object top;
            public Object vote;
        }
        public static class Control {
            public boolean input_disable;
            public String root_input_text;
            public String child_input_text;
            public String giveup_input_text;
            public short screenshot_icon_state;
            public short upload_picture_icon_state;
            public String answer_guide_text;
            public String answer_guide_icon_url;
            public String answer_guide_ios_url;
            public String answer_guide_android_url;
            public String bg_text;
            public Object empty_page;
            public short show_type;
            public String show_text;
            public boolean web_selection;
            public boolean disable_jump_emote;
            public boolean enable_charged;
            public boolean enable_cm_biz_helper;
            public Object preload_resources;
        }
        public static class Folder {
            public boolean has_folded;
            public boolean is_folded;
            public String rule;
        }
    }
}

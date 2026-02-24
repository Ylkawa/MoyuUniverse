package com.nekoyu.Universe.AIChat.Web.BiliBiliAPI;

import java.util.ArrayList;
import java.util.Map;

public class DynamicList {
    public int code;
    public String message;
    public int ttl; // 恒为 1
    public Data data;

    public static class Data {
        public boolean has_more;
        public ArrayList<Item> items;
        public String offset;
        public String update_baseline;
        public int update_num;

        public static class Item {
            public Basic basic;
            public String id_str;
            public ArrayList<Modules> modules;
            public String type;
            public boolean visible;
            public Object orig;

            public static class Basic {
                public String comment_id_str;
                public int comment_type;
                public Like_icon like_icon;
                public String rid_str;

                /**
                 * 这个类应该没用
                 */
                public static class Like_icon {
                    public String action_url; // ""
                    public String end_url; // ""
                    public int id; // 0
                    public String start_url; // ""
                }
            }

            public static class Modules {
                public Module_author module_author;
                public Module_dynamic module_dynamic;
                // public Module_more module_more;
                public Module_stat module_stat;
                // public Module_interaction module_interaction;
                // public Module_fold module_fold;
                // public Module_dispute module_dispute;
                // public Module_tag module_tag;
                public Module_desc module_desc;
                public String module_type;

                public static class Module_author {
                    public Decorate_card decorate_card;
                    public boolean is_top;
                    public More more;
                    public String pub_text;
                    public int pub_ts;
                    public Object relation;
                    public boolean show_follow;
                    public User user;

                    public static class Decorate_card {
                        public String big_card_url;
                        public short card_type;
                        public String card_type_name;
                        public String card_url;
                        public Fan fan;
                        public long id;
                        public String image_enhance;
                        public long item_id;
                        public String jump_url;
                        public String name;

                        public static class Fan {
                            public String color;
                            public Color_format color_format;
                            public short is_fan;
                            public String name;
                            public int num_desc;
                            public int number;

                            public static class Color_format {
                                public String[] colors;
                                public String end_point;
                                public int[] gradients;
                                public String start_point;
                            }
                        }
                    }

                    public static class More {
                        public Three_point_items[] three_point_items;

                        public static class Three_point_items {
                            public String label;
                            public Map<String, Object> params;
                            public String type;
                        }
                    }

                    public static class User {
                        public String face;
                        public boolean face_nft;
                        public long mid;
                        public String name;
                        public UserInfo.Data.Official official;
                        public UserInfo.Data.Pendant pendant;
                        public UserInfo.Data.Vip vip;
                    }
                }

                public static class Module_dynamic {
                    public Dyn_draw dyn_draw;
                    public Dyn_archive dyn_archive;

                    public static class Dyn_draw {
                        public long id;
                        public Item[] items;

                        public static class Items {
                            public int height;
                            public double size;
                            public String src;
                            public int width;
                        }
                    }

                    public static class Dyn_archive {
                        public long aid;
                        public boolean autoplay;
                        public Badge badge;
                        public String bvid;
                        public String cover;
                        public Object desc;
                        public String duration_text;
                        public Object epid;
                        public Open_player_params open_player_params;
                        public Stat stat;
                        public String title;
                        public short type;

                        public static class Badge {
                            public String bg_color; // CSS
                            public String color; // CSS
                            public String text;
                        }

                        public static class Open_player_params {
                            public String bvid;
                            public String type;
                        }

                        public static class Stat {
                            public String danmaku;
                            public String like;
                            public String play;
                        }
                    }
                }

                public static class Module_stat {
                    public Comment comment;
                    public Forward forward;
                    public Like like;

                    public static class Comment {
                        public long comment_id;
                        public short comment_type;
                        public int count;
                        public short type;
                    }

                    public static class Forward {
                        public int count;
                        public short type;
                    }

                    public static class Like {
                        public int count;
                        public boolean like_state;
                    }
                }

                public static class Module_desc {
                    public Rich_text_node[] rich_text_nodes;
                    public String text;

                    public static class Rich_text_node {
                       public String jump_url;
                       public String orig_text;
                       public String text;
                       public String type;
                    }
                }
            }
        }
    }
}

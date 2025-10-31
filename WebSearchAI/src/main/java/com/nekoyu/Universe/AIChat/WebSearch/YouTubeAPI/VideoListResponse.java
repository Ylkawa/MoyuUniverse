package com.nekoyu.Universe.AIChat.WebSearch.YouTubeAPI;

import com.google.gson.annotations.SerializedName;

@SuppressWarnings("unused")
public class VideoListResponse extends Response {
    public Item[] items;

    public static class Item {
        public String kind;
        public String etag;
        public String id;
        public Snippet snippet;
        public ContentDetails contentDetails;
        public Statistics statistics;

        public static class Snippet {
            public String publishedAt;
            public String channelId;
            public String title;
            public String description;
            public Thumbnails thumbnails;
            public String channelTitle;
            public String[] tags;
            public String categoryId;
            public String liveBroadcastContent;
            public String defaultLanguage;
            public Localized localized;
            public String defaultAudioLanguage;

            public static class Thumbnails {
                @SerializedName("default")
                public Set default_;
                public Set medium;
                public Set high;
                public Set standard;
                public Set maxres;

                public static class Set {
                    public String url;
                    public String width;
                    public String height;
                }
            }
            public static class Localized {
                public String title;
                public String description;
            }
        }
        public static class ContentDetails {
            public String duration;
            public String dimension;
            public String definition;
            public String caption;
            public boolean licensedContent;
            public Object contentRating;
            public String projection;
        }
        public static class Statistics {
            public String viewCount;
            public String likeCount;
            public String favoriteCount;
            public String commentCount;
        }
    }
}

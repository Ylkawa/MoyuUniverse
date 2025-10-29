package com.nekoyu.Universe.AIChat.WebSearch.YouTubeAPI;

import com.google.gson.annotations.SerializedName;

@SuppressWarnings("unused")
public class VideoListResponse {
    String kind;
    String etag;
    Item[] items;
    PageInfo pageInfo;

    public static class Item {
        String kind;
        String etag;
        String id;
        Snippet snippet;
        ContentDetails contentDetails;
        Statistics statistics;

        public static class Snippet {
            String publishedAt;
            String channelId;
            String title;
            String description;
            Thumbnails thumbnails;
            String channelTitle;
            String[] tags;
            String categoryId;
            String liveBroadcastContent;
            String defaultLanguage;
            Localized localized;
            String defaultAudioLanguage;

            public static class Thumbnails {
                @SerializedName("default")
                Set default_;
                Set medium;
                Set high;
                Set standard;
                Set maxres;

                public static class Set {
                    String url;
                    String width;
                    String height;
                }
            }
            public static class Localized {
                String title;
                String description;
            }
        }
        public static class ContentDetails {
            String duration;
            String dimension;
            String definition;
            String caption;
            boolean licensedContent;
            Object contentRating;
            String projection;
        }
        public static class Statistics {
            String viewCount;
            String likeCount;
            String favoriteCount;
            String commentCount;
        }
    }

    public static class PageInfo {
        int totalResults;
        int resultsPerPage;
    }
}

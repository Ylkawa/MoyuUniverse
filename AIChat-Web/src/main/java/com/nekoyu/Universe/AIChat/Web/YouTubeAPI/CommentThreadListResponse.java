package com.nekoyu.Universe.AIChat.Web.YouTubeAPI;

@SuppressWarnings("unused")
public class CommentThreadListResponse extends Response {
    public String nextPageToken;
    public Item[] items;

    public static class Item {
        public String kind;
        public String etag;
        public String id;
        public Snippet snippet;

        public static class Snippet {
            public String channelId;
            public String videoId;
            public TopLevelComment topLevelComment;
            public boolean canReply;
            public int totalReplyCount;
            public boolean isPublic;

            public static class TopLevelComment {
                public String kind;
                public String etag;
                public String id;
                public TopLevelCommentSnippet snippet;

                public static class TopLevelCommentSnippet {
                    public String channelId;
                    public String videoId;
                    public String textDisplay;
                    public String textOriginal;
                    public String authorDisplayName;
                    public String authorProfileImageUrl;
                    public String authorChannelUrl;
                    public AuthorChannelId authorChannelId;
                    public boolean canRate;
                    public String viewerRating;
                    public int likeCount;
                    public String publishedAt;
                    public String updatedAt;

                    public static class AuthorChannelId {
                        public String value;
                    }
                }
            }
        }
    }
}

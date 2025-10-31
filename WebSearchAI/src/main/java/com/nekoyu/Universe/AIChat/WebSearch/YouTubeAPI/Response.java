package com.nekoyu.Universe.AIChat.WebSearch.YouTubeAPI;

@SuppressWarnings("unused")
public class Response {
    String kind;
    String etag;
    PageInfo pageInfo;

    public static class PageInfo {
        int totalResults;
        int resultsPerPage;
    }
}

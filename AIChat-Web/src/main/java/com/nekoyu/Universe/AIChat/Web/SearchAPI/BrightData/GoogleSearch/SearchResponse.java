package com.nekoyu.Universe.AIChat.Web.SearchAPI.BrightData.GoogleSearch;

import com.nekoyu.Universe.AIChat.Web.SearchAPI.SearchResult;

import java.net.URL;
import java.util.List;

public class SearchResponse { // Light JSON response
    List<Item> organic;

    public static class Item {
        URL link;
        String title;
        String description;
        List<SearchResult.Extension> extensions;
        int global_rank;
    }
}

package com.nekoyu.Universe.AIChat.Web.SearchAPI.SerpApi.GoogleSearch;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

public class SearchResponse {
    public SearchMetadata search_metadata;
    public SearchParameters search_parameters;
    public SearchInformation search_information;
    public JsonObject knowledge_graph;
    public List<OrganicResult> organic_results;
    public List<RelatedSearch> related_searches;
    public List<RelatedQuestion> related_questions;
    public Pagination pagination;
    public SerpapiPagination serpapi_pagination;

    public static class SearchMetadata {
        public String id;
        public String status;
        public String json_endpoint;
        public String created_at;
        public String processed_at;
        public String google_url;
        public String raw_html_file;
        public double total_time_taken;
    }

    public static class SearchParameters {
        public String engine;
        public String q;
        public String location_requested;
        public String location_used;
        public String google_domain;
        public String hl;
        public String gl;
        public String safe;
        public int start;
        public String device;
    }

    public static class SearchInformation {
        public String organic_results_state;
        public String query_displayed;
        public long total_results;
        public int page_number;
        public double time_taken_displayed;
    }

    public static class OrganicResult {
        public int position;
        public String title;
        public String link;
        public String redirect_link;
        public String displayed_link;
        public String favicon;
        public String thumbnail;
        public String date;
        public String snippet;
        public List<String> snippet_highlighted_words;
        public AboutThisResult about_this_result;
        public String about_page_link;
        public String about_page_serpapi_link;
        public String cached_page_link;
        public String related_pages_link;
        public RichSnippet rich_snippet;
        public SiteLinks sitelinks;

        public static class SiteLinks {
            public List<SiteLink> expanded;
        }

        public static class SiteLink {
            public String title;
            public String link;
            public String snippet;
        }
    }

    public static class AboutThisResult {
        public Source source;
        public List<String> keywords;
        public List<String> related_keywords;
        public List<String> languages;
        public List<String> regions;
    }

    public static class Source {
        public String description;
        public String source_info_link;
        public String icon;
    }

    public static class RichSnippet {
        public Top top;
    }

    public static class Top {
        public DetectedExtensions detected_extensions;
        public List<String> extensions;
    }

    public static class DetectedExtensions {
        public double rating;
        public int review_by_jennaviles;
    }

    public static class RelatedSearch {
        public int block_position;
        public String query;
        public String link;
        public String serpapi_link;
    }

    public static class RelatedQuestion {
        public String question;
        public String type;
        public List<List<String>> table;
        public Map<String, Map<String, String>> formatted;
        public String title;
        public String link;
        public String displayed_link;
        public String next_page_token;
        public String serpapi_link;
    }

    public static class Pagination {
        public int current;
        public String previous;
        public String next;
        public Map<String, String> other_pages;
    }

    public static class SerpapiPagination {
        public int current;
        public String previous_link;
        public String previous;
        public String next_link;
        public String next;
        public Map<String, String> other_pages;
    }
}

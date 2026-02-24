package com.nekoyu.Universe.AIChat.Web.SearchAPI.Google;

import java.util.List;
import java.util.Map;

@SuppressWarnings("unused")
public class SearchResponse {

    public String kind;
    public Url url;
    public Queries queries;
    public List<Promotion> promotions;
    public Map<String, Object> context;
    public SearchInformation searchInformation;
    public Spelling spelling;
    public List<Item> items;

    public static class Url {
        public String type;
        public String template;
    }

    public static class Queries {
        public List<Page> previousPage;
        public List<Page> request;
        public List<Page> nextPage;
    }

    public static class Page {
        public String title;
        public String totalResults;
        public String searchTerms;
        public int count;
        public int startIndex;
        public int startPage;
        public String language;
        public String inputEncoding;
        public String outputEncoding;
        public String safe;
        public String cx;
        public String sort;
        public String filter;
        public String gl;
        public String cr;
        public String googleHost;
        public String disableCnTwTranslation;
        public String hq;
        public String hl;
        public String siteSearch;
        public String siteSearchFilter;
        public String exactTerms;
        public String excludeTerms;
        public String linkSite;
        public String orTerms;
        public String relatedSite;
        public String dateRestrict;
        public String lowRange;
        public String highRange;
        public String fileType;
        public String rights;
        public String searchType;
        public String imgSize;
        public String imgType;
        public String imgColorType;
        public String imgDominantColor;
    }

    public static class Promotion {
        public Map<String, Object> promotion;
    }

    public static class SearchInformation {
        public double searchTime;
        public String formattedSearchTime;
        public int totalResults;
        public String formattedTotalResults;
    }

    public static class Spelling {
        public String correctedQuery;
        public String htmlCorrectedQuery;
    }

    public static class Item {
        public String kind;
        public String title;
        public String htmlTitle;
        public String link;
        public String displayLink;
        public String snippet;
        public String htmlSnippet;
        public String cacheId;
        public String formattedUrl;
        public String htmlFormattedUrl;
        public Map<String, List<Map<String, String>>> pagemap;
        public String mime;
        public String fileFormat;
        public Image image;
        public List<Label> labels;

        public static class Image {
            public String contextLink;
            public int height;
            public int width;
            public int byteSize;
            public String thumbnailLink;
            public int thumbnailHeight;
            public int thumbnailWidth;
        }

        public static class Label {
            public String name;
            public String displayName;
            public String label_with_op;
        }
    }
}

package com.nekoyu.Universe.AIChat.WebSearch.GoogleWebSearchAPI;

import java.util.List;
import java.util.Map;

public class Response {
    String kind;
    URL url;
    Queries queries;

    public class URL {
        String type;
        String template;
    }
    public class Queries {
        List<Page> previousPage;
        List<Page> request;
        List<Page> nextPage;
        List<Promotion> promotions;
        Context context;
        SearchInformation searchInformation;
        Spelling spelling;
        List<Item> items;

        public class Page {
            String title;
            String totalResults;
            String searchTerms;
            int count;
            int startIndex;
            int startPage;
            String language;
            String inputEncoding;
            String outputEncoding;
            String safe;
            String cx;
            String sort;
            String filter;
            String gl;
            String cr;
            String googleHost;
            String disableCnTwTranslation;
            String hq;
            String hl;
            String siteSearch;
            String siteSearchFilter;
            String exactTerms;
            String excludeTerms;
            String linkSite;
            String orTerms;
            String relatedSite;
            String dateRestrict;
            String lowRange;
            String highRange;
            String fileType;
            String rights;
            String searchType;
            String imgSize;
            String imgType;
            String imgColorType;
            String imgDominantColor;
        }

        public class Promotion {
            String title;
            String htmlTitle;
            String link;
            String displayLink;
            List<BodyLine> bodyLines;
            Image image;

            public class BodyLine {
                String title;
                String htmlTitle;
                String url;
                String link;
            }

            public class Image {
                String source;
                int width;
                int height;
            }
        }

        public class Context {
            String title;
        }

        public class SearchInformation {
            double searchTime;
            String formattedSearchTime;
            int totalResults;
            String formattedTotalResults;
        }

        public class Spelling {
            String correctedQuery;
            String htmlCorrectedQuery;
        }

        public class Item {
            String kind;
            String title;
            String htmlTitle;
            String link;
            String displayLink;
            String snippet;
            String htmlSnippet;
            String cacheId;
            String formattedUrl;
            String htmlFormattedUrl;
            Map<String, List<Map<String, String>>> pagemap;
            String mime;
            String fileFormat;
            Image image;
            List<Label> labels;

            public class Image {
                String contextLink;
                int height;
                int width;
                int byteSize;
                String thumbnailLink;
                int thumbnailHeight;
                int thumbnailWidth;
            }

            public class Label {
                String name;
                String displayName;
                String label_with_op;
            }
        }
    }
}

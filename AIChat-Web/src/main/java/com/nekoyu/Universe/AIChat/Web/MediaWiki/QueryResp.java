package com.nekoyu.Universe.AIChat.Web.MediaWiki;

import java.util.Map;

public class QueryResp {
    Query query;

    public static class Query {
        Map<String, Page> pages;

        public static class Page {
            int pageid;
            int ns;
            String title; // 标题
            String extract; // 正文
        }
    }

    @Override
    public String toString() {
        if (query == null || query.pages == null) return "No result";
        StringBuilder s = new StringBuilder();
        for (Map.Entry<String, Query.Page> entry : query.pages.entrySet()) {
            s.append(entry.getKey()).append(" - ").append(entry.getValue().title).append("\n").append(entry.getValue().extract).append("\n\n");
        }
        return s.toString();
    }
}

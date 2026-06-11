package com.nekoyu.Universe.AIChat.Web.MediaWiki;

import java.util.List;
import java.util.Map;

public class QueryRespBiligames {
    Query query;

    public static class Query {
        Map<String, Page> pages;

        public static class Page {
            int pageid;
            int ns;
            String title; // 标题
            List<Map<String, Object>> revisions; // 正文
        }
    }
}

package com.nekoyu.Universe.AIChat.Web;

public class Config {
    boolean EnableProxy;
    String HttpProxyURI;
    String GoogleAPIKey;
    String SearchProvider;
    boolean EnableGoogleSearch;
    boolean EnableYouTubeAPI;
    String SearchEngineID;
    String SerpApiKey;
    SearchParam SearchParam = new SearchParam();

    public Config() {
        EnableProxy = false;
        HttpProxyURI = "";
        GoogleAPIKey = null;
        SearchEngineID = "";
        EnableGoogleSearch = true;
        EnableYouTubeAPI = true;
    }

    public static class SearchParam {
        public String language;
        public String country;
        public String location;
    }
}

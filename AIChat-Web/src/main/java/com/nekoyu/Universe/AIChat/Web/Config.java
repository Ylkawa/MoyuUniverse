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
    String BrightDataApiKey;
    String BrightDataZone;
    SearchParam SearchParam = new SearchParam();
    SeleniumConfig Selenium = null;

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

    public static class SeleniumConfig {
        String URL;
    }
}

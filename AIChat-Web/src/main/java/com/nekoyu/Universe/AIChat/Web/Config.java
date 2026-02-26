package com.nekoyu.Universe.AIChat.Web;

public class Config {
    boolean EnableProxy;
    String HttpProxyURI;
    String GoogleAPIKey;
    String SearchProvider;
    boolean EnableGoogleSearch;
    boolean EnableYouTubeAPI;
    String SearchEngineID;
    SearchParam additionalParams = new SearchParam();

    public Config() {
        EnableProxy = false;
        HttpProxyURI = "";
        GoogleAPIKey = null;
        SearchEngineID = "";
        EnableGoogleSearch = true;
        EnableYouTubeAPI = true;
    }

    private static class SearchParam {
        String language;
        String country;
        String location;
    }
}

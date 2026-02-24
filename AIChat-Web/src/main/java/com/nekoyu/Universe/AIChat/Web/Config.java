package com.nekoyu.Universe.AIChat.Web;

public class Config {
    boolean EnableProxy;
    String HttpProxyURI;
    String GoogleAPIKey;
    String SearchProvider;
    boolean EnableGoogleSearch;
    boolean EnableYouTubeAPI;
    String SearchEngineID;

    public Config() {
        EnableProxy = false;
        HttpProxyURI = "";
        GoogleAPIKey = null;
        SearchEngineID = "";
        EnableGoogleSearch = true;
        EnableYouTubeAPI = true;
    }
}

package com.nekoyu.Universe.AIChat.WebSearch;

import java.util.ArrayList;
import java.util.List;

public class Config {
    boolean EnableProxy;
    String HttpProxyURI;
    String GoogleAPIKey;
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

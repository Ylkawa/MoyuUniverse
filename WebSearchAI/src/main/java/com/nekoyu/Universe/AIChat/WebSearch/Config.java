package com.nekoyu.Universe.AIChat.WebSearch;

import java.util.ArrayList;
import java.util.List;

public class Config {
    boolean EnableProxy;
    String HttpProxyURI;
    String SearchAPIKey;
    String SearchEngineID;

    public Config() {
        EnableProxy = false;
        HttpProxyURI = "";
        SearchAPIKey = "";
        SearchEngineID = "";
    }
}

package com.nekoyu.Universe.AIChat.Web.SearchAPI;

import com.nekoyu.Universe.AIChat.Web.Config;

import java.io.IOException;

public abstract class SearchClient {
    protected Config.SearchParam searchParam;

    public abstract SearchResult search(String query) throws IOException;

    public void setSearchParam(Config.SearchParam searchParam) {
        this.searchParam = searchParam;
    }
}

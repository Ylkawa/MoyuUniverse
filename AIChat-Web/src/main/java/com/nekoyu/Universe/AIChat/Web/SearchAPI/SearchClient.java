package com.nekoyu.Universe.AIChat.Web.SearchAPI;

import java.io.IOException;

public abstract class SearchClient {
    public abstract SearchResult search(String query) throws IOException;
}

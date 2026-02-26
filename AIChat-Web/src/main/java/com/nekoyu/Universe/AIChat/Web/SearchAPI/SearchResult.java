package com.nekoyu.Universe.AIChat.Web.SearchAPI;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class SearchResult {
    @Nullable
    public String overview = null;
    public String resultFor = null;
    public List<Item> items;

    public SearchResult() {
        items = new ArrayList<Item>();
    }

    public static class Item {
        @Nullable
        public URL image = null;
        public URL link = null;
        public String title = null;
        public String snippet = null;
    }

    public static class SiteItem extends Item {
        public List<SiteLink> siteLinks;

        public SiteItem() {
            siteLinks = new ArrayList<>();
        }

        public static class SiteLink {
            public String title;
            public URL link;
        }
    }

    public static class LocationItem extends Item {
        public double latitude;
        public double longitude;
    }
}

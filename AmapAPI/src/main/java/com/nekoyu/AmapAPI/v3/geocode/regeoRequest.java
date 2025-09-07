package com.nekoyu.AmapAPI.v3.geocode;

import java.util.HashMap;
import java.util.Map;

public class regeoRequest {
    public Map<String, String> args;

    public regeoRequest() {
        args = new HashMap<>();
        args.put("output", "JSON");
    }

    public void setLocation(String location) {
        args.put("location", location);
    }

    public void setExtensions(String extensions) {
        args.put("extensions", extensions);
    }

    public void setRadius(String radius) {
        args.put("radius", radius);
    }
}

package com.nekoyu.MoyuUniverse.Nya.OnebotAdapter;

import java.util.HashMap;
import java.util.Map;

public class OBRequest {

    protected String action;
    protected Map params = new HashMap<>();
    String echo;

    public OBRequest() {}

    public OBRequest(String action, String echo) {
        this.action = action;
        this.echo = echo;
    }

    public OBRequest(String action) {
        this.action = action;
    }
}

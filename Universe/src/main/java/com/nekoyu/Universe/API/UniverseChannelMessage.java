package com.nekoyu.Universe.API;

import java.util.HashMap;
import java.util.Map;

public class UniverseChannelMessage {
    public String tag;
    public Map args;
    public String message;

    public UniverseChannelMessage() {
        args = new HashMap<>();
    }
}
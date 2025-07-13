package com.nekoyu.Universe.API;

import java.util.Map;

public interface UniverseListener {
    public void onMessage(String ID, String message, Map args);
}

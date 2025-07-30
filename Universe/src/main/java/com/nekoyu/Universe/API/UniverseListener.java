package com.nekoyu.Universe.API;

import java.util.Map;

public interface UniverseListener {
    public void onMessage(Planet planet, String message, Map args);
}

package com.nekoyu.Universe.ColorGroupName;

import java.util.HashMap;
import java.util.Map;

public class Config {
    public Map<String, Set> Settings = new HashMap<>();

    public static class Set {
        public String template;
        public int delay;

        public Set() {}
        public Set(String template, int delay) {
            this.template = template;
            this.delay = delay;
        }
    }
}

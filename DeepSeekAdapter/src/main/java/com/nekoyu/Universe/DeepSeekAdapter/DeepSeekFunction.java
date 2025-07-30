package com.nekoyu.Universe.DeepSeekAdapter;

public class DeepSeekFunction {
    String name;
    String description;

    CallbackFunction cf;

    public DeepSeekFunction(String name, String description, CallbackFunction cf) {
        this.name = name;
        this.description = description;
        this.cf = cf;
    }

    public interface CallbackFunction {
        public String function(String[] args);
    }
}

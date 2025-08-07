package com.nekoyu.Universe.DeepSeekAdapter;

import java.util.Map;

public class DeepSeekTool {
    public String type = "function";
    public Function function = new Function();

    public class Function {
        public String name;
        public String description;
        public Parameters parameters;
        public transient CallbackFunction cf;

        public static class Parameters {
            public String type;
            public Map<String, Property> properties;
            public String[] required;

            public class Property {
                public String type;
                public String description;
                // 可选: public String format; public String[] enum;
            }
        }
    }

    public DeepSeekTool(String name, String description, CallbackFunction cf) {
        this.function.name = name;
        this.function.description = description;
        this.function.cf = cf;
    }

    public interface CallbackFunction {
        String function(Map<String, String> args);
    }
}
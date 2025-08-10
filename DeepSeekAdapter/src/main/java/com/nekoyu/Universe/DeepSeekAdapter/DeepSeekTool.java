package com.nekoyu.Universe.DeepSeekAdapter;

import java.util.Map;

public class DeepSeekTool {
    public String type;
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

            public Parameters() {
                type = "object";
            }

            public static class Property {
                public String type;
                public String description;
                // 可选: public String format; public String[] enum;

                public Property(String type, String description) {
                    this.type = type;
                    this.description = description;
                }

                public Property(String description) {
                    this.type = "string";
                    this.description = description;
                }
            }
        }
    }

    public DeepSeekTool(String name, String description, CallbackFunction cf, Map<String, Function.Parameters.Property> properties, String[] required) {
        this.type  = "function";
        this.function.name = name;
        this.function.description = description;
        this.function.cf = cf;
        this.function.parameters = new Function.Parameters();
        this.function.parameters.properties = properties;
        this.function.parameters.required = required;
    }

    public interface CallbackFunction {
        String function(Map<String, String> args);
    }
}
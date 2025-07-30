package com.nekoyu.Universe.DeepSeekAdapter;

import java.util.Map;

public class AssistantRequest {
    public String model;
    public Message[] messages;
    public Tool[] tools;

    public static class Tool {
        public String type;
        public Function function;

        public static class Function {
            public String name;
            public String description;
            public Parameters parameters;

            public static class Parameters {
                public String type;
                public Map<String, Property> properties;
                public String[] required;

                public static class Property {
                    public String type;
                    public String description;
                    // 可选: public String format; public String[] enum;
                }
            }
        }
    }
}

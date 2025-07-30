package com.nekoyu.Universe.DeepSeekAdapter;

import java.util.Map;

public class AssistantResponse {
    public String id;
    public String object;
    public long created;
    public String model;
    public Choice[] choices;
    public Usage usage;
    public String system_fingerprint;

    public static class Choice {
        public int index;
        public Message message;

        public static class Message {
            public String role;
            public String content;
            public Tool_call[] tool_calls;

            public static class Tool_call {
                public int index;
                public String id;
                public String type;
                public Function function;

                public static class Function {
                    public String name;
                    public Map<String, String> arguments;
                }
            }
        }

        public String logprobs;
        public String finish_reason;
    }

    public static class Usage {
        public int prompt_tokens;
        public int completion_tokens;
        public int total_tokens;
        public Map<String, Integer> prompt_tokens_details;
        public int prompt_cache_hit_tokens;
        public int prompt_cache_miss_tokens;
    }
}

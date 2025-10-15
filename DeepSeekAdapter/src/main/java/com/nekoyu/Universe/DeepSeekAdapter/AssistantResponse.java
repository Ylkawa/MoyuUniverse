package com.nekoyu.Universe.DeepSeekAdapter;

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
        }

        public String logprobs;
        public String finish_reason;
    }

    public static class Usage {
        public int prompt_tokens;
        public int completion_tokens;
        public int total_tokens;
    }
}

package com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies;

import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Tool_call;

public class CompletionsResponse {
    public String id;
    public String object;
    public long created;
    public String model;
    public Choice[] choices;
    public Usage usage;
    public String system_fingerprint;

    public CompletionsResponse() {
        choices = new Choice[0];
        usage = new Usage();
    }

    public static class Choice {
        public int index;
        public Message message;

        public Choice() {
            message = new Message();
        }

        public static class Message {
            public String role;
            public String content;
            public Tool_call[] tool_calls;

            public Message() {
                tool_calls = null;
            }
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

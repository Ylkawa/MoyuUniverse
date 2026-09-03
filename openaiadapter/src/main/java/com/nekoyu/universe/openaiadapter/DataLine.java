package com.nekoyu.universe.openaiadapter;

import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.CompletionsResponse;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Tool_call;

public class DataLine {
    Choice[] choices;
    String object;
    CompletionsResponse.Usage usage;
    long created;
    String system_fingerprint;
    String model;
    String id;

    public static class Choice {
        Delta delta;
        int index;
        String finish_reason;

        public static class Delta {
            String reasoning_content;
            String content;
            String role;
            Tool_call[] tool_calls;
        }
    }
}

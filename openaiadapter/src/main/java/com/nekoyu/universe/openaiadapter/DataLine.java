package com.nekoyu.universe.openaiadapter;

import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.CompletionsResponse;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ExtraContent;
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
            /** 百炼 qwen4-max 密文帧：独立下发、一次返回，不得因 content 为空而过滤 */
            String encrypted_content;
            /** Gemini message 级加密思维签名 */
            ExtraContent extra_content;
            String content;
            String role;
            Tool_call[] tool_calls;
        }
    }
}

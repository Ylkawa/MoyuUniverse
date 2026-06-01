package com.nekoyu.universe.openaiadapter.RequestBodies;

import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.CompletionsRequest;

public class OpenAIReq extends CompletionsRequest {
    public Reasoning reasoning;

    public static class Reasoning {
        public enum Effort {none, low, medium, high, @SuppressWarnings("SpellCheckingInspection") xhigh}
        public Effort effort;
    }

    public OpenAIReq() {
        super();
        this.reasoning = new Reasoning();
    }
}

package com.nekoyu.universe.openaiadapter.RequestBodies;

public class OpenAIReq extends OpenAICompletionsRequest {
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

package com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies;

import com.google.gson.JsonElement;
import com.nekoyu.Universe.API.MessageChannel.MFChain;

public class LLMFunction {
    public String name;
    public String description;
    public JsonSchema parameters;
    public transient Callback callback;

    public LLMFunction() {
    }

    public LLMFunction(String name, String description, JsonSchema parameters, Callback callback) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
        this.callback = callback;
    }

    @FunctionalInterface
    public interface Callback {
        MFChain call(JsonElement args);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        LLMFunction function = new LLMFunction();

        public LLMFunction build() {
            return function;
        }

        public Builder name(String name) {
            function.name = name;
            return this;
        }

        public Builder description(String description) {
            function.description = description;
            return this;
        }

        public Builder parameters(JsonSchema parameters) {
            function.parameters = parameters;
            return this;
        }

        public Builder callback(Callback callback) {
            function.callback = callback;
            return this;
        }
    }
}
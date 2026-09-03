package com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies;

import com.google.gson.JsonElement;

import java.util.concurrent.atomic.AtomicReference;

public class LLMFunction {
    public String name;
    public String description;
    public JsonSchema parameters;
    public transient Callback callback;
    public Type type;

    public enum Type {
        asynchronous, synchronous
    }

    public interface Callback {
        Message callSync(JsonElement args);
        void callAsync(JsonElement args, Calling calling);
    }

    public interface SyncCallback extends Callback {
        @Override
        default void callAsync(JsonElement args, Calling calling) {
            calling.calling(callSync(args));
        }
    }

    public interface AsyncCallback extends SyncCallback {
        @Override
        default Message callSync(JsonElement args) {
            AtomicReference<Message> result = new AtomicReference<>();
            callAsync(args, message -> {
                if (result.get() == null) result.set(message);
                else result.get().content.addAll(message.content);
            });
            return result.get();
        }
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

        public Builder syncCallback(SyncCallback callback) {
            function.type = Type.synchronous;
            function.callback = callback;
            return this;
        }

        public Builder asyncCallback(AsyncCallback callback) {
            function.type = Type.asynchronous;
            function.callback = callback;
            return this;
        }
    }
}
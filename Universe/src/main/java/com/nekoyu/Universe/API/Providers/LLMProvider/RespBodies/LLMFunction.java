package com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies;

import java.util.*;

public class LLMFunction {
    public String name;
    public String description;
    public Parameters parameters;
    public transient Callback callback;

    public LLMFunction() {
        parameters = new Parameters();
    }

    public LLMFunction(String name, String description, Parameters parameters, Callback callback) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
        this.callback = callback;
    }

    public static class Parameters {
        String type;
        Map<String, Property> properties;
        List<String> required;

        public Parameters() {
            properties = new HashMap<>();
        }

        public Parameters(String type, String[] properties, String[] required) {
            this.type = type;
            this.properties = new HashMap<>();
            for (String property : properties) {
                this.properties.put(property, new Property("string"));
            }
            this.required = new ArrayList<>();
            this.required.addAll(Arrays.asList(required));
        }

        public static class Property {
            String type;

            public Property(String type) {
                this.type = type;
            }
        }
    }

    public static Builder Builder() {
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
        public Builder parameters(Parameters parameters) {
            function.parameters = parameters;
            return this;
        }
        public Builder parameters(String[] parameters, String[] required) {
            function.parameters = new Parameters("object", parameters, required);
            return this;
        }
        public Builder callback(Callback callback) {
            function.callback = callback;
            return this;
        }
    }

    public interface Callback {
        String callback(HashMap<String, String> args);
    }
}

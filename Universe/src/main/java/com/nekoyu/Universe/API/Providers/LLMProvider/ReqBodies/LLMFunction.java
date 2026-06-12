package com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies;

import com.google.gson.annotations.SerializedName;
import com.nekoyu.Universe.API.MessageChannel.MFChain;

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
            String description;
            @SerializedName("enum")
            List<String> enum_;

            private Property() {}

            public Property(String type) {
                this.type = type;
            }

            public static class Builder {
                private String type;
                private String description;
                private List<String> enum_;

                public Builder type(String type) {
                    this.type = type;
                    return this;
                }

                public Builder description(String description) {
                    this.description = description;
                    return this;
                }

                public Builder enum_(List<String> enum_) {
                    this.enum_ = enum_;
                    return this;
                }

                public Property build() {
                    Property property = new Property();
                    property.type = type;
                    property.description = this.description;
                    property.enum_ = this.enum_;
                    return property;
                }
            }

            public static Builder Builder() {
                return new Builder();
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
        public Builder parameters(String[] parameters, String[] required) {
            function.parameters = new Parameters("object", parameters, required);
            return this;
        }
        public Builder parameters(String type, Map<String, Parameters.Property> properties, String[] required) {
            function.parameters = new Parameters();
            function.parameters.type = type;
            function.parameters.properties = properties;
            function.parameters.required = new ArrayList<>();
            function.parameters.required.addAll(Arrays.asList(required));
            return this;
        }
        public Builder callback(Callback callback) {
            function.callback = callback;
            return this;
        }
    }

    public interface Callback {
        MFChain callback(Map<String, String> args);
    }
}

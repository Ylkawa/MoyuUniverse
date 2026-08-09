package com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON Schema (draft-07 子集) 的轻量表示，用于描述 LLM 工具的参数格式。
 * Gson 序列化时自动转换为标准的 JSON Schema 结构。
 */
public class JsonSchema {
    public String type;
    public String description;
    public String format;
    public Map<String, JsonSchema> properties;
    public List<String> required;
    public JsonSchema items;
    @SerializedName("enum")
    public List<Object> enumValues;

    private JsonSchema(String type) {
        this.type = type;
    }

    // ---------- 类型工厂 ----------

    public static JsonSchema object() {
        JsonSchema schema = new JsonSchema("object");
        schema.properties = new LinkedHashMap<>();
        return schema;
    }

    public static JsonSchema array(JsonSchema items) {
        JsonSchema schema = new JsonSchema("array");
        schema.items = items;
        return schema;
    }

    public static JsonSchema string() {
        return new JsonSchema("string");
    }

    public static JsonSchema integer() {
        return new JsonSchema("integer");
    }

    public static JsonSchema number() {
        return new JsonSchema("number");
    }

    public static JsonSchema booleanType() {
        return new JsonSchema("boolean");
    }

    public static JsonSchema enumType(Object... values) {
        JsonSchema schema = new JsonSchema("string");
        schema.enumValues = new ArrayList<>(Arrays.asList(values));
        return schema;
    }

    public static JsonSchema of(String type) {
        return new JsonSchema(type);
    }

    // ---------- 流式设置 ----------

    public JsonSchema description(String description) {
        this.description = description;
        return this;
    }

    public JsonSchema format(String format) {
        this.format = format;
        return this;
    }

    public JsonSchema property(String name, JsonSchema schema) {
        properties.put(name, schema);
        return this;
    }

    public JsonSchema required(String... names) {
        if (required == null) required = new ArrayList<>();
        required.addAll(Arrays.asList(names));
        return this;
    }

    public JsonSchema requiredProperties(List<String> names) {
        if (required == null) required = new ArrayList<>();
        required.addAll(names);
        return this;
    }

    public JsonSchema items(JsonSchema items) {
        this.items = items;
        return this;
    }

    public JsonSchema enumValues(Object... values) {
        this.enumValues = new ArrayList<>(Arrays.asList(values));
        return this;
    }
}
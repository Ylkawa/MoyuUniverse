package com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies;

import com.google.gson.annotations.SerializedName;

/** 输出格式配置；仅约束模型输出，返回的正文仍由调用方反序列化。 */
public class ResponseFormat {
    public String type;
    @SerializedName("json_schema")
    public JsonSchemaConfig jsonSchema;

    private ResponseFormat(String type) {
        this.type = type;
    }

    /** 要求输出有效 JSON，具体字段结构由提示词描述。 */
    public static ResponseFormat jsonObject() {
        return new ResponseFormat("json_object");
    }

    /** 按给定 Schema 输出；是否支持由调用的模型和接口决定。 */
    public static ResponseFormat jsonSchema(String name, JsonSchema schema, boolean strict) {
        ResponseFormat format = new ResponseFormat("json_schema");
        format.jsonSchema = new JsonSchemaConfig();
        format.jsonSchema.name = name;
        format.jsonSchema.schema = schema;
        format.jsonSchema.strict = strict;
        return format;
    }

    public static class JsonSchemaConfig {
        public String name;
        public Boolean strict;
        public JsonSchema schema;
    }
}

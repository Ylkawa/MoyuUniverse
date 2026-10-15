package com.nekoyu.Universe.AIChat;

import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.JsonSchema;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ResponseFormat;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.CompletionsResponse;

/** 内置自动化的输出契约；Schema 之外的业务约束仍由各调用方校验。 */
final class StructuredOutputs {
    private StructuredOutputs() {}

    static ResponseFormat leading() {
        JsonSchema schema = memoryOperations()
                .property("should_reply", JsonSchema.booleanType().description("本批真人消息是否需要主助手回复"))
                .property("addressed_message_ids", JsonSchema.array(JsonSchema.integer()
                        .description("本批正在与主助手对话的真人消息的原始消息 ID；只能使用本批新消息目录中的消息ID")))
                .property("memory_queries", JsonSchema.array(object()
                        .property("question", JsonSchema.string().description("独立可理解的记忆检索问题"))
                        .property("location_id", JsonSchema.anyOf(JsonSchema.string(), JsonSchema.of("null"))
                                .description("仅针对特定用户时填写已提供的 LocationId，否则为 null"))
                        .required("question", "location_id")))
                .property("knowledge_queries", JsonSchema.array(JsonSchema.string()
                        .description("独立可理解的本地知识库检索问题")))
                .required("should_reply", "addressed_message_ids", "memory_queries", "knowledge_queries");
        return ResponseFormat.jsonSchema("leading_plan", schema, true);
    }

    static ResponseFormat knowledge() {
        JsonSchema item = object()
                .property("question", JsonSchema.string().description("保留主体和适用范围的检索问题"))
                .property("conclusion", JsonSchema.string().description("由原文明确支持、直接回答问题的结论"))
                .property("confidence", JsonSchema.number().description("原文支持程度，范围 0.8 到 1"))
                .property("ttl_days", JsonSchema.integer().enumValues(7, 30, 90, 180, 365, 730, 3650))
                .required("question", "conclusion", "confidence", "ttl_days");
        return ResponseFormat.jsonSchema("knowledge_items", object()
                .property("items", JsonSchema.array(item)).required("items"), true);
    }

    static ResponseFormat conflict() {
        return ResponseFormat.jsonSchema("knowledge_conflict", object()
                .property("conflict", JsonSchema.booleanType().description("是否存在事实性冲突"))
                .required("conflict"), true);
    }

    static ResponseFormat ingestion() {
        JsonSchema source = object().property("url", JsonSchema.string())
                .property("title", JsonSchema.string()).property("content", JsonSchema.string())
                .required("url", "title", "content");
        return ResponseFormat.jsonSchema("knowledge_sources", object()
                .property("sources", JsonSchema.array(source)).required("sources"), true);
    }

    /** 仅完整结束的最终回答可以进入业务解析和持久化流程。 */
    static String content(CompletionsResponse response) {
        if (response == null || response.choices == null || response.choices.length == 0
                || response.choices[0] == null || response.choices[0].message == null
                || !"stop".equals(response.choices[0].finish_reason)
                || response.choices[0].message.content == null || response.choices[0].message.content.isBlank()) {
            throw new IllegalArgumentException("Structured output did not finish with a complete answer");
        }
        return response.choices[0].message.content;
    }

    private static JsonSchema memoryOperations() {
        JsonSchema addition = object()
                .property("location_id", JsonSchema.string().description("逐字使用输入提供的 LocationId"))
                .property("content", JsonSchema.string().description("可复用的原子化记忆事实"))
                .property("confidence", fraction("对记忆内容的确定程度"))
                .property("importance", fraction("记忆的长期价值"))
                .required("location_id", "content", "confidence", "importance");
        JsonSchema update = object()
                .property("id", JsonSchema.integer().description("候选记忆列表中的编号"))
                .property("content", JsonSchema.string().description("修改后的记忆内容"))
                .property("confidence", fraction("对记忆内容的确定程度"))
                .property("importance", fraction("记忆的长期价值"))
                .required("id", "content", "confidence", "importance");
        return object().property("add", JsonSchema.array(addition))
                .property("update", JsonSchema.array(update))
                .property("delete", JsonSchema.array(JsonSchema.integer().description("候选记忆列表中的编号")))
                .required("add", "update", "delete");
    }

    private static JsonSchema fraction(String description) {
        return JsonSchema.number().description(description + "，范围 0 到 1");
    }

    private static JsonSchema object() {
        return JsonSchema.object().additionalProperties(false);
    }
}

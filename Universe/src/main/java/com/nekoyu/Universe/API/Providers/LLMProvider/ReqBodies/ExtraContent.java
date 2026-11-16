package com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies;

/**
 * 厂商扩展字段载体，随 assistant 消息 / tool_call 原样回传，不得展示或改写。
 * 目前承载 Gemini（OpenAI 兼容协议）的加密思维签名：extra_content.google.thought_signature。
 * Gemini 3.x 要求下一轮请求原样带回签名，缺失会导致 400（Function call is missing a thought_signature）。
 * 字段为 null 时 Gson 不序列化，对不兼容的厂商无副作用。
 */
public class ExtraContent {
    public Google google;

    public static class Google {
        /** Gemini 加密思维签名（不透明字符串），必须原样回传 */
        public String thought_signature;
    }

    public static ExtraContent ofThoughtSignature(String signature) {
        ExtraContent extra = new ExtraContent();
        extra.google = new Google();
        extra.google.thought_signature = signature;
        return extra;
    }
}

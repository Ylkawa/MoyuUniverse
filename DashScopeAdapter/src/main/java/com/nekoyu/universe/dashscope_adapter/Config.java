package com.nekoyu.universe.dashscope_adapter;

/** 与 OpenAIAdapter 一致，每份 JSON 配置注册一个 ProviderId。 */
public final class Config {
    public String ProviderId;
    public String APIKey;
    public String BaseUrl;
    public String DefaultModel;
    public String DefaultEmbeddingModel;
    public String EmbeddingSpaceVersion = "v1";
    public int EmbeddingResolutionLevel = 1;
    public boolean enable_thinking = false;
}

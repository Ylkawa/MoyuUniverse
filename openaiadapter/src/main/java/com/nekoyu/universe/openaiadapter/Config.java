package com.nekoyu.universe.openaiadapter;

public class Config {
    String ProviderId;
    String APIKey;
    String BaseUrl;
    String DefaultModel;
    String DefaultEmbeddingModel;
    /** 单次回复内允许执行的最大工具调用次数，防止模型陷入工具调用死循环；不配置则使用默认值20 */
    Integer MaxToolCallsPerTurn;
    /** 单次回复内工具调用最多进行的请求轮数；不配置则使用默认值5 */
    Integer MaxToolRounds;
    /** 相同工具两次调用的参数 Jaccard 相似度达到该值即视为重复调用；不配置则使用默认值0.8 */
    Double DuplicateThreshold;
}

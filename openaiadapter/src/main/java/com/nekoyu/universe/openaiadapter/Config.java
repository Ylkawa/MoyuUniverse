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
    /** 是否启用跨请求后台异步工具结果；不配置默认false */
    Boolean EnableAsyncTools;
    /** 新消息静默去抖等待时长（毫秒）；不配置则默认5000 */
    Integer AsyncDebounceMillis;
    /** 异步工具结果最大等待时长（毫秒）；不配置则默认30000 */
    Integer AsyncMaxWaitMillis;
}

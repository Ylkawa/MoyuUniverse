package com.nekoyu.universe.openaiadapter;

public class Config {
    String ProviderId;
    String APIKey;
    String BaseUrl;
    String DefaultModel;
    String DefaultEmbeddingModel;
    /** 仅百炼白名单模型启用联网搜索；旧配置缺省关闭。 */
    boolean Search = false;
}

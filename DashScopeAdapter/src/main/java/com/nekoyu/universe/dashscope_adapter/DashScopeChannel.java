package com.nekoyu.universe.dashscope_adapter;

import com.nekoyu.Universe.API.Providers.LLMProvider.*;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.*;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.*;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 注册为 LLMProvider，通过 Embedding 能力提供百炼原生多模态向量。 */
public final class DashScopeChannel extends LLMProvider implements Embedding, TextToImage {
    public String apikey;
    public String baseurl;
    public String defaultModel;
    public String defaultEmbeddingModel;
    public String embeddingSpaceVersion = "v1";
    public int embeddingResolutionLevel = 1;
    public Logger logger;
    public OkHttpClient client = new OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build();
    @Override public EmbeddingResponse embedding(EmbeddingRequest request) throws IOException { return new DashScopeEmbedding(this).embedding(request); }
    @Override public Embedding.Space embeddingSpace(EmbeddingRequest options) throws IOException { return new DashScopeEmbedding(this).space(options); }
    @Override public CompletionsResponse completions(String model, List<Message> messages, List<LLMFunction> tools,
                                                    CompletionsRequest request, BufferCallback callback) throws IOException {
        throw new IOException("DashScopeChannel 尚未实现聊天 completions，请使用已有 OpenAI 兼容通道");
    }
    @Override public T2IResponse t2i(T2IRequest request) {
        throw new UnsupportedOperationException("DashScopeChannel 原有文生图功能尚未完成");
    }
}

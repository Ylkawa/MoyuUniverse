package com.nekoyu.Universe.API.Providers.LLMProvider;

import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.EmbeddingRequest;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.EmbeddingResponse;

import java.io.IOException;

public interface Embedding {
    EmbeddingResponse embedding(EmbeddingRequest embeddingRequest) throws IOException;
}

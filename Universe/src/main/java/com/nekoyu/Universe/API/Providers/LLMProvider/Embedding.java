package com.nekoyu.Universe.API.Providers.LLMProvider;

import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.EmbeddingRequest;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.EmbeddingResponse;

import java.io.IOException;

public interface Embedding {
    EmbeddingResponse embedding(EmbeddingRequest embeddingRequest) throws IOException;
    /** 不发起网络请求、不要求密钥；缓存和预建工具通过此身份防止混用语义空间。 */
    default Space embeddingSpace(EmbeddingRequest options) throws IOException {
        throw new IOException("Provider does not expose embedding space metadata");
    }
    record Space(String model, String revision, int dimension) {
        public Space {
            if (model == null || model.isBlank() || revision == null || revision.isBlank() || dimension <= 0)
                throw new IllegalArgumentException("Embedding space identity is required");
        }
    }
}

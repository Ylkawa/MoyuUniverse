package com.nekoyu.Universe.AIChat;

import com.nekoyu.Universe.API.MessageChannel.MFChain;
import com.nekoyu.Universe.API.MessageChannel.MessageField.TextField;
import com.nekoyu.Universe.API.Providers.LLMProvider.Embedding;
import com.nekoyu.Universe.API.Providers.LLMProvider.LLMProvider;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.EmbeddingRequest;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.EmbeddingResponse;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

public class Memory {
    private Embedding provider;
    private Logger logger = LoggerFactory.getLogger(Memory.class);
    private QdrantClient client;
    private String collection;

    public Memory(Config.QdrantConfig config) throws IOException {
        var builder = QdrantGrpcClient.newBuilder(config.address, config.port);
        if (config.secretKey != null) builder.withApiKey(config.secretKey);
        client = new QdrantClient(builder.build());
    }

    public List<Item> query(String quiz) {

    }

    public double[] embedding(String text) throws IOException {
        EmbeddingRequest embeddingRequest = new EmbeddingRequest();
        embeddingRequest.message = new MFChain();
        embeddingRequest.message.add(new TextField(text));
        EmbeddingResponse resp = provider.embedding(embeddingRequest);
        if (resp == null || resp.data == null || resp.data.isEmpty()) throw new IOException("Empty response");
        return resp.data.get(0).embedding;
    }

    public static class Item {
        public int id;
        public String content;
        public long createAt;
        public long updatedAt;
        public String locationId;
    }
}

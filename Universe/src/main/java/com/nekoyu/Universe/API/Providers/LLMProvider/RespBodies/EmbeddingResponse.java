package com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies;

import java.util.ArrayList;
import java.util.List;

public class EmbeddingResponse {
    public List<Embedding> data;
    public String object;
    public String model;
    public Usage usage;
    public String id;

    public EmbeddingResponse() {
        usage = new Usage();
        data = new ArrayList<>();
    }

    public static class Embedding {
        public double[] embedding;
        public int index;
        public String object;
    }

    public static class Usage {
        public int prompt_tokens = 0;
        public int total_tokens = 0;
    }
}

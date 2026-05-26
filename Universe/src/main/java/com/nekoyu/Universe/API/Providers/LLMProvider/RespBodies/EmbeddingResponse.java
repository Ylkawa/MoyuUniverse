package com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies;

import java.util.List;

public class EmbeddingResponse {
    public List<Embedding> data;
    public String object;
    public String model;
    public Usage usage;
    public String id;

    public static class Embedding {
        public double[] embedding;
        public int index;
        public String object;
    }

    public static class Usage {
        public String prompt_tokens;
        public String total_tokens;
    }
}

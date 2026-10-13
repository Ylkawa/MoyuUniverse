package com.nekoyu.Universe.AIChat.graph;

/** Settings under AIChat config.json's Graph key. Hard bounds protect the rendering budget. */
public final class GraphSettings {
    public boolean enabled = true;
    public int width = 1440;
    public int pageHeight = 2000;
    public int maxPixels = 8_000_000;
    public int maxNodes = 200;
    public int maxEdges = 400;
    public int maxTextCharacters = 20_000;
    public int maxPages = 32;
    public String fontFile;
    public String outputDirectory = "cache/graph";
    public String catalogManifest;
    public String embeddingApiKeyEnv = "DASHSCOPE_API_KEY";
    public String embeddingEndpoint = Qwen3VlEmbeddingProvider.DEFAULT_ENDPOINT.toString();
    public int embeddingDimension = 2560;
    public String qdrantCollection;

    public void validate() {
        if (width < 640 || width > 2048 || pageHeight < 800 || pageHeight > 4096
                || maxPixels < 1 || maxPixels > 8_000_000 || (long) width * pageHeight > maxPixels
                || maxNodes < 1 || maxNodes > 200 || maxEdges < 0 || maxEdges > 400
                || maxTextCharacters < 1 || maxTextCharacters > 20_000 || maxPages < 1 || maxPages > 64
                || outputDirectory == null || outputDirectory.isBlank()) {
            throw new IllegalArgumentException("Graph dimensions or resource limits exceed supported bounds");
        }
    }
}

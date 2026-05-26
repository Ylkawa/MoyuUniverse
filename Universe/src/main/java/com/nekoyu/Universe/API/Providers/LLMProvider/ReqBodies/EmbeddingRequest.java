package com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies;

import com.nekoyu.Universe.API.MessageChannel.MFChain;

public class EmbeddingRequest {
    public String model;
    public MFChain message;
}

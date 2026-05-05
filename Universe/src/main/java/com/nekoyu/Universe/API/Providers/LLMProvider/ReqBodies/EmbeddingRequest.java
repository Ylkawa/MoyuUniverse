package com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies;

import com.nekoyu.Universe.API.MessageChannel.MFChain;

public class EmbeddingRequest {
    String model;
    MFChain message;
}

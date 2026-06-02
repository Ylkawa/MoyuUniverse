package com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies;

import com.nekoyu.Universe.API.MessageChannel.MFChain;

import java.util.ArrayList;
import java.util.List;

public class EmbeddingRequest {
    public String model;
    public List<MFChain> message = new ArrayList<>();
}

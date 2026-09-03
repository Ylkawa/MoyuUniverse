package com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies;

import com.nekoyu.Universe.API.Providers.LLMProvider.LLMProvider;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.CompletionsResponse;

import java.io.IOException;

public abstract class Assistant {
    public abstract CompletionsResponse completions(LLMProvider.BufferCallback bufferCallback) throws IOException;
}

package com.nekoyu.Universe.API.Providers.LLMProvider;

import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.T2IRequest;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.T2IResponse;

public interface TextToImage {
    T2IResponse t2i(T2IRequest request);
}

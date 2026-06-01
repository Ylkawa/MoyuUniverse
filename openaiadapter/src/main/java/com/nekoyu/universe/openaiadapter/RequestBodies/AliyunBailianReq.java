package com.nekoyu.universe.openaiadapter.RequestBodies;

import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.CompletionsRequest;

import java.util.HashMap;

public class AliyunBailianReq extends CompletionsRequest {
    public HashMap<String, Object> stream_options;
    public boolean enable_thinking;

    public AliyunBailianReq() {
        super();
        stream_options = new HashMap<>();
    }
}

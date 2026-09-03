package com.nekoyu.universe.openaiadapter.RequestBodies;

import java.util.HashMap;

public class AliyunBailianReq extends OpenAICompletionsRequest {
    public HashMap<String, Object> stream_options;
    public boolean enable_thinking;

    public AliyunBailianReq() {
        super();
        stream_options = new HashMap<>();
    }
}

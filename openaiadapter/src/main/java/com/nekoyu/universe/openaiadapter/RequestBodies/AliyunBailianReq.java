package com.nekoyu.universe.openaiadapter.RequestBodies;

import java.util.HashMap;

public class AliyunBailianReq extends OpenAICompletionsRequest {
    public HashMap<String, Object> stream_options;
    public boolean enable_thinking;
    /** null 时省略，避免向不兼容的模型发送搜索参数。 */
    public Boolean enable_search;

    public AliyunBailianReq() {
        super();
        stream_options = new HashMap<>();
    }
}

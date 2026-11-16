package com.nekoyu.universe.openaiadapter.RequestBodies;

import java.util.HashMap;

public class AliyunBailianReq extends OpenAICompletionsRequest {
    public HashMap<String, Object> stream_options;
    public boolean enable_thinking;
    /** null 时省略，避免向不兼容的模型发送搜索参数。 */
    public Boolean enable_search;
    /** 思维链展示模式；"summarized" 时返回摘要(reasoning_content)与完整密文(encrypted_content)，仅 qwen4-max 等新模型支持 */
    public String thinking_display;
    /** 回传历史密文续推时需为 true；与 thinking_display 每轮一同下发。null 时省略 */
    public Boolean preserve_thinking;

    public AliyunBailianReq() {
        super();
        stream_options = new HashMap<>();
    }
}

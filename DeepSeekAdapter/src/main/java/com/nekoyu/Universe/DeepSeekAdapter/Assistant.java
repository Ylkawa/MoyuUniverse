package com.nekoyu.Universe.DeepSeekAdapter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class Assistant {
    private DeepSeekChannel dsc;
    public Map<String, DeepSeekTool> deepSeekTools = new HashMap<>();
    public String prompt = "";
    public String model;

    /**
     * 应当仅由DeepSeekChannel调用，且DeepSeekChannel创建时应当把自身传入构建函数
     * @param deepSeekChannel DeepSeek客户端
     */
    public Assistant(DeepSeekChannel deepSeekChannel, String model) {
        dsc = deepSeekChannel;
        this.model = model;
    }

    /**
     *
     * @param dsf 要添加的函数
     */
    public void addTool(DeepSeekTool dsf) {
        deepSeekTools.put(dsf.function.name, dsf);
    }

    public AssistantResponse request(MessageList ml) throws IOException,DSException {
        return dsc.request(ml, this);
    }

    public void setSystemPrompt(String prompt) {
        this.prompt = prompt;
    }
}

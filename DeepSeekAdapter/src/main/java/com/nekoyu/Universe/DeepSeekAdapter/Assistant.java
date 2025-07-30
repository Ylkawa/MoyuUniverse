package com.nekoyu.Universe.DeepSeekAdapter;

import java.util.ArrayList;
import java.util.List;

public class Assistant {
    private DeepSeekChannel dsc;
    private List<DeepSeekFunction> deepSeekFunctions = new ArrayList<>();

    /**
     * 应当仅由DeepSeekChannel调用，且DeepSeekChannel创建时应当把自身传入构建函数
     * @param deepSeekChannel DeepSeek客户端
     */
    public Assistant(DeepSeekChannel deepSeekChannel) {
        dsc = deepSeekChannel;
    }

    /**
     *
     * @param dsf 要添加的函数
     */
    public void addFunction(DeepSeekFunction dsf) {
        deepSeekFunctions.add(dsf);
    }

    public AssistantResponse request(MessageList ml) {
        var ar = new AssistantRequest();
        return null;
    }
}

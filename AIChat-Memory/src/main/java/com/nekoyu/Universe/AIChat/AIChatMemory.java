package com.nekoyu.Universe.AIChat;

import com.nekoyu.Universe.AIChat.Event.RequestEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AIChatMemory extends AIChatPlugin {
    Logger logger = LoggerFactory.getLogger(getClass());

    @Override
    public void onEnable() {
        logger.info("AIChat 记忆插件已启用");
    }

    @Override
    public void onRequest(RequestEvent event) {
        logger.info("处理请求");
    }
}

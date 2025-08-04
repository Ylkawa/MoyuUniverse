package com.nekoyu.Universe.AIChat;

import com.nekoyu.Universe.API.MessageChannel.MCMessage;
import com.nekoyu.Universe.API.MessageChannel.MessageChannelListener;
import com.nekoyu.Universe.ConfigureProcessor.CFGFileSyntaxException;
import com.nekoyu.Universe.ConfigureProcessor.ConfigureProcessor;
import com.nekoyu.Universe.DeepSeekAdapter.Assistant;
import com.nekoyu.Universe.DeepSeekAdapter.DeepSeekChannel;
import com.nekoyu.Universe.DeepSeekAdapter.MessageList;
import com.nekoyu.Universe.LawsLoader.Law;
import com.nekoyu.Universe.Universe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class AIChat extends Law {
    Logger logger = LoggerFactory.getLogger(this.getClass());
    Map<String, ConfigureProcessor> configs = new HashMap<>();
    Map<String, MessageList> messageLists = new HashMap<>();

    @Override
    public boolean prepare() {

        File configDic = new File("./config/AIChat/");
        if (!configDic.isDirectory()) {
            if (!configDic.exists()) configDic.mkdir();
        }
        loadCfg(configDic);
        return true;
    }

    private void loadCfg(File configDic) {
        for (File file : configDic.listFiles()) {
             if (file.isFile() && file.getName().endsWith(".yml")) {
                 ConfigureProcessor cfg = new ConfigureProcessor(file, true);
                 try {
                     cfg.read();
                     cfg.requireNode("ChannelId", "[a-zA-Z0-9]+:[a-zA-Z0-9/]+", "");
                     cfg.requireNode("Prompt", "[\\s\\S]+", "");
                     cfg.requireNode("Provider", "[\\s\\S]+", "");
                     cfg.requireNode("Trigger", "^auto$|^every$|^keyword$");
                     cfg.requireNode("Model", "[\\s\\S]+", "deepseek-chat");
                     int checkFor = cfg.checkFor();
                     if (checkFor == 0) {
                         configs.put((String) cfg.getNode("ChannelId"), cfg);
                         logger.info("载入配置文件 {} ", file.getName());
                     } else {
                         logger.warn("{} 中仍然有 {} 个错误，将不会被加载", file.getName(), checkFor);
                     }

                 } catch (IOException e) {
                     throw new RuntimeException(e);
                 } catch (CFGFileSyntaxException e) {
                     logger.warn("{} 的格式错误，无法加载", file.getName());
                 }
             } else if (file.isDirectory()) {
                 loadCfg(file);
             }
        }
    }

    @Override
    public void run() {
        for (ConfigureProcessor cfg : configs.values()) {
            switch (cfg.getNode("Trigger").toString()) {
                case "every":
                    messageLists.put(cfg.getNode("ChannelId").toString(), new MessageList());
                    Universe.MessageChannelManager.listenToSession(cfg.getNode("ChannelId").toString(), mcm -> {
                        // 更新聊天记录
                        MessageList ml = messageLists.get(mcm.sessionId);
                        ml.addMessage(mcm.message);
                        Object provider = Universe.Providers.get(cfg.getNode("Provider").toString());
                        if (provider instanceof DeepSeekChannel dsc) {
                            Assistant assistant = dsc.getAssistant(cfg.getNode("Model").toString());
                            try {
                                var response = assistant.request(ml);
                                mcm.action.reply(response.choices[0].message.content);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        } else {
                            logger.warn("定义的AI服务适配器 {} 无效", cfg.getNode("Provider").toString());
                        }
                    });
                    break;
            }
        }
    }

    @Override
    public void stop() {

    }
}

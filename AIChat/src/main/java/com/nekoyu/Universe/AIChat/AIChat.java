package com.nekoyu.Universe.AIChat;

import com.nekoyu.Universe.ConfigureProcessor.CFGFileSyntaxException;
import com.nekoyu.Universe.ConfigureProcessor.ConfigureProcessor;
import com.nekoyu.Universe.DeepSeekAdapter.*;
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
    ConfigureProcessor config;
    Map<String, DeepSeekTool> deepSeekTools = new HashMap<>();

    @Override
    public boolean prepare() {
        File configDic = new File("./config/AIChat");
        if (!configDic.exists()) configDic.mkdir();
        File sessionCFGDic = new File("./config/AIChat/SessionCFG/");
        if (!sessionCFGDic.exists()) sessionCFGDic.mkdir();
        loadSessionCfg(sessionCFGDic);
        ConfigureProcessor config = new ConfigureProcessor("./config/AIChat/config.yml");
        config.requireNode("Prompt", "[\\s\\S]+", "");
        try {
            config.read();
        } catch (CFGFileSyntaxException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.config = config;

        // LoadBuiltInDeepSeekFunction
        var dst = new DeepSeekTool("get_weather", "get weather", new DeepSeekTool.CallbackFunction() {
            @Override
            public String function(Map<String, String> args) {
                return "气温26度";
            }
        });
        deepSeekTools.put("get_weather", dst);
        return true;
    }

    private void loadSessionCfg(File configDic) {
        for (File file : configDic.listFiles()) {
             if (file.isFile() && file.getName().endsWith(".yml")) {
                 ConfigureProcessor cfg = new ConfigureProcessor(file, true);
                 try {
                     cfg.read();
                     cfg.requireNode("SessionId", "[a-zA-Z0-9]+:[a-zA-Z0-9/]+", "");
                     cfg.requireNode("Prompt", "[\\s\\S]+", "");
                     cfg.requireNode("Provider", "[\\s\\S]+", "");
                     cfg.requireNode("Trigger", "^auto$|^every$|^keyword$");
                     cfg.requireNode("Model", "[\\s\\S]+", "deepseek-chat");
                     if (cfg.getNode("Trigger").toString().equals("keyword")) {
                         cfg.requireNode("Keyword", "[\\s\\S]+", "");
                     }
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
                 loadSessionCfg(file);
             }
        }
    }

    @Override
    public void run() {
        for (ConfigureProcessor cfg : configs.values()) {
            MessageList newML = new MessageList();
            StringBuilder prompt = new StringBuilder();
            prompt.append(config.getNode("Prompt").toString() + "\n\n");
            prompt.append(cfg.getNode("Prompt").toString());

            newML.setSystemPrompt(prompt.toString());
            messageLists.put(cfg.getNode("SessionId").toString(), newML);
            switch (cfg.getNode("Trigger").toString()) {
                case "every":
                    Universe.MessageChannelManager.listenToSession(cfg.getNode("SessionId").toString(), mcm -> {
                        // 更新聊天记录
                        MessageList ml = messageLists.get(mcm.sessionId);
                        ml.addMessage(mcm.sender.getNickname() + ": " + mcm.message);
                        ml.clean();
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
                case "keyword":
                    Universe.MessageChannelManager.listenToSession(cfg.getNode("SessionId").toString(), mcm -> {
                        MessageList ml = messageLists.get(mcm.sessionId);
                        ml.addMessage(mcm.sender.getNickname() + ": " + mcm.message);
                        ml.clean();
                        if (mcm.message.contains(cfg.getNode("Keyword").toString())) {
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
                        }
                    });
            }
        }
    }

    @Override
    public void stop() {

    }
}

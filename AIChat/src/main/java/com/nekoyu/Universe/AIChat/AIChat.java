package com.nekoyu.Universe.AIChat;

import com.nekoyu.Universe.API.MessageChannel.MCMessage;
import com.nekoyu.Universe.API.MessageChannel.MessageChannelListener;
import com.nekoyu.Universe.ConfigureProcessor.CFGFileSyntaxException;
import com.nekoyu.Universe.ConfigureProcessor.ConfigureProcessor;
import com.nekoyu.Universe.DeepSeekAdapter.Assistant;
import com.nekoyu.Universe.DeepSeekAdapter.DeepSeekChannel;
import com.nekoyu.Universe.DeepSeekAdapter.DeepSeekFunction;
import com.nekoyu.Universe.LawsLoader.Law;
import com.nekoyu.Universe.Universe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public class AIChat extends Law {
    Logger logger = LoggerFactory.getLogger(this.getClass());
    Map<String, ConfigureProcessor> configs = new HashMap<>();

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
                     cfg.requireNode("Prompt", "", "");
                     cfg.requireNode("Provider", "", "");
                     cfg.requireNode("Trigger", "^auto$|^every$|^keyword$");
                     cfg.requireNode("Model", "", "deepseek-chat");
                     int checkFor = cfg.checkFor();
                     if (checkFor == 0) {
                         configs.put((String) cfg.getNode("ChannelId"), cfg);
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
                    Universe.MessageChannelManager.listenToSession(cfg.getNode("ChannelId").toString(), new MessageChannelListener() {
                        @Override
                        public void onMessage(MCMessage mcm) {
                            Object provider = Universe.Providers.get(cfg.getNode("Provider").toString());
                            if (provider instanceof DeepSeekChannel dsc) {
                                Assistant assistant = dsc.getAssistant(cfg.getNode("Model").toString());
                            }
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

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
import java.text.SimpleDateFormat;
import java.util.*;

public class AIChat extends Law {
    Logger logger = LoggerFactory.getLogger(this.getClass());
    List<ConfigureProcessor> configs = new ArrayList<>();
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
        var get_weather = new DeepSeekTool("get_weather", "get weather", new DeepSeekTool.CallbackFunction() {
            @Override
            public String function(Map<String, String> args) {
                return "气温26度";
            }
        }, new HashMap<>(), new String[]{});
        deepSeekTools.put("get_weather", get_weather);
        var add_memory = new DeepSeekTool("add_memory", "Add a new info into memory", new DeepSeekTool.CallbackFunction() {
            @Override
            public String function(Map<String, String> args) {
                return "";
            }
        }, new HashMap<>(){{
            put("memory_content", new DeepSeekTool.Function.Parameters.Property("记忆的内容"));
        }}, new String[]{"memory_content"});
        deepSeekTools.put("add_memory", add_memory);
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
                         configs.add(cfg);
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
        SimpleDateFormat sdf = new SimpleDateFormat("[yyyy-MM-dd HH:mm:ss]");
        for (ConfigureProcessor cfg : configs) {
            MessageList newML = new MessageList();
            messageLists.put(cfg.getNode("SessionId").toString(), newML);
            Universe.MessageChannelManager.listenToSession(cfg.getNode("SessionId").toString(), mcm -> {
                StringBuilder prompt = new StringBuilder();
                prompt.append("当前时间: ").append(sdf.format(new Date(System.currentTimeMillis()))).append("\n");
                prompt.append("当前所处会话: ").append(cfg.getNode("SessionId")).append("\n");
                prompt.append("你的账号: ").append(mcm.receiver.getId());
                prompt.append("\n");
                prompt.append(config.getNode("Prompt").toString()).append("\n");
                prompt.append(cfg.getNode("Prompt").toString());
                newML.setSystemPrompt(prompt.toString());
                MessageList ml = messageLists.get(mcm.sessionId);
                StringBuilder content = new StringBuilder();
                content.append(sdf.format(new Date(mcm.time * 1000))); // [时间]
                content.append("[").append(mcm.id).append("]"); // [时间] [消息id]
                content.append(mcm.sender.getNickname()).append("(").append(mcm.sender.getId()).append(")").append(mcm.sender.getSex()); // [时间] [消息id] [昵称](用户QQ号)性别
                content.append(": ").append(mcm.message); // [时间] [消息id] [昵称](用户QQ号)性别: [消息内容]
                ml.addMessage(content.toString());
                ml.clean();
                if (cfg.getNode("Trigger").toString().equals("every") || mcm.message.contains(cfg.getNode("Keyword").toString())) {
                    Object provider = Universe.Providers.get(cfg.getNode("Provider").toString());
                    if (provider instanceof DeepSeekChannel dsc) {
                        Assistant assistant = dsc.getAssistant(cfg.getNode("Model").toString());
                        if (cfg.getNode("Tools") instanceof List) {
                            for (String tool : (List) cfg.getNode("Tools")) {
                                if (deepSeekTools.get(tool) != null) assistant.addTool(deepSeekTools.get(tool));
                            } // 为assistant添加指定的tools // 如果不存在这个tool就不添加
                        }
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

    @Override
    public void stop() {

    }
}

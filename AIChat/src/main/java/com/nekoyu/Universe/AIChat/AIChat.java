package com.nekoyu.Universe.AIChat;

import com.google.gson.Gson;
import com.nekoyu.Universe.AIChat.Event.RequestEvent;
import com.nekoyu.Universe.API.PlaceHolder;
import com.nekoyu.Universe.ConfigureProcessor.CFGFileSyntaxException;
import com.nekoyu.Universe.ConfigureProcessor.ConfigureProcessor;
import com.nekoyu.Universe.DeepSeekAdapter.*;
import com.nekoyu.Universe.LawsLoader.Law;
import com.nekoyu.Universe.Universe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

public class AIChat extends Law {
    public static final Yaml yaml = new Yaml();
    Logger logger = LoggerFactory.getLogger(this.getClass());
    List<ConfigureProcessor> configs = new ArrayList<>();
    Map<String, MessageList> messageLists = new HashMap<>();
    ConfigureProcessor config;
    Map<String, DeepSeekTool> deepSeekTools = new HashMap<>();
    List<AIChatPlugin> aiChatPlugins = new ArrayList<>();
    Config new_cfg;

    @Override
    public boolean prepare() {
        getDataDir();
        File configDic = new File("./config/AIChat");
        if (!configDic.exists()) configDic.mkdir();
        File sessionCFGDic = new File("./config/AIChat/SessionCFG/");
        if (!sessionCFGDic.exists()) sessionCFGDic.mkdir();
        File toolsCFGDic = new File("./config/AIChat/ToolsCFG");
        if (!toolsCFGDic.exists()) toolsCFGDic.mkdir();
        loadSessionCfg(sessionCFGDic);

        // 从这里开始重写
        try {
            new_cfg = new Gson().fromJson(new FileReader("./config/AIChat/config.json"), Config.class);
        } catch (FileNotFoundException e) {
            // 没找到配置文件，所以新建一个配置文件
            new_cfg = new Config();
            new_cfg.Prompt = ""; // 默认的System_prompt，这里留白了没写
            try (FileWriter fw = new FileWriter("./config/AIChat/config.json")) {
                fw.write(new Gson().toJson(new_cfg)); //写入
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }

        // LoadBuiltInDeepSeekFunction
        var get_weather = new DeepSeekTool("get_weather", "get weather", args -> "气温26度", new HashMap<>(), new String[]{});
        deepSeekTools.put("get_weather", get_weather);
        var add_memory = new DeepSeekTool("add_memory", "Add a new info into memory", new DeepSeekTool.CallbackFunction() {
            @Override
            public String function(Map<String, String> args) {
                return null;
            }
        }, new HashMap<>(){{
            put("memory_content", new DeepSeekTool.Function.Parameters.Property("记忆的内容"));
        }}, new String[]{"memory_content"});
        deepSeekTools.put("add_memory", add_memory);

        File toolsDic = new File("./data/AIChat/Plugins/");
        if (toolsDic.isDirectory()) {
            logger.info("准备加载AI Chat插件...");
            // 加载外置的Tool(Advanced)
            File[] files = toolsDic.listFiles((dir, name) -> name.toLowerCase().endsWith(".jar"));
            List<AIChatPluginInfo> aiChatPluginInfos = new ArrayList<>();
            List<URL> urls = new ArrayList<>();
            for (File f : files) {
                try (JarFile jf = new JarFile(f)) {
                    ZipEntry ze = jf.getEntry("plug.properties");
                    if (ze == null) {
                        logger.info("{} 无描述文件", f.getName());
                        continue;
                    }
                    try (InputStream is = jf.getInputStream(ze)) {
                        Properties properties = new Properties();
                        properties.load(is);
                        AIChatPluginInfo aiChatPluginInfo = new AIChatPluginInfo();
                        aiChatPluginInfo.url = f.toURI().toURL();
                        aiChatPluginInfo.mainClass = properties.getProperty("Main");
                        aiChatPluginInfo.id = properties.getProperty("ID");
                        if (aiChatPluginInfo.id != null && aiChatPluginInfo.mainClass != null) {
                            aiChatPluginInfos.add(aiChatPluginInfo);
                            urls.add(f.toURI().toURL());
                        } else {
                            logger.info("{} 没有有效的描述文件", f.getName());
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            var classloader = new URLClassLoader(urls.toArray(new URL[0]), getClass().getClassLoader());
            for (var info : aiChatPluginInfos) {
                try {
                    Class<?> clazz = Class.forName(info.mainClass, true, classloader);
                    AIChatPlugin aiChatPlugin = (AIChatPlugin) clazz.getDeclaredConstructor().newInstance();
                    aiChatPlugin.id = info.id;
                    aiChatPlugins.add(aiChatPlugin);
                    logger.info("已载入AI Chat插件 {}", info.id);
                } catch (ClassNotFoundException e) {
                    logger.error("AI Chat插件 {} 主类缺失，无法加载({})", info.id, info.mainClass, e);
                } catch (InvocationTargetException | InstantiationException | IllegalAccessException | NoSuchMethodException e) {
                    logger.error("AI Chat插件 {} 加载失败", info.id, e);
                }
            }
            for (var plug : aiChatPlugins) {
                plug.onEnable();
            }
        } else {
            toolsDic.mkdirs();
        }
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
            MessageList ml = new MessageList();
            Universe.MessageChannelManager.listenToSession(cfg.getNode("SessionId").toString(), mcm -> {
                アンテナ39 newMsg = new アンテナ39(mcm);
                newMsg.role = "user";
                ml.addMessage(newMsg);
                ml.clean();
                if (cfg.getNode("Trigger").toString().equals("every") || mcm.messageString.contains(cfg.getNode("Keyword").toString()) || mcm.level >= 2) {
                    Object provider = Universe.Providers.get(cfg.getNode("Provider").toString());
                    if (provider instanceof DeepSeekChannel dsc) {
                        Assistant assistant = dsc.getAssistant(cfg.getNode("Model").toString());
                        if (cfg.getNode("Tools") instanceof List) {
                            for (String tool : (List<String>) cfg.getNode("Tools")) {
                                if (deepSeekTools.get(tool) != null) assistant.addTool(deepSeekTools.get(tool));
                            } // 为assistant添加指定的tools // 如果不存在这个tool就不添加
                        }
                        // 决定让AI发言
                        // 设置 System Prompt
                        // 先让插件处理事件 插件提供局部的PlaceHolder
                        var reqEv = new RequestEvent();
                        for (var plug : aiChatPlugins) {
                            plug.onRequest(reqEv);
                        }
                        StringBuilder prompt = new StringBuilder();
                        prompt.append("当前时间: ").append(sdf.format(new Date(System.currentTimeMillis()))).append("\n");
                        prompt.append("当前所处会话: ").append(cfg.getNode("SessionId")).append("\n");
                        prompt.append("你的账号: ").append(mcm.receiver.getId()).append("\n");
                        prompt.append(new_cfg.Prompt).append("\n");
                        prompt.append(cfg.getNode("Prompt").toString());
                        ml.setSystemPrompt(PlaceHolder.replace(prompt.toString(), reqEv.placeholders));
                        // 把还没转换好的MCMessage转换成String
                        for (Message message : ml.getMessageList()) {
                            if (!(message instanceof アンテナ39 antena39)) continue;
                            antena39.content = antena39.mcMessage.solveAll();
                        }
                        RequestEvent re = new RequestEvent();
                        re.messageList = ml;
                        try {
                            var response = assistant.request(ml);
                            mcm.reply(response.choices[0].message.content);
                        } catch (Exception e) {
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
        for (var plug : aiChatPlugins) {
            plug.onDisable();
        }
    }
}

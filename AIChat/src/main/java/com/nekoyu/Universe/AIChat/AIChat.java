package com.nekoyu.Universe.AIChat;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.Gson;
import com.nekoyu.Universe.AIChat.Event.RequestEvent;
import com.nekoyu.Universe.API.MessageChannel.MCMessage;
import com.nekoyu.Universe.API.MessageChannel.MessageField.TextField;
import com.nekoyu.Universe.API.MessageChannel.MessageList;
import com.nekoyu.Universe.API.PlaceHolder;
import com.nekoyu.Universe.API.Providers.LLMProvider.Assistant;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.LLMFunction;
import com.nekoyu.Universe.API.Providers.LLMProvider.LLMProvider;
import com.nekoyu.Universe.LawsLoader.Law;
import com.nekoyu.Universe.Universe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

public class AIChat extends Law {
    public static final Gson gson = new Gson();
    Logger logger = LoggerFactory.getLogger(this.getClass());
    List<SessionConfig> configs = new ArrayList<>();
    static Multimap<String, LLMFunction> llmFunctions = ArrayListMultimap.create();
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
        try (Reader reader = new InputStreamReader(
                new FileInputStream("./config/AIChat/config.json"), StandardCharsets.UTF_8)) {
            new_cfg = gson.fromJson(reader, Config.class);
        } catch (IOException e) {
            // 没找到配置文件，所以新建一个配置文件
            new_cfg = new Config();
            new_cfg.Prompt = ""; // 默认的System_prompt，这里留白了没写
            try (FileWriter fw = new FileWriter("./config/AIChat/config.json")) {
                fw.write(gson.toJson(new_cfg)); //写入
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }

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
                    AIChatPlugin aiChatPlugin = (AIChatPlugin) clazz.getDeclaredConstructor(AIChat.class).newInstance(this);
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
            if (file.getName().toLowerCase().endsWith(".json")) try (InputStreamReader isr = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                SessionConfig sc = gson.fromJson(isr, SessionConfig.class);
                configs.add(sc);
                logger.info("载入配置文件 {} ", file.getName());
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            } catch (IOException e) {
                throw new RuntimeException(e);
            } else if (file.isDirectory()) {
                loadSessionCfg(file);
            }
        }
    }

    @Override
    public void run() {
        SimpleDateFormat sdf = new SimpleDateFormat("[yyyy-MM-dd HH:mm:ss]");
        for (SessionConfig cfg : configs) {
            Universe.MessageChannelManager.listenToSession(cfg.SessionId, mcm -> {
                if (cfg.Trigger.equals("every") || mcm.messageString.contains(cfg.Keyword) || mcm.level >= 2) {
                    Object provider = Universe.Providers.get(cfg.Provider);
                    if (provider instanceof LLMProvider lp) {
                        Assistant assistant = lp.newAssistant(cfg.Model);
                        if (cfg.Tools != null) {
                            for (String tool : cfg.Tools) {
                                if (llmFunctions.get(tool) != null) {
                                    for (LLMFunction func : llmFunctions.get(tool)) {  // FIXME Tool 可能被重复添加而无保护
                                        assistant.addTool(func);
                                    }
                                }
                            } // 为assistant添加指定的tools // 如果不存在这个tool就不添加
                        }
                        // 决定让AI发言
                        MessageList ml = Universe.MessageChannelManager.getMessageHistory(cfg.SessionId);
                        // 设置 System Prompt
                        // 先让插件处理事件 插件提供局部的PlaceHolder
                        var reqEv = new RequestEvent();
                        reqEv.messageList = ml;
                        for (var plug : aiChatPlugins) {
                            plug.onRequest(reqEv);
                        }
                        StringBuilder prompt = new StringBuilder();
                        prompt.append("当前时间: ").append(sdf.format(new Date(System.currentTimeMillis()))).append("\n");
                        prompt.append("当前所处会话: ").append(cfg.SessionId).append("\n");
                        prompt.append("你的账号: ").append(mcm.receiver.getId()).append("\n");
                        prompt.append(new_cfg.Prompt).append("\n");
                        prompt.append(cfg.Prompt);
                        assistant.setSystemPrompt(PlaceHolder.replace(prompt.toString(), reqEv.placeholders));
                        // 把还没转换好的MCMessage转换成String
                        // todo:here 这里需要根据 mcm.receiver 是否等于 msg.sender 为openai message list 中的mcm指定role；需要把相邻的assistant message使用\n\n拼接成同一条
//                        int key = 0; // 从第0条开始读取
//                        MCMessage mcMessage = ml.get(key);
//                        if (mcMessage.sender.equals(mcm.receiver)) {
//                            StringBuilder assistantMsg = new StringBuilder();
//                            do {
//                                assistantMsg.append(mcMessage.solveAll());
//                                key++;
//                            } while ((mcMessage = ml.get(key)).sender.equals(mcm.receiver));
//                        } else {
//
//                        }

                        // 以下为旧逻辑
                        ExecutorService executor = Executors.newFixedThreadPool(5);
                        String[][] solve = new String[ml.size()][3];
                        for (int i = 0; i < ml.size(); i++) {
                            int loopNum = i;
                            executor.submit(() -> {
                                MCMessage msg = ml.get(loopNum);
                                if (msg.sender.getId().equals(mcm.receiver.getId())) solve[loopNum][0] = "assistant";
                                else solve[loopNum][0] = "user";
                                solve[loopNum][1] = msg.solveAll();
                                solve[loopNum][2] =  // prefix
                                        sdf.format(new Date(msg.time * 1000)) + // [时间]
                                        "[" + msg.id + "]" + // [时间] [消息id]
                                        msg.sender.getNickname() + "(" + msg.sender.getId() + ")" + msg.sender.getSex() + // [时间] [消息id] [昵称](用户QQ号)性别
                                        ": "; // [时间] [消息id] [昵称](用户QQ号)性别: [消息内容]
                            });
                        }
                        executor.shutdown();
                        // 以上为旧逻辑
                        try {
                            if (executor.awaitTermination(60, TimeUnit.SECONDS)) {
                                RequestEvent re = new RequestEvent();
                                re.messageList = ml;
                                try {
                                    // 构建 OpenAI Adapter ML
                                    MessageList openaiMl = new MessageList();
                                    int key = 0;
                                    for (int i = 0; i < solve.length; i++) {
                                        if (solve[i][0].equals("assistant")) {
                                            StringBuilder content = new StringBuilder();
                                            boolean first = true;
                                            do {
                                                if (!first) content.append("\n\n");
                                                content.append(solve[i][1]);
                                                i++;
                                                first = false;
                                            } while (solve[i] != null && solve[i][0].equals("assistant"));
                                            i--;
                                            MCMessage msg = new MCMessage();
                                            msg.putMetainfo("role", "assistant");
                                            msg.messageFields.add(new TextField(content.toString()));
                                            openaiMl.add(msg);
                                        } else {
                                            MCMessage msg = new MCMessage();
                                            msg.putMetainfo("role", "user");
                                            msg.messageFields.add(new TextField(solve[i][2] + solve[i][1]));
                                            openaiMl.add(msg);
                                        }
                                    }

                                    // 接收响应 tokens
                                    StringBuilder respTokens = new StringBuilder();
                                    assistant.completions(openaiMl, (LLMProvider.BufferCallback) outputs -> {
                                        String[] split = outputs.split("\n\n", 2); // 每一次接收够一段就回复一次消息
                                        if (split.length > 1) {
                                            respTokens.append(split[0]);
                                            mcm.reply(respTokens.toString());
                                            respTokens.setLength(0);
                                            respTokens.append(split[1]);
                                        } else {
                                            respTokens.append(split[0]);
                                        }
                                    });
                                    mcm.reply(respTokens.toString());
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            } else {
                                logger.error("消息解析超时");
                            }
                        } catch (InterruptedException e) {
                            logger.error(e.getMessage(), e);
                            return;
                        }
                    } else {
                        if (provider == null) logger.warn("无此适配器 {}", cfg.Provider);
                        else logger.warn("定义的AI服务适配器 {} 无效", cfg.Provider);
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

    public static void registerFunction(String toolName, LLMFunction tool) {
        llmFunctions.put(toolName, tool);
    }
}

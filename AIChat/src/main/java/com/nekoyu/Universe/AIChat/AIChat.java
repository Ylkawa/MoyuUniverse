package com.nekoyu.Universe.AIChat;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.Gson;
import com.nekoyu.Universe.AIChat.Event.RequestEvent;
import com.nekoyu.Universe.API.MessageChannel.*;
import com.nekoyu.Universe.API.MessageChannel.MessageField.ImageField;
import com.nekoyu.Universe.API.MessageChannel.MessageField.MsgField;
import com.nekoyu.Universe.API.MessageChannel.MessageField.TextField;
import com.nekoyu.Universe.API.PlaceHolder;
import com.nekoyu.Universe.API.Providers.LLMProvider.Assistant;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ExtensionalArgs;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.LLMFunction;
import com.nekoyu.Universe.API.Providers.LLMProvider.LLMProvider;
import com.nekoyu.Universe.LawsLoader.Law;
import com.nekoyu.Universe.Universe;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

public class AIChat extends Law {
    public static final Gson gson = new Gson();
    Logger logger = LoggerFactory.getLogger(this.getClass());
    List<SessionConfig> configs = new ArrayList<>();
    static Multimap<String, LLMFunction> llmFunctions = ArrayListMultimap.create();
    List<AIChatPlugin> aiChatPlugins = new ArrayList<>();
    Config globalCfg;
    @Nullable
    Memory MEMORY = null;

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

        try (Reader reader = new InputStreamReader(
                new FileInputStream("./config/AIChat/config.json"), StandardCharsets.UTF_8)) {
            globalCfg = gson.fromJson(reader, Config.class);
            if (globalCfg.SQLConfig != null) {
                MEMORY = new Memory(globalCfg.SQLConfig);
            }
        } catch (IOException e) {
            // 没找到配置文件，所以新建一个配置文件
            globalCfg = new Config();
            globalCfg.SQLConfig = new Config.SQLConfig();
            globalCfg.Prompt = ""; // 默认的System_prompt，这里留白了没写
            try (FileWriter fw = new FileWriter("./config/AIChat/config.json")) {
                fw.write(gson.toJson(globalCfg)); //写入
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
            if (files != null) {
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
            } else {
                logger.error("请确保有足够权限写入");
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
                } catch (InvocationTargetException | InstantiationException | IllegalAccessException |
                         NoSuchMethodException e) {
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
            if (file.getName().toLowerCase().endsWith(".json"))
                try (InputStreamReader isr = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                    SessionConfig sc = gson.fromJson(isr, SessionConfig.class);
                    configs.add(sc);
                    logger.info("载入配置文件 {} ", file.getName());
                } catch (FileNotFoundException e) {
                    throw new RuntimeException(e);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            else if (file.isDirectory()) {
                loadSessionCfg(file);
            }
        }
    }

    @Override
    public void run() {
        SimpleDateFormat sdf = new SimpleDateFormat("[yyyy-MM-dd HH:mm:ss]");
        for (SessionConfig sessionCfg : configs) {
            Universe.MessageChannelManager.listenToSession(sessionCfg.SessionId, mcm -> {
                if (sessionCfg.Trigger.equals("every") || mcm.messageString.contains(sessionCfg.Keyword) || mcm.level >= 2) {
                    Object provider = Universe.Providers.get(sessionCfg.Provider);
                    if (provider instanceof LLMProvider lp) {
                        Assistant assistant = lp.newAssistant(sessionCfg.Model);
                        if (sessionCfg.Tools != null) {
                            for (String tool : sessionCfg.Tools) {
                                if (llmFunctions.get(tool) != null) {
                                    for (LLMFunction func : llmFunctions.get(tool)) {  // FIXME Tool 可能被重复添加而无保护
                                        assistant.addTool(func);
                                    }
                                }
                            } // 为assistant添加指定的tools // 如果不存在这个tool就不添加
                        }
                        // 决定让 AI 发言
                        MessageList ml = (MessageList) Universe.MessageChannelManager.getMessageHistory(sessionCfg.SessionId).clone();
                        // 设置 System Prompt
                        // 先让插件处理事件 插件提供局部的PlaceHolder
                        var reqEv = new RequestEvent();
                        reqEv.messageList = ml;
                        reqEv.locationId = mcm.getLocationId();
                        for (var plug : aiChatPlugins) {
                            try {
                                plug.onRequest(reqEv);
                            } catch (Exception e) {
                                logger.debug("{} 在处理 RequestEvent 发生错误", plug.id, e);
                            }
                        }
                        reqEv.placeholders.put("TIME", sdf.format(new Date(System.currentTimeMillis())));
                        reqEv.placeholders.put("SESSION_LOCATION_ID", mcm.getLocationId());
                        reqEv.placeholders.put("ACCOUNT_NICKNAME", mcm.receiver.getName());
                        reqEv.placeholders.put("SESSION_PROMPT", PlaceHolder.replace(sessionCfg.Prompt, reqEv.placeholders));
                        assistant.setSystemPrompt(PlaceHolder.replace(globalCfg.Prompt, reqEv.placeholders));

                        ExecutorService executor = Executors.newFixedThreadPool(5);
                        String[][] solveInfo = new String[ml.size()][2];
                        for (int i = 0; i < ml.size(); i++) {
                            MCMessage msg = ml.get(i);
                            if (msg.sender.getLocationId().equals(mcm.receiver.getLocationId())) {
                                solveInfo[i][0] = "assistant";
                            } else solveInfo[i][0] = "user";
                            executor.submit(() -> { // presolve
                                for (MsgField mf : msg.messageFields) {
                                    if (mf instanceof ImageField) {
                                        if (!sessionCfg.nativeImage) mf.solve();
                                    }
                                }
                            });
                        }
                        executor.shutdown();

                        try {
                            if (executor.awaitTermination(60, TimeUnit.SECONDS)) {
                                RequestEvent re = new RequestEvent();
                                re.messageList = ml;
                                try {
                                    // 构建 OpenAI Adapter ML
                                    MessageList openaiMl = new MessageList();
                                    for (int i = 0; i < solveInfo.length; i++) {
                                        if (solveInfo[i][0].equals("assistant")) {
                                            StringBuilder content = new StringBuilder();
                                            boolean first = true;
                                            do {
                                                if (first) first = false;
                                                else content.append("\n\n");
                                                content.append(ml.get(i).solveAll());
                                                i++;
                                            } while (solveInfo[i] != null && solveInfo[i][0].equals("assistant"));
                                            i--;
                                            MCMessage msg = new MCMessage();
                                            msg.putMetainfo("role", "assistant");
                                            msg.messageFields.add(new TextField(content.toString()));
                                            openaiMl.add(msg);
                                        } else { // 此处默认非 assistant 即 user
                                            MCMessage msg = new MCMessage();
                                            msg.putMetainfo("role", "user");
                                            msg.messageFields.add(new TextField(sdf.format(new Date(ml.get(i).time * 1000)) + // [时间]
                                                    "[" + ml.get(i).id + "]" + // [时间] [消息id]
                                                    ml.get(i).sender.getName() + "(" + ml.get(i).sender.getLocationId() + ")" + ml.get(i).sender.getSex() + // [时间] [消息id] [昵称](用户QQ号)性别
                                                    ": "));  // [时间] [消息id] [昵称](用户 LocationId)性别: [消息内容]
                                            for (MsgField mf : ml.get(i).messageFields) { // 这里仅处理了 Text 和 Image 类型，其他的都是交给末屿宇宙的默认方式转换成文本
                                                if (mf instanceof TextField) {
                                                    msg.messageFields.add(mf);
                                                } else if (mf instanceof ImageField imgF) {
                                                    if (sessionCfg.nativeImage) {
                                                        msg.messageFields.add(mf);
                                                        String metadata = imgF.solveMetadata();
                                                        if (!metadata.isBlank())
                                                            msg.messageFields.add(new TextField("{" + metadata + "}"));
                                                        // 如果 Metadata 存在就追加一条 Metadata 的提示词字段
                                                    }
                                                } else msg.messageFields.add(new TextField(mf.toString()));
                                            }
                                            openaiMl.add(msg);
                                        }
                                    }

                                    // Extensional Args
                                    ExtensionalArgs extensionalArgs = new ExtensionalArgs();
                                    extensionalArgs.placeholders.put("_LocationID", mcm.getLocationId());
                                    extensionalArgs.enable_thinking = sessionCfg.enable_thinking;

                                    // 接收响应 tokens
                                    StringBuilder respTokens = new StringBuilder();
                                    assistant.completions(openaiMl, extensionalArgs, outputs -> {
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
                                    logger.error(e.getMessage(), e);
                                }
                            } else {
                                logger.error("消息解析超时");
                            }
                        } catch (InterruptedException e) {
                            logger.error(e.getMessage(), e);
                            return;
                        }
                    } else {
                        if (provider == null) logger.warn("无此适配器 {}", sessionCfg.Provider);
                        else logger.warn("定义的AI服务适配器 {} 无效", sessionCfg.Provider);
                    }
                }
            });
            Universe.MessageChannelManager.listenToPost("sessionCfg", mcp -> {
                if (sessionCfg.Trigger.equals("every") || mcp.messageString.contains(sessionCfg.Keyword) || mcp.level >= 2) {
                    Object provider = Universe.Providers.get(sessionCfg.Provider);
                    if (provider instanceof LLMProvider lp) {
                        if (MEMORY != null) {
                            Assistant assistant = lp.newAssistant(sessionCfg.Model);
                            // 生成记忆这种应该不需要tool
                            assistant.setSystemPrompt("""
                                    你不与用户对话，只负责记忆的构建，用户角色的输入内容为用户的聊天记录或者用户发布的帖子，请以第三人称口吻分条目输出对用户的关键记忆，尝试分析用户行文和说话习惯，要求各条目独立于其他条目，保证打乱之后能以原意解读
                                    包括：近期用户经历的事情、用户心理状态
                                    为避免生成的记忆不符合真实情况，请只输出可以确定的内容，并及时移除不再有用的记忆、修改有误的记忆
                                    
                                    Assistant的输出应当严格遵循此格式 且不应自行添加多余参数，记忆条目ID和记忆修改时间会自动一并分配并写入：
                                    
                                    NEW [目标LocationId]: [要新增的记忆]
                                    UPDATE [记忆条目ID]: [修改后的记忆内容]
                                    DELETE [要删除的记忆条目ID]
                                    
                                    例如：
                                    NEW[Universe:group/12435678]: 群聊主要讨论人工智能大语言模型应用开发
                                    UPDATE 12: 用户比较喜欢VOCALOID的音乐
                                    DELETE 3
                                    
                                    LocationId定义记忆条目的作用域，作用在user上的记忆则user出现的场景生效，作用在group上则对此群聊生效""");
                            var reqEv = new RequestEvent();
                            MessageList ml = new MessageList();
                            MCMessage msg = new MCMessage();
                            msg.putMetainfo("role", "user");
                            msg.sender = mcp.poster;
                            msg.messageFields = mcp.messageFields;
                            ml.add(msg);

                            reqEv.placeholders.put("TIME", sdf.format(new Date(System.currentTimeMillis())));
                            for (var plug : aiChatPlugins) {
                                try {
                                    plug.onRequest(reqEv);
                                } catch (Exception e) {
                                    logger.debug("{} 在处理 RequestEvent 发生错误", plug.id, e);
                                }
                            }

                            // Extensional Args
                            ExtensionalArgs extensionalArgs = new ExtensionalArgs();
                            extensionalArgs.placeholders.put("_LocationID", mcp.getLocationId());
                            extensionalArgs.enable_thinking = sessionCfg.enable_thinking;

                            // 接收响应 tokens
                            try {
                                StringBuilder respTokens = new StringBuilder();
                                assistant.completions(ml, extensionalArgs, respTokens::append);
                                for (var line : respTokens.toString().split("\n")) {
                                    if (line.toUpperCase().startsWith("UPDATE")) {
                                        Matcher matcher = Pattern.compile("^UPDATE (?<MemKey>\\d+): (?<Content>.+)").matcher(line);
                                        int memKey = Integer.parseInt(matcher.group("MemKey"));
                                        String content = matcher.group("Content");
                                        MEMORY.updateMemory(memKey, content);
                                    } else if (line.toUpperCase().startsWith("DELETE")) {
                                        Matcher matcher = Pattern.compile("^DELETE (?<MemKey>\\d+)").matcher(line);
                                        MEMORY.deleteMemory(Integer.parseInt(matcher.group("MemKey")));
                                    } else if (line.toUpperCase().startsWith("NEW")) {
                                        Matcher matcher = Pattern.compile("^NEW \\[(?<LocationId>[^]]+)]: (?<Content>.+)").matcher(line);
                                        String locationId = matcher.group("LocationId");
                                        String content = matcher.group("Content");
                                        MEMORY.newMemory(locationId, content);
                                    }
                                }
                            } catch (IOException e) {
                                logger.error("生成失败", e);
                            }
                        }
                    } else {
                        if (provider == null) logger.warn("无此适配器 {}", sessionCfg.Provider);
                        else logger.warn("定义的AI服务适配器 {} 无效", sessionCfg.Provider);
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

    public class Memory {
        HikariDataSource ds;

        public Memory(Config.SQLConfig sqlConfig) {
            HikariConfig config = new HikariConfig();
            config.setDriverClassName("com.mysql.cj.jdbc.Driver");
            config.setJdbcUrl(sqlConfig.url);
            config.setUsername(sqlConfig.user);
            config.setPassword(sqlConfig.password);
            ds = new HikariDataSource(config);
            initTable();
        }

        public void newMemory(String locationId, String content) {
            try (var conn = ds.getConnection();
                 PreparedStatement p = conn.prepareStatement("""
                         INSERT INTO memories(location_id, content)
                         values (?, ?)""")
            ) {
                p.setString(1, locationId);
                p.setString(2, content);
                p.execute();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }

        public void updateMemory(int memKey, String content) {
            try (var conn = ds.getConnection();
                 PreparedStatement p = conn.prepareStatement("""
                         UPDATE memories
                         SET content = (?)
                         WHERE mem_key = (?)""")
            ) {
                p.setString(1, content);
                p.setInt(2, memKey);
                p.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }

        public void deleteMemory(int memKey) {
            try (var conn = ds.getConnection();
                 PreparedStatement p = conn.prepareStatement("""
                         DELETE FROM memories
                         WHERE mem_key = (?)""")
            ) {
                p.setInt(1, memKey);
                p.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }

        public List<MemObj> getMemories(List<String> locIds) {
            if (locIds == null || locIds.isEmpty()) {
                return List.of();
            }

            String placeholders = String.join(",", Collections.nCopies(locIds.size(), "?"));

            String sql = "SELECT location_id, mem_key, value FROM memories WHERE location_id IN (" + placeholders + ")";

            List<MemObj> result = new ArrayList<>();

            try (var conn = ds.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {

                // 绑定参数
                for (int i = 0; i < locIds.size(); i++) {
                    ps.setString(i + 1, locIds.get(i));
                }

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        MemObj obj = new MemObj();
                        obj.locationId = rs.getString("location_id");
                        obj.memKey = rs.getInt("mem_key");
                        obj.content = rs.getString("content");
                        result.add(obj);
                    }
                }

            } catch (SQLException e) {
                throw new RuntimeException(e);
            }

            return result;
        }

        public void initTable() {
            try (var conn = ds.getConnection();
                 PreparedStatement p = conn.prepareStatement("""
                     CREATE TABLE IF NOT EXISTS memories (
                     mem_key INT AUTO_INCREMENT PRIMARY KEY,
                     location_id VARCHAR(255) NOT NULL,
                     content TEXT NOT NULL,
                     created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                     updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                     INDEX idx_location_id (location_id)
                     )""")
            ) {
                p.execute();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }

        public static class MemObj {
            int memKey;
            String locationId;
            String content;
            long createdAt;
            long updatedAt;
        }
    }
}

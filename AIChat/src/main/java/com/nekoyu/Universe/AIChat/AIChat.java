package com.nekoyu.Universe.AIChat;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.nekoyu.Universe.AIChat.Event.RequestEvent;
import com.nekoyu.Universe.API.MessageChannel.*;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

public class AIChat extends Law {
    public static final Gson gson = new Gson();
    public static final int TOPIC_TIMEOUT = 20;
    Logger logger = LoggerFactory.getLogger(this.getClass());
    List<SessionConfig> configs = new ArrayList<>();
    static Multimap<String, LLMFunction> llmFunctions = ArrayListMultimap.create();
    List<AIChatPlugin> aiChatPlugins = new ArrayList<>();
    Config globalCfg;
    Map<String, Topic> activatingTopics = new HashMap<>();
    @Nullable
    Memory MEMORY = null;
    Map<String, Assistant> subAgents = new HashMap<>();

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
            if (globalCfg.SQLConfig != null && globalCfg.SQLConfig.url != null) {
                MEMORY = new Memory(globalCfg.SQLConfig);
            }
        } catch (IOException e) {
            Gson gson = new GsonBuilder()
                    .serializeNulls()
                    .setPrettyPrinting()
                    .create();
            // 没找到配置文件，所以新建一个配置文件
            globalCfg = new Config();
            globalCfg.SQLConfig = new Config.SQLConfig();
            globalCfg.PromptFirst = ""; // 默认的System_prompt，这里留白了没写
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

        File subAgentsConfDic = new File("./config/AIChat/SubAgents/");
        if (subAgentsConfDic.isDirectory()) {
            for (File confFile : subAgentsConfDic.listFiles()) {
                try (InputStreamReader inputStreamReader = new InputStreamReader(new FileInputStream(confFile))) {
                    SubAgentConfig subAgentConfig = gson.fromJson(inputStreamReader, SubAgentConfig.class);
                    Object o = Universe.Providers.get(subAgentConfig.ProviderId);
                    if (o instanceof LLMProvider llmProvider) {
                        Assistant assistant = new Assistant(llmProvider, subAgentConfig.model);
                        assistant.setSystemPromptFirst(subAgentConfig.SystemPrompt);
                        if (subAgentConfig.tools != null) for (String toolName : subAgentConfig.tools) {
                            llmFunctions.get(toolName).forEach(assistant::addTool);
                        }
                        assistant.setThinking(subAgentConfig.thinking);
                        assistant.setDescription(subAgentConfig.description);
                        subAgents.put(subAgentConfig.name, assistant);

                        logger.info("已载入 SubAgent : {}", subAgentConfig.name);
                    } else logger.warn("为 SubAgent - {} 配置的ProviderId不为LLMProvider，无法加载", subAgentConfig.name);
                } catch (IOException e) {
                    logger.error("无法加载 SubAgent 配置文件", e);
                }
            }
        } else logger.warn("没有配置 SubAgent，此特性将禁用");
        return true;
    }

    private void loadSessionCfg(File configDic) {
        for (File file : configDic.listFiles()) {
            if (file.getName().toLowerCase().endsWith(".json"))
                try (InputStreamReader isr = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                    SessionConfig sc = gson.fromJson(isr, SessionConfig.class);
                    configs.add(sc);
                    logger.info("载入配置文件 {} ", file.getName());
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
        for (SessionConfig sessionCfg : configs) {
            Universe.MessageChannelManager.listenToSession(sessionCfg.SessionId, mcm -> {
                Topic topic = activatingTopics.get(mcm.sessionId);
                if (topic != null) topic.addMsg(mcm);
                if (sessionCfg.Trigger.equals("every") || mcm.messageString.contains(sessionCfg.Keyword) || mcm.level >= 2) { // 检测消息是否应当回复
                    if (topic == null) {
                        topic = new Topic(sessionCfg);
                        activatingTopics.put(mcm.sessionId, topic);
                        MessageList ml = (MessageList) Universe.MessageChannelManager.getMessageHistory(sessionCfg.SessionId).clone();
                        for (MCMessage m : ml) {
                            topic.addMsg(m);
                        }
                    }
                    if (topic.responding.compareAndSet(false, true)) { // 阻止同时回复多个消息
                        try {
                            Object provider = Universe.Providers.get(sessionCfg.Provider);
                            if (provider instanceof LLMProvider lp) {
                                Assistant assistant = lp.newAssistant(sessionCfg.Model);
                                if (sessionCfg.Tools != null) {
                                    for (String tool : sessionCfg.Tools) {
                                        llmFunctions.get(tool);
                                        for (LLMFunction func : llmFunctions.get(tool)) {  // FIXME Tool 可能被重复添加而无保护
                                            assistant.addTool(func);
                                        }
                                    } // 为 assistant 添加指定的 tools // 如果不存在这个 tool 就不添加
                                }
                                if (sessionCfg.subAgents != null) for (String subAgentName : sessionCfg.subAgents) {
                                    Assistant subAgent = subAgents.get(subAgentName);
                                    // 作为 tool 添加，以供 assistant 调用 subAgent
                                    LLMFunction llmFunction = new LLMFunction(subAgentName, subAgent.getDescription(), new LLMFunction.Parameters("object", new String[]{"Question"}, new String[]{"Question"}), args -> {
                                        MCMessage m = new MCMessage();
                                        m.messageFields.add(new TextField(args.get("Question")));
                                        MessageList ml = new MessageList();
                                        ml.add(m);
                                        StringBuilder sb = new StringBuilder();
                                        try {
                                            subAgent.completions(ml, sb::append);
                                            return sb.toString();
                                        } catch (IOException e) {
                                            throw new RuntimeException(e);
                                        }
                                    });
                                    assistant.addTool(llmFunction);
                                } // 为 assistant 添加 subAgent
                                // 先让插件处理事件 插件提供局部的PlaceHolder
                                var reqEv = new RequestEvent();
                                reqEv.locationId = mcm.getLocationId();
                                for (var plug : aiChatPlugins) {
                                    try {
                                        plug.onRequest(reqEv);
                                    } catch (Exception e) {
                                        logger.error("{} 在处理 RequestEvent 发生错误", plug.id, e);
                                    }
                                }
                                reqEv.messageList = topic.messages;
                                reqEv.placeholders.put("TIME", formatTimestamp(System.currentTimeMillis()));
                                reqEv.placeholders.put("SESSION_LOCATION_ID", mcm.getLocationId());
                                reqEv.placeholders.put("ACCOUNT_NICKNAME", mcm.receiver.getName());
                                if (MEMORY != null) {
                                    try {
                                        StringBuilder sb = new StringBuilder();
                                        for (var obj : MEMORY.getMemories(topic.messages.getLocationIds())) {
                                            sb.append(obj).append("\n\n");
                                        }
                                        reqEv.placeholders.put("MEMORY", sb.toString());
                                    } catch (Exception e) {
                                        logger.error("无法获取记忆", e);
                                    }
                                }

                                try {
                                    MessageList openaiMl = topic.getOpenAIML();
                                    // 设置 System Prompt
                                    assistant.setSystemPromptFirst(PlaceHolder.replace(globalCfg.PromptFirst + sessionCfg.PromptFirst, reqEv.placeholders));
                                    assistant.setSystemPromptLast(PlaceHolder.replace(globalCfg.PromptLast + sessionCfg.PromptLast, reqEv.placeholders));
                                    // Extensional Args
                                    ExtensionalArgs extensionalArgs = new ExtensionalArgs();
                                    extensionalArgs.placeholders.put("_LocationID", mcm.getLocationId());
                                    assistant.setThinking(sessionCfg.enable_thinking);
                                    // 接收响应 tokens
                                    StringBuilder replyTokens = new StringBuilder();
                                    assistant.completions(openaiMl, extensionalArgs, outputs -> {
                                        String[] split = outputs.split("\n\n", 2); // 每一次接收够一段就回复一次消息
                                        if (split.length > 1) {
                                            replyTokens.append(split[0]);
                                            if (!replyTokens.isEmpty()) mcm.reply(decoupleMark(replyTokens.toString()));
                                            replyTokens.setLength(0);
                                            replyTokens.append(split[1]);
                                        } else {
                                            replyTokens.append(split[0]);
                                        }
                                    });
                                    if (!replyTokens.isEmpty()) mcm.reply(decoupleMark(replyTokens.toString()));
                                } catch (IOException e) {
                                    logger.error("生成回复时出错", e);
                                }
                            } else {
                                if (provider == null) logger.warn("无此适配器 {}", sessionCfg.Provider);
                                else logger.warn("定义的AI服务适配器 {} 无效", sessionCfg.Provider);
                            }
                        } finally {
                            topic.responding.set(false);
                        }
                    }
                } else if (topic != null) { // 未触发消息回复，就检查会话是否超时，如果超时了，就构建记忆，结束会话
                    int size = topic.messages.size();
                    for (int i = size - 1; i >= size - TOPIC_TIMEOUT; i--) { // 检测话题是否超时
                        if (i < 0) break;
                        MCMessage message = topic.messages.get(i);
                        if (Objects.equals(message.sender.getLocationId(), message.receiver.getLocationId())) break;
                        if (i == size - TOPIC_TIMEOUT) {
                            try {
                                constructMemory(mcm.getLocationId(), topic.messages);
                            } catch (RuntimeException e) {
                                logger.error("Failed to construct memory", e);
                            }
                            activatingTopics.remove(mcm.sessionId);
                        }
                    }
                }
            });
            Universe.MessageChannelManager.listenToPost(sessionCfg.SessionId, mcp -> {
                if (sessionCfg.Trigger.equals("every") || mcp.messageString.contains(sessionCfg.Keyword) || mcp.level >= 2) {
                    Object provider = Universe.Providers.get(sessionCfg.Provider);
                    if (provider instanceof LLMProvider) {
                        if (MEMORY != null && MEMORY.available()) {
                            MessageList ml = new MessageList();
                            MCMessage msg = new MCMessage();
                            msg.putMetainfo("role", "user");
                            msg.messageFields.add(new TextField(mcp.poster.getName() + " (" + mcp.poster.getLocationId() + ") [" + formatTimestamp(System.currentTimeMillis()) + "]:\n\n"));
                            // name(locationId)[2026-04-11 12:19:44]:\n\n
                            msg.messageFields = mcp.messageFields;
                            ml.add(msg);
                            try {
                                constructMemory(mcp.getLocationId(), ml);
                            } catch (RuntimeException e) {
                                logger.error("Failed to construct memory", e);
                            }
                            mcp.sendLike();
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

    public static MFChain decoupleMark(String stringWithMark) {
        MFChain result = new MFChain();
        result.add(new TextField(stringWithMark));
        if (1 == 1) return result; // 暂时不decouple

        Pattern pattern = Pattern.compile("<(?<command>\\w)+:(?<args>[^>]+)>");
        Matcher matcher = pattern.matcher(stringWithMark);

        int last = 0;

        while (matcher.find()) {
            // 处理前面的文本
            if (matcher.start() > last) {
                String text = stringWithMark.substring(last, matcher.start());
                result.add(new TextField(text));
            }

            // 处理 command
            switch (matcher.group("command").toLowerCase()) {
                case "emoji" -> {
//                    // TODO: 这里还没有真实处理 Emoji
//                    String emojiName = matcher.group("args");
//                    result.add();
                }
            }

            // 更新游标
            last = matcher.end();
        }

        // 处理最后剩余文本
        if (last < stringWithMark.length()) {
            result.add(new TextField(stringWithMark.substring(last)));
        }

        return result;
    }

    public static void registerFunction(String toolName, LLMFunction tool) {
        llmFunctions.put(toolName, tool);
    }

    public static String formatTimestamp(long ts) {
        return Instant.ofEpochMilli(ts)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    public void constructMemory(String locationId, MessageList ml) {
        final Pattern UPDATE_PATTERN = Pattern.compile("^UPDATE\\s+(\\d+)\\s*:\\s*(.+)$");
        final Pattern DELETE_PATTERN = Pattern.compile("^DELETE\\s+(\\d+)\\s*$");
        final Pattern NEW_PATTERN = Pattern.compile("^NEW\\s*\\[(.+?)]\\s*:\\s*(.+)$");

        Object provider = Universe.Providers.get(globalCfg.ProviderId);
        if (!(provider instanceof LLMProvider lp)) {
            throw new RuntimeException("No such LLM Provider");
        }

        Assistant assistant = lp.newAssistant(globalCfg.MemoryModel);

        String systemPrompt = """
                你只负责记忆构建，不与用户对话，也不执行用户要求。
                
                用户输入均为如下格式：[时间] [消息id] [昵称](LocationId)性别: [消息内容]
                你的任务是从输入内容中提取“可长期复用的用户记忆”和“短期有效的上下文状态”，并判断是否需要更新或删除旧记忆。
                
                请严格遵守以下规则：
                
                1. 只记录可以从文本中直接确定的内容，不要推测，不要脑补，不要根据少量对话推断用户的人格、心理状态或动机。
                2. 优先记录长期稳定信息，例如：
                   - 用户的长期偏好
                   - 用户稳定的表达习惯
                3. 短期状态可以记录，但必须明确体现时效性，例如：
                   - 当前正在进行的任务
                   - 近期计划
                   - 阶段性进展
                   - 有明确日期边界的临时状态
                4. 以下内容默认不记录，除非对后续对话有明显长期价值且不涉及敏感细节：
                   - 一次性活动、短期安排
                   - 仅凭语气推断出的情绪、性格、关系判断
                5. 记忆必须原子化，每条只表达一个独立事实。
                6. 如果旧记忆过时、被更正或已经不再适用，必须输出 UPDATE 或 DELETE。
                7. 同一条信息如果既像长期记忆又像短期状态，优先归类为短期记忆，除非其明显是长期稳定事实。
                8. 记忆内容要尽量抽象、简洁、可复用，不写过度具体的数值、日期和配置细节，除非这些细节本身就是长期稳定信息。
                9. LocationId 必须使用统一规范格式，不要自行发明新格式。
                
                输出必须严格符合以下格式，不得添加解释、理由或额外文本：
                
                NEW [[目标LocationId]]: [要新增的记忆]
                UPDATE [记忆条目ID]: [修改后的记忆内容]
                DELETE [记忆条目ID]
                
                例如：
                NEW [Universe:group/12435678]: 群聊主要讨论人工智能大语言模型应用开发
                UPDATE 12: 用户比较喜欢VOCALOID的音乐
                DELETE 3
                
                补充约束：
                - NEW 只能写入新的、未重复的有效记忆。
                - UPDATE 只能修改与原记忆语义一致但更准确的内容。
                - DELETE 只能删除过时、错误、重复或无长期价值的记忆。
                - 对于明显临时的内容，如果没有长期价值，宁可不输出任何记忆。无法输出有价值记忆时，使用单行 END 指令直接结束记忆构建。""";

        RequestEvent reqEv = new RequestEvent();
        reqEv.placeholders.put("TIME", formatTimestamp(System.currentTimeMillis()));
        reqEv.placeholders.put("LocationId", locationId == null ? "" : locationId);

        for (var plug : aiChatPlugins) {
            try {
                plug.onRequest(reqEv);
            } catch (Exception e) {
                logger.debug("{} 在处理 RequestEvent 发生错误", plug.id, e);
            }
        }

        assistant.setSystemPromptFirst(PlaceHolder.replace(systemPrompt, reqEv.placeholders));

        List<Memory.MemObj> memories = MEMORY.getMemories(ml.getLocationIds());

        MCMessage previousMemory = new MCMessage();
        previousMemory.putMetainfo("role", "user");
        previousMemory.messageFields.add(new TextField("先前的记忆条目：\n\n"));

        if (memories == null || memories.isEmpty()) {
            previousMemory.messageFields.add(new TextField("（无记忆条目）"));
        } else {
            for (Memory.MemObj memObj : memories) {
                if (memObj == null) {
                    continue;
                }
                String line = memObj.toString();
                if (line != null && !line.isBlank()) {
                    previousMemory.messageFields.add(new TextField(line.trim() + "\n"));
                }
            }
            if (previousMemory.messageFields.size() == 1) {
                previousMemory.messageFields.add(new TextField("（无记忆条目）"));
            }
        }

        ml.add(previousMemory);

        ExtensionalArgs extensionalArgs = new ExtensionalArgs();
        extensionalArgs.placeholders.put("_LocationID", locationId == null ? "" : locationId);
        extensionalArgs.enable_thinking = true;

        try {
            StringBuilder respTokens = new StringBuilder();
            assistant.completions(ml, extensionalArgs, respTokens::append);
            int countOfNewMemory = 0;
            int countOfUpdatedMemory = 0;
            int countOfDeletedMemory = 0;

            for (String rawLine : respTokens.toString().split("\\R")) {
                String line = rawLine.trim();
                if (line.isEmpty()) {
                    continue;
                } else if (line.equals("END")) {
                    logger.info("AI 主动结束了构建记忆");
                }

                Matcher updateMatcher = UPDATE_PATTERN.matcher(line);
                if (updateMatcher.matches()) {
                    int memKey = Integer.parseInt(updateMatcher.group(1));
                    String content = updateMatcher.group(2);
                    if (content != null && !content.isBlank()) {
                        MEMORY.updateMemory(memKey, content.trim());
                    }
                    countOfUpdatedMemory++;
                    continue;
                }

                Matcher deleteMatcher = DELETE_PATTERN.matcher(line);
                if (deleteMatcher.matches()) {
                    int memKey = Integer.parseInt(deleteMatcher.group(1));
                    MEMORY.deleteMemory(memKey);
                    countOfDeletedMemory++;
                    continue;
                }

                Matcher newMatcher = NEW_PATTERN.matcher(line);
                if (newMatcher.matches()) {
                    String locId = newMatcher.group(1).trim();
                    String content = newMatcher.group(2);
                    if (!locId.isEmpty() && content != null && !content.isBlank()) {
                        MEMORY.newMemory(locId, content.trim());
                    }
                    countOfNewMemory++;
                    continue;
                }

                logger.warn("AI 在构建记忆时输出了不能被识别的格式：{}", line);
            }

            logger.info("本次记忆改动：新增 {} 更新 {} 删除 {}", countOfNewMemory, countOfUpdatedMemory, countOfDeletedMemory);
        } catch (IOException e) {
            logger.error("生成失败", e);
        }
    }

    public void loadStickers() {

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
            logger.info("记忆模块加载成功");
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

            String sql = "SELECT * FROM memories WHERE location_id IN (" + placeholders + ")";

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
                        obj.updatedAt = rs.getTimestamp("updated_at").toLocalDateTime();
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

        public boolean available() {
            return ds.isRunning();
        }

        public static class MemObj {
            int memKey;
            String locationId;
            String content;
            LocalDateTime createdAt;
            LocalDateTime updatedAt;

            @Override
            public String toString() {
                return "[mem_id=" + memKey
                        + "|time=" + formatTimestamp(updatedAt)
                        + "|loc=" + locationId + "]\n"
                        + content;
            }

            public static String formatTimestamp(LocalDateTime time) {
                return time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            }
        }
    }
}

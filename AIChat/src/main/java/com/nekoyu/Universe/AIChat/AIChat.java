package com.nekoyu.Universe.AIChat;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.nekoyu.Universe.AIChat.Event.RequestEvent;
import com.nekoyu.Universe.API.MessageChannel.*;
import com.nekoyu.Universe.API.MessageChannel.MessageField.StickerField;
import com.nekoyu.Universe.API.MessageChannel.MessageField.TextField;
import com.nekoyu.Universe.API.PlaceHolder;
import com.nekoyu.Universe.API.Providers.LLMProvider.Assistant;
import com.nekoyu.Universe.API.Providers.LLMProvider.Embedding;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.EmbeddingRequest;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ExtensionalArgs;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.JsonSchema;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.LLMFunction;
import com.nekoyu.Universe.API.Providers.LLMProvider.LLMProvider;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.EmbeddingResponse;
import com.nekoyu.Universe.API.UniverseChannel;
import com.nekoyu.Universe.LawsLoader.Law;
import com.nekoyu.Universe.Universe;
import com.nekoyu.Universe.Utils.Time;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

public class AIChat extends Law {
    public static final Gson gson = new Gson();
    public static final int TOPIC_TIMEOUT = 20;
    static Logger logger = LoggerFactory.getLogger(AIChat.class);
    static Multimap<String, LLMFunction> llmFunctions = ArrayListMultimap.create();
    List<SessionConfig> configs = new ArrayList<>();
    List<AIChatPlugin> aiChatPlugins = new ArrayList<>();
    Config globalCfg;
    Map<String, Topic> activatingTopics = new HashMap<>();
    @Nullable
    Memory memory = null;
    ExternalKnowledgeBase externalKnowledgeBase = null;
    Map<String, Assistant> subAgents = new HashMap<>();
    Multimap<String, File> emojisCollect = ArrayListMultimap.create();
    public HikariDataSource dataSource;

    public static void registerFunction(String toolName, LLMFunction tool) {
        llmFunctions.put(toolName, tool);
    }

    public static String formatTimestamp(long ts) {
        return Instant.ofEpochMilli(ts)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

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
                HikariConfig config = new HikariConfig();
                config.setDriverClassName("com.mysql.cj.jdbc.Driver");
                config.setJdbcUrl(globalCfg.SQLConfig.url);
                config.setUsername(globalCfg.SQLConfig.user);
                config.setPassword(globalCfg.SQLConfig.password);
                dataSource = new HikariDataSource(config);
                logger.info("数据库连接成功");
            }

            if (globalCfg.Qdrant != null) {
                if (globalCfg.Memory != null) {
                    if (Universe.Providers.get(globalCfg.Annotator.embeddingProvider) instanceof Embedding embedding) {
                        memory = new com.nekoyu.Universe.AIChat.Memory(globalCfg.Qdrant, globalCfg.Memory);
                        // 如果记忆可用，那么给主Agent提供主动查询记忆内容的方法
                        LLMFunction query_memory = LLMFunction.builder()
                                .name("QueryMemory")
                                .description("""
                                        主动查询记忆，如果问题不仅仅与某一个用户关联，则不需要提供LocationId，直接提问；
                                        如果问题与某一个用户相关，那么需要提供准确的LocationId，并在问题中固定使用“用户”的称呼""")
                                .parameters(JsonSchema.object()
                                        .property("Question", JsonSchema.string().description("需要查询记忆的问题"))
                                        .property("LocationId", JsonSchema.string().description("与问题相关的用户的 LocationId"))
                                        .required("Question"))
                                .callback(args -> {
                                    JsonObject o = args.getAsJsonObject();
                                    String locationId = o.has("LocationId") ? o.get("LocationId").getAsString() : null;
                                    List<String> locationIds = new ArrayList<>();
                                    if (locationId != null) {
                                        locationIds.add(locationId);
                                    } else {
                                        for (String locationIdd : o.get("LOCATION_IDS").getAsString().split(" ")) {
                                            if (!locationIdd.isEmpty()) locationIds.add(locationIdd);
                                        }
                                    }
                                    String question = o.get("Question").getAsString();
                                    try {
                                        EmbeddingRequest request = new EmbeddingRequest();
                                        MFChain mfc = new MFChain();
                                        mfc.add(new TextField(question));
                                        request.message.add(mfc);
                                        EmbeddingResponse response = embedding.embedding(request);
                                        List<Float> vector = new ArrayList<>();
                                        for (double a : response.data.get(0).embedding) {
                                            vector.add((float) a);
                                        }
                                        List<Memory.Item> queryResult = memory.query(vector, locationIds);
                                        StringBuilder builder = new StringBuilder().append("查询到的记忆：\n");
                                        for (Memory.Item item : queryResult) {
                                            builder.append(item.toString()).append("\n");
                                        }
                                        MFChain result = new MFChain();
                                        result.add(new TextField(builder.toString()));
                                        return result;
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                })
                                .build();
                        llmFunctions.put("QueryMemory", query_memory);
                        llmFunctions.put("ExternalKnowledgeBase",
                                LLMFunction.builder()
                                        .name("QueryExternalKnowledgeBase")
                                        .description("查询知识库中内容，需要调用外部知识时应当优先从知识库查询而非直接联网，参数为需要知道的问题，必须是完整的句子")
                                        .parameters(JsonSchema.object()
                                                .property("question", JsonSchema.string().description("需要了解的问题"))
                                                .required("question"))
                                        .callback(args -> {
                                            try {
                                                MFChain result = new MFChain();
                                                result.add(new TextField(externalKnowledgeBase.search(args.getAsJsonObject().get("question").getAsString())));
                                                return result;
                                            } catch (IOException e) {
                                                MFChain result = new MFChain();
                                                result.add(new TextField("调用失败" + e.getMessage()));
                                                logger.error("模型主动调用外部知识库时出错", e);
                                                return result;
                                            }
                                        })
                                        .build()
                        );
                    }
                }
                if (globalCfg.ExternalKnowledgeBase != null) {
                    externalKnowledgeBase = new ExternalKnowledgeBase(globalCfg.Qdrant, globalCfg.ExternalKnowledgeBase);
                }
            }
        } catch (IOException e) {
            Gson gson = new GsonBuilder()
                    .serializeNulls()
                    .setPrettyPrinting()
                    .create();
            // 没找到配置文件，所以新建一个配置文件
            globalCfg = new Config();
            globalCfg.PromptFirst = ""; // 默认的System_prompt，这里留白了没写
            globalCfg.PromptLast = "";
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
                    } else
                        logger.warn("为 SubAgent - {} 配置的ProviderId不为LLMProvider，无法加载", subAgentConfig.name);
                } catch (IOException e) {
                    logger.error("无法加载 SubAgent 配置文件", e);
                }
            }
        } else logger.warn("没有配置 SubAgent，此特性将禁用");

        Path emojisDic = Paths.get("./data/AIChat/emojis/");
        loadEmojis(emojisDic);

        final Object lock = new Object();
        new Thread(() -> {
            try {
                WatchService watchService = FileSystems.getDefault().newWatchService();
                emojisDic.register(
                        watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE,
                        StandardWatchEventKinds.ENTRY_MODIFY
                );

                final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
                ScheduledFuture<?> pendingTask = null;
                while (true) {
                    try {
                        WatchKey key = watchService.take();

                        synchronized (lock) {
                            for (WatchEvent<?> event : key.pollEvents()) {
                                if (pendingTask != null && !pendingTask.isDone()) {
                                    pendingTask.cancel(false);
                                }

                                pendingTask = executorService.schedule(() -> {
                                    loadEmojis(emojisDic);
                                }, 5, TimeUnit.SECONDS);
                            }
                        }

                        key.reset();
                    } catch (InterruptedException e) {
                        logger.error(e.getMessage());
                    }
                }
            } catch (IOException e) {
                logger.warn("无法监听文件变化，将不能热重载表情包");
            }
        }).start();
        return true;
    }

    private void loadEmojis(Path emojisDic) {
        emojisCollect.clear();
        if (Files.isDirectory(emojisDic)) {
            // Load emojis list for chatbot
            try (Stream<Path> paths = Files.walk(emojisDic)) {
                paths.filter(Files::isRegularFile).forEach(path -> {
                    Path relative = emojisDic.relativize(path);
                    if (relative.getNameCount() >= 2) {
                        StringBuilder collectName = new StringBuilder();
                        for (int i = 0; i < relative.getNameCount() - 1; i++) {
                            if (!collectName.isEmpty()) collectName.append("-");
                            collectName.append(relative.getName(i));
                        }
                        emojisCollect.put(collectName.toString(), path.toFile());
                    }
                });
            } catch (IOException e) {
                logger.warn("未能正确获取表情包列表");
            }
        }
        logger.info("已刷新表情包库，载入 {} 个表情包", emojisCollect.size());
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
        UniverseChannel.addHttpHandler("/AIChat/", exchange -> {
            if (!exchange.getRequestMethod().equals("GET")) {
                String resp = "405 Method Not Allowed";
                exchange.sendResponseHeaders(405, resp.getBytes().length);
                OutputStream os = exchange.getResponseBody();
                os.write(resp.getBytes());
                os.close();
            }
            try {
                String[] way = exchange.getRequestURI().toString().split("/");
                switch (way[2]) {
                    case "emoji" -> {
                        Collection<File> collection = emojisCollect.get(way[way.length - 1]);
                        int index = new Random().nextInt(collection.size());
                        File image = (File) collection.toArray()[index];
                        exchange.sendResponseHeaders(200, image.length());
                        try (OutputStream os = exchange.getResponseBody();
                             FileInputStream fis = new FileInputStream(image)) {

                            byte[] buffer = new byte[8192];
                            int len;
                            while ((len = fis.read(buffer)) != -1) {
                                os.write(buffer, 0, len);
                            }
                        }
                    }
//                    case "ekb" -> {
//                        String question = URLDecoder.decode(way[4], StandardCharsets.UTF_8);
//                        exchange.getResponseHeaders().set(
//                                "Content-Type",
//                                "application/json; charset=UTF-8"
//                        );
//                        switch (way[3]) {
//                            case "query" -> {
//                                List<ExternalKnowledgeBase.Item> items = externalKnowledgeBase.query(question, null);
//                                String resp = gson.toJson(items);
//                                byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
//                                exchange.sendResponseHeaders(200, bytes.length);
//                                OutputStream os = exchange.getResponseBody();
//                                os.write(bytes);
//                                os.flush();
//                                os.close();
//                            }
//                            case "quiz" -> {
//                                List<ExternalKnowledgeBase.Item> items = externalKnowledgeBase.quiz(question);
//                                String resp = gson.toJson(items);
//                                byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
//                                exchange.sendResponseHeaders(200, bytes.length);
//                                OutputStream os = exchange.getResponseBody();
//                                os.write(bytes);
//                                os.flush();
//                                os.close();
//                            }
//                            case "update" -> {
//                                exchange.sendResponseHeaders(200, 0);
//                                externalKnowledgeBase.constructItems(externalKnowledgeBase.fetch(question));
//                            }
//                        }
//                    }
                    default -> exchange.sendResponseHeaders(400, 0);
                }
            } catch (IndexOutOfBoundsException e) {
                exchange.sendResponseHeaders(400, 0);
            } catch (Exception e) {
                logger.error("API 出错", e);
                exchange.sendResponseHeaders(500, 0);
            }
        });
        for (SessionConfig sessionCfg : configs) {
            LLMProvider llmProvider;
            try {
                llmProvider = (LLMProvider) Universe.Providers.get(sessionCfg.Provider);
            } catch (ClassCastException e) {
                logger.error("Provider {} 不是有效的LLMProvider", sessionCfg.Provider);
                continue;
            }
            boolean leading = (sessionCfg.PromptFirst + sessionCfg.PromptLast + globalCfg.PromptFirst + globalCfg.PromptLast).contains("%LEADING%");
            MessageChannelManager.listenToSession(sessionCfg.SessionId, mcm -> {
                Topic topic = activatingTopics.get(mcm.sessionId);
                if (topic != null) topic.addMsg(mcm);
                // 检测消息是否应当回复
                if (sessionCfg.Trigger.equals("every") || mcm.messageString.contains(sessionCfg.Keyword) || mcm.level >= 2) {
                    if (topic == null) {
                        topic = new Topic(sessionCfg);
                        activatingTopics.put(mcm.sessionId, topic);
                        MessageList ml = (MessageList) MessageChannelManager.getMessageHistory(sessionCfg.SessionId).clone();
                        for (MCMessage m : ml) {
                            topic.addMsg(m);
                        }
                    }
                    if (topic.responding.compareAndSet(false, true)) { // 阻止同时回复多个消息
                        try {
                            Assistant assistant = llmProvider.newAssistant(sessionCfg.Model);
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
                                LLMFunction llmFunction = LLMFunction.builder()
                                        .name(subAgentName)
                                        .description(subAgent.getDescription())
                                        .parameters(JsonSchema.object()
                                                .property("Question", JsonSchema.string().description("要交给子代理处理的问题"))
                                                .required("Question"))
                                        .callback(args -> {
                                            MCMessage m = new MCMessage();
                                            m.messageFields.add(new TextField(args.getAsJsonObject().get("Question").getAsString()));
                                            MessageList ml = new MessageList();
                                            ml.add(m);
                                            StringBuilder sb = new StringBuilder();
                                            try {
                                                subAgent.completions(ml, sb::append);
                                                MFChain mfc = new MFChain();
                                                mfc.add(new TextField(sb.toString()));
                                                return mfc;
                                            } catch (IOException e) {
                                                throw new RuntimeException(e);
                                            }
                                        })
                                        .build();
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
                            reqEv.placeholders.put("TIME", formatTimestamp(System.currentTimeMillis())); // 时间
                            reqEv.placeholders.put("SESSION_LOCATION_ID", mcm.getLocationId()); // 会话 LocationId
                            reqEv.placeholders.put("ACCOUNT_NICKNAME", mcm.receiver.getName()); // 账号昵称
                            if (leading) try {
                                String lead = leading(mcm, topic);
                                reqEv.placeholders.put("LEADING", lead);
                            } catch (IOException e) {
                                logger.warn("Leading失效", e);
                                reqEv.placeholders.put("LEADING", "Failed");
                            }
                            StringBuilder emojiSetAvailable = new StringBuilder();
                            for (String emojiName : emojisCollect.keySet()) {
                                if (emojiName != null) emojiSetAvailable.append(emojiName).append(" ");
                            }
                            reqEv.placeholders.put("AVAILABLE_EMOJI", emojiSetAvailable.toString());
                            StringBuilder locationIdsString = new StringBuilder();
                            for (var s : topic.messages.getLocationIds()) {
                                locationIdsString.append(s).append(" ");
                            }
                            reqEv.placeholders.put("LOCATION_IDS", locationIdsString.toString());

                            if (memory != null) {
                                try {
                                    StringBuilder sb = new StringBuilder();
                                    List<String> locationIds = topic.messages.getLocationIds();
                                    for (var obj : memory.getLastMemoryItems(locationIds, locationIds.size() * 5)) {
                                        sb.append(obj.confidence)
                                                .append(" ")
                                                .append(Time.formatTimestamp(obj.updateAt))
                                                .append("[")
                                                .append(obj.locationId)
                                                .append("]: ")
                                                .append(obj.content)
                                                .append("\n");
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
                                extensionalArgs.assistant = mcm.receiver;
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
            MessageChannelManager.listenToPost(sessionCfg.SessionId, mcp -> {
                if (sessionCfg.Trigger.equals("every") || mcp.messageString.contains(sessionCfg.Keyword) || mcp.level >= 2) {
                    logger.info("接收到MCPost");
                    if (memory != null) {
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

    public MFChain decoupleMark(String stringWithMark) {
        MFChain result = new MFChain();

        Matcher matcher = Pattern.compile("<(?<command>\\w+):(?<args>[^>]+)>").matcher(stringWithMark);

        int last = 0;

        while (matcher.find()) {
            // 处理前面的文本
            if (matcher.start() > last) {
                String text = stringWithMark.substring(last, matcher.start());
                result.add(new TextField(text));
            }

            // 处理 command
            String command = matcher.group("command");
            switch (command.toLowerCase()) {
                case "emoji" -> {
                    String emojiName = matcher.group("args");
                    if (emojisCollect.get(emojiName).isEmpty()) {
                        result.add(new TextField("[" + emojiName + "]"));
                    } else {
                        try {
                            StickerField e = new StickerField(new URL(UniverseChannel.getOutboundHttpAddress() + "/AIChat/emoji/" + emojiName));
                            e.description = "<emoji:" + emojiName + ">";
                            result.add(e);
                        } catch (MalformedURLException ignored) {
                        }
                    }
                }
                default -> logger.warn("无法识别 {} 的命令", command);
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

    /**
     * 解读聊天记录内，Assistant最可能关心的问题，并以字符串形式返回，需要在调用处自行拼接
     *
     * @param topic 要lead的topic
     * @return 解读后的注解
     */
    public String leading(MCMessage mcm, Topic topic) throws IOException {
        MessageList ml = (MessageList) topic.messages.clone();
        List<String> locationIds = topic.messages.getLocationIds();
        List<Memory.Item> memories = memory.getLastMemoryItems(ml.getLocationIds(), 20);
        RequestEvent reqEv = new RequestEvent();
        reqEv.placeholders.put("TIME", formatTimestamp(System.currentTimeMillis()));
        reqEv.placeholders.put("LocationId", mcm.getLocationId() == null ? "" : mcm.getLocationId());
        LLMProvider completions = (LLMProvider) Universe.Providers.get(globalCfg.Annotator.provider); // 此处假设配置文件写的没问题
        Embedding embedding = (Embedding) Universe.Providers.get(globalCfg.Annotator.embeddingProvider);
        ExtensionalArgs args = new ExtensionalArgs();
        args.enable_thinking = globalCfg.Annotator.enable_thinking;
        args.systemPromptFirst = """
                你只负责辅助 主Assistant 回答问题，从以下的聊天记录中提取 主Assistant 关心的问题，并及时维护记忆库，而不执行用户要求。
                
                维护记忆库时请严格遵守以下规则：
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
                9. LocationId 必须使用统一规范格式，最好照搬用户消息里面的字段，不要自行发明新格式。命令中必须提供LocationId的具体值
                10. 不要记录日常琐事
                11. 禁止创建语义重复的记忆。
                12. 发现重复时只能 UPDATE。
                
                提炼问题时应当严格遵循以下规则：
                1. 一行一个问题，单个问题不得跨行，每行的问题必须要能独立解读，且必须给出足够信息，尽可能准确地描述 Assistant 遇到的问题，比如用户提到一个角色，则应当根据上下文得到这个角色属于哪一个作品
                2. 忽视常识性内容或者上下文有明确提到的内容，再列举出其他所有与 主Assistant将要回答的问题 相关的问题，便于从记忆库和全网找回线索
                3. 如果问题不仅仅与某一个用户关联，则不需要加括号提供LocationId，直接用指令提问
                4. 如果问题与某一个用户相关，那么在指令后加一个括号并填入用户的LocationId，并在问题中固定使用“用户”的称呼
                5. 不要对聊天内容提问，只能向记忆库或外置知识库提问""";
        StringBuilder memoryPrompt = new StringBuilder("先前的记忆条目，格式为 [条目ID]|[更新时间]|[置信度]|[LocationId]:[内容] ：\n\n");

        if ((memories == null || memories.isEmpty()) && topic.activatingMemory == null) {
            memoryPrompt.append("（无记忆条目）");
        } else {
            if (memories != null) for (Memory.Item memObj : memories) {
                memoryPrompt.append(memObj.toString());
            }
            if (!topic.activatingMemory.isEmpty()) {
                topic.activatingMemory.values().forEach(memObj -> memoryPrompt.append(memObj.id).append("|").append(memObj).append("\n"));
            }
        }
        memoryPrompt.append("""
                输出必须严格符合以下格式，允许先解释后输出指令，但指令必须在独立行，且指令不允许包含多余参数：
                
                NEW [置信度] [[目标LocationId]]: [要新增的记忆]
                UPDATE [记忆条目ID] [置信度]: [修改后的记忆内容]
                DELETE [记忆条目ID]
                QUIZ [问题]
                QUIZ(LocationId) [问题]
                例如（只参考格式，不可参考参数）：
                NEW 0.76 [Universe:group/12435678]: 群聊主要讨论人工智能大语言模型应用开发
                UPDATE d6e23098-9428-4fe1-a1b0-c8f58dd8c7d4 0.63: 用户比较喜欢VOCALOID的音乐
                DELETE 55eaafe9-ad8c-4fbc-8a59-6b136c84d971
                QUIZ 《异环》是什么时候发布的游戏
                QUIZ 《绝区零》的‘啥子蛇’是什么角色
                QUIZ 《异环》的娜娜莉怎么配队
                QUIZ(example_platform:example_id) 用户的电脑的硬件配置是什么
                补充约束：
                - NEW 只能写入新的、未重复的有效记忆。
                - UPDATE 只能修改与原记忆语义一致但更准确的内容。
                - DELETE 只能删除过时、错误、重复或无长期价值的记忆。
                - 对于明显临时的内容，如果没有长期价值，宁可不输出任何记忆。
                - 如果不能提取有用记忆和Assistant遇到的非常识性问题，输出一句"END"直接结束输出""");
        args.systemPromptLast = memoryPrompt.toString();

        StringBuilder sb = new StringBuilder();
        completions.completions(globalCfg.Annotator.model, ml, null, args, sb::append);
        int countOfUpdatedMemory = 0;
        int countOfDeletedMemory = 0;
        List<Memory.Item> newMemory = new ArrayList<>();
        class Quiz {
            List<String> locationIds = new ArrayList<>();
            String question;
        }
        List<Quiz> questions = new ArrayList<>();
        logger.debug(sb.toString());
        for (String line : sb.toString().split("\n")) {
            if (line.strip().equalsIgnoreCase("END")) break; // 允许LLM主动结束记忆更新和注释
            Pattern COMMAND_PATTERN =
                    Pattern.compile("^([^(\\s]+)(?:\\(([^)]*)\\))?");
            Matcher matcher = COMMAND_PATTERN.matcher(line);
            if (!matcher.find()) continue;
            String command = matcher.group(1).toUpperCase();
            String cmdArgs = matcher.group(2);
            switch (command) {
                case "NEW", "UPDATE", "DELETE" -> {
                    final Pattern UPDATE_PATTERN = Pattern.compile("^UPDATE\\s+([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\s+([0-9]*\\.?[0-9]+)\\s*:\\s*(.+)$");
                    final Pattern DELETE_PATTERN = Pattern.compile("^DELETE\\\\s+([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\\\s*$");
                    final Pattern NEW_PATTERN = Pattern.compile("^NEW\\s+([0-9]*\\.?[0-9]+)\\s+\\[(.+?)]\\s*:\\s*(.+)$");
                    Matcher updateMatcher = UPDATE_PATTERN.matcher(line);
                    if (updateMatcher.matches()) {
                        Memory.Item item = topic.activatingMemory.get(UUID.fromString(updateMatcher.group(1)));
                        if (item == null) {
                            logger.warn("傻子模型 {} 想修改一个不存在的记忆条目ID👍", globalCfg.Annotator.model);
                            continue;
                        }
                        UUID uuid = item.id;
                        float confidence = Float.parseFloat(updateMatcher.group(2));
                        String content = updateMatcher.group(3);
                        memory.update(uuid, confidence, content.trim());
                        countOfUpdatedMemory++;
                        continue;
                    }

                    Matcher deleteMatcher = DELETE_PATTERN.matcher(line);
                    if (deleteMatcher.matches()) {
                        UUID key = UUID.fromString(deleteMatcher.group(1));
                        Memory.Item item = topic.activatingMemory.get(key);
                        if (item == null) {
                            logger.warn("傻子模型 {} 想删一个不存在的记忆条目ID👍", globalCfg.Annotator.model);
                            continue;
                        }
                        memory.delete(topic.activatingMemory.remove(key).id);
                        countOfDeletedMemory++;
                        continue;
                    }

                    Matcher newMatcher = NEW_PATTERN.matcher(line);
                    if (newMatcher.matches()) {
                        float confidence = Float.parseFloat(newMatcher.group(1));
                        String locId = newMatcher.group(2).trim();
                        if (locId.equals("Universe:group/12435678")) {
                            logger.warn("傻子模型 {} 赢了，照着模板抄一个错的参数👍记忆无法插入", globalCfg.Annotator.model);
                            continue;
                        }
                        String content = newMatcher.group(3);
                        Memory.Item item = new Memory.Item();
                        item.confidence = confidence;
                        item.locationId = locId;
                        item.content = content;
                        newMemory.add(item);
                        topic.activatingMemory.put(item.id, item);
                        continue;
                    }
                }
                case "QUIZ" -> {
                    String quiz = line.split(" ", 2)[1];
                    if (Objects.equals(cmdArgs, "example_platform:example_id")) {
                        logger.warn("傻子模型 {} 赢了，照着模板抄一个错的参数👍无法按用户实施精确查找记忆", globalCfg.Annotator.model);
                        cmdArgs = null;
                    }
                    Quiz selection = new Quiz();
                    if (cmdArgs != null && locationIds.contains(cmdArgs)) {
                        selection.locationIds.add(cmdArgs);
                    } else selection.locationIds = locationIds;
                    selection.question = quiz;
                    questions.add(selection);
                }
            }
        }
        if (!newMemory.isEmpty()) memory.insert(newMemory);
        EmbeddingRequest req = new EmbeddingRequest();
        for (Quiz quiz : questions) {
            req.message.add(new MFChain(new TextField(quiz.question)));
        }
        List<Memory.Item> memoryResults = new ArrayList<>();
        List<ExternalKnowledgeBase.Item> ekbResults = new ArrayList<>();
        StringBuilder ret = new StringBuilder();
        if (!questions.isEmpty()) {
            List<String> strings = new ArrayList<>();
            questions.forEach(question -> strings.add(question.question));
            logger.debug("Questions in this round: {}", strings);
            EmbeddingResponse res = embedding.embedding(req);
            if (res.data.size() != questions.size()) {
                throw new RuntimeException("Embedding的结果数量不正确，逻辑中断");
            }
            for (var data : res.data) {
                int idx = data.index;
                List<Float> vector = new ArrayList<>();
                for (double v : data.embedding) {
                    vector.add((float) v);
                }
                List<Memory.Item> memoryResult =
                        memory.query(vector, questions.get(idx).locationIds);
                memoryResults.addAll(memoryResult);
                List<ExternalKnowledgeBase.Item> ekbResult = externalKnowledgeBase.query(vector, null);
                ekbResults.addAll(ekbResult);
            }
            memoryResults.forEach(result -> topic.activatingMemory.put(result.id, result));
            ret.append("本地记忆内容：\n");
            for (Memory.Item item : topic.activatingMemory.values()) {
                ret.append(item.toString()).append("\n");
            }
            ret.append("\n知识库内容：\n");
            for (ExternalKnowledgeBase.Item item : ekbResults) {
                ret.append(item.toString()).append("\n");
            }
        }
        String log = "";
        if (countOfUpdatedMemory != 0 || countOfDeletedMemory != 0 || !newMemory.isEmpty())
            log += "本次记忆改动：新增 " + newMemory.size() + " 更新 " + countOfUpdatedMemory + " 删除 " + countOfDeletedMemory + " ";
        if (!memoryResults.isEmpty()) log += "命中 " + memoryResults.size() + " 条本地记忆";
        if (!log.isEmpty()) logger.info(log);
        return ret.toString();
    }

    public void constructMemory(String locationId, MessageList ml) {
        final Pattern UPDATE_PATTERN = Pattern.compile(
                "^UPDATE\\s+([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\s+([0-9]*\\.?[0-9]+)\\s*:\\s*(.+)$"
        );
        final Pattern DELETE_PATTERN = Pattern.compile(
                "^DELETE\\s+([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\s*$"
        );
        final Pattern NEW_PATTERN = Pattern.compile(
                "^NEW\\s+([0-9]*\\.?[0-9]+)\\s+\\[(.+?)]\\s*:\\s*(.+)$"
        );

        Object provider = Universe.Providers.get(globalCfg.ProviderId);
        if (!(provider instanceof LLMProvider lp)) {
            throw new RuntimeException("No such LLM Provider");
        }

        Assistant assistant = lp.newAssistant(globalCfg.Annotator.model);

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
                9. LocationId 必须使用统一规范格式，最好照搬用户消息里面的字段，不要自行发明新格式。
                
                输出必须严格符合以下格式，不得添加解释、理由或额外文本：
                
                NEW [置信度] [[目标LocationId]]: [要新增的记忆]
                UPDATE [记忆条目ID] [置信度]: [修改后的记忆内容]
                DELETE [记忆条目ID]
                
                例如：
                NEW 0.76 [Universe:group/12435678]: 群聊主要讨论人工智能大语言模型应用开发
                UPDATE d6e23098-9428-4fe1-a1b0-c8f58dd8c7d4 0.63: 用户比较喜欢VOCALOID的音乐
                DELETE 55eaafe9-ad8c-4fbc-8a59-6b136c84d971
                
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

        List<Memory.Item> memories = memory.getLastMemoryItems(ml.getLocationIds(), 20);

        Map<UUID, Memory.Item> memoryIndex = new HashMap<>();
        MCMessage previousMemory = new MCMessage();
        previousMemory.putMetainfo("role", "user");
        previousMemory.messageFields.add(new TextField("先前的记忆条目，格式为 [条目ID]|[更新时间]|[置信度]|[LocationId]:[内容] ：\n\n"));

        if (memories == null || memories.isEmpty()) {
            previousMemory.messageFields.add(new TextField("（无记忆条目）"));
        } else {
            for (Memory.Item memObj : memories) {
                if (memObj == null) {
                    continue;
                }
                memoryIndex.put(memObj.id, memObj);
                previousMemory.messageFields.add(new TextField(memObj.toString()));
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

            List<Memory.Item> newMemory = new ArrayList<>();
            int countOfUpdatedMemory = 0;
            int countOfDeletedMemory = 0;

            for (String rawLine : respTokens.toString().split("\\R")) {
                String line = rawLine.trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.equals("END")) {
                    logger.info("AI 主动结束了构建记忆");
                    break;
                }

                Matcher updateMatcher = UPDATE_PATTERN.matcher(line);
                if (updateMatcher.matches()) {
                    try {
                        UUID uuid = UUID.fromString(updateMatcher.group(1));
                        Memory.Item item = memoryIndex.get(uuid);
                        if (item == null) {
                            logger.warn("AI 想修改一个不存在的记忆条目ID: {}", uuid);
                            continue;
                        }

                        float confidence = Float.parseFloat(updateMatcher.group(2));
                        String content = updateMatcher.group(3).trim();
                        memory.update(uuid, confidence, content);
                        countOfUpdatedMemory++;
                    } catch (Exception e) {
                        logger.warn("解析 UPDATE 指令失败：{}", line, e);
                    }
                    continue;
                }

                Matcher deleteMatcher = DELETE_PATTERN.matcher(line);
                if (deleteMatcher.matches()) {
                    try {
                        UUID uuid = UUID.fromString(deleteMatcher.group(1));
                        Memory.Item item = memoryIndex.get(uuid);
                        if (item == null) {
                            logger.warn("AI 想删除一个不存在的记忆条目ID: {}", uuid);
                            continue;
                        }

                        memory.delete(uuid);
                        countOfDeletedMemory++;
                        memoryIndex.remove(uuid);
                    } catch (Exception e) {
                        logger.warn("解析 DELETE 指令失败：{}", line, e);
                    }
                    continue;
                }

                Matcher newMatcher = NEW_PATTERN.matcher(line);
                if (newMatcher.matches()) {
                    try {
                        float confidence = Float.parseFloat(newMatcher.group(1));
                        String locId = newMatcher.group(2).trim();
                        String content = newMatcher.group(3).trim();

                        Memory.Item item = new Memory.Item();
                        item.confidence = confidence;
                        item.locationId = locId;
                        item.content = content;

                        newMemory.add(item);
                        memoryIndex.put(item.id, item);
                    } catch (Exception e) {
                        logger.warn("解析 NEW 指令失败：{}", line, e);
                    }
                    continue;
                }

                logger.warn("AI 在构建记忆时输出了不能被识别的格式：{}", line);
            }

            if (!newMemory.isEmpty()) {
                memory.insert(newMemory);
            }

            logger.info("本次记忆改动：新增 {} 更新 {} 删除 {}", newMemory.size(), countOfUpdatedMemory, countOfDeletedMemory);
        } catch (IOException e) {
            logger.error("生成失败", e);
        }
    }
}

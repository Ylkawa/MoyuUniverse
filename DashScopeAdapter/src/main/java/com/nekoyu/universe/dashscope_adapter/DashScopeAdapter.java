package com.nekoyu.universe.dashscope_adapter;

import com.google.gson.Gson;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.EmbeddingRequest;
import com.nekoyu.Universe.LawsLoader.Law;
import com.nekoyu.Universe.Universe;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DashScopeAdapter extends Law {
    private final Gson gson = new Gson();
    private final Map<String, DashScopeChannel> registered = new LinkedHashMap<>();
    private final Map<String, Object> previous = new LinkedHashMap<>();
    @Override public boolean prepare() { return prepareDirectory(getConfigDir().toPath()); }
    /** 正常插件启动及独立验证共用同一配置加载与注册入口。 */
    public boolean prepareDirectory(Path directory) {
        boolean success = true;
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { register(gson.fromJson(reader, Config.class)); }
                catch (IOException | RuntimeException e) {
                    logger.error("DashScope Provider 配置无法加载：{} ({})", file.getFileName(), e.getClass().getSimpleName()); success = false;
                }
            }
        } catch (IOException e) { logger.error("DashScope Provider 配置目录无法读取：{}", directory); return false; }
        return success;
    }
    public DashScopeChannel register(Config config) throws IOException {
        if (config == null || config.ProviderId == null || config.ProviderId.isBlank()) throw new IOException("ProviderId is required");
        String id = config.ProviderId;
        if (registered.containsKey(id)) throw new IOException("Duplicate DashScope ProviderId");
        var channel = new DashScopeChannel();
        channel.apikey = config.APIKey;
        channel.defaultModel = config.DefaultModel; channel.defaultEmbeddingModel = config.DefaultEmbeddingModel;
        channel.embeddingSpaceVersion = config.EmbeddingSpaceVersion; channel.embeddingResolutionLevel = config.EmbeddingResolutionLevel;
        channel.baseurl = config.BaseUrl; channel.logger = LoggerFactory.getLogger("DashScope C - " + id);
        if (config.DefaultEmbeddingModel != null) channel.embeddingSpace(new EmbeddingRequest());
        previous.put(id, Universe.Providers.put(id, channel)); registered.put(id, channel);
        return channel;
    }
    @Override public void run() {}
    @Override public void stop() {
        for (var entry : registered.entrySet()) {
            if (Universe.Providers.remove(entry.getKey(), entry.getValue()) && previous.get(entry.getKey()) != null)
                Universe.Providers.putIfAbsent(entry.getKey(), previous.get(entry.getKey()));
        }
        registered.clear(); previous.clear();
    }
}

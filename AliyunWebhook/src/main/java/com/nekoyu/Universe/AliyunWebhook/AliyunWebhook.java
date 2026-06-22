package com.nekoyu.Universe.AliyunWebhook;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.nekoyu.Universe.API.MessageChannel.MessageChannelManager;
import com.nekoyu.Universe.API.UniverseChannel;
import com.nekoyu.Universe.LawsLoader.Law;

import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class AliyunWebhook extends Law {
    Gson gson = new GsonBuilder()
        .disableHtmlEscaping()
        .setPrettyPrinting()   // 可选，让 JSON 更好看
        .create();
    Multimap<String, String> forwarding = ArrayListMultimap.create();

    @Override
    public boolean prepare() {
        getConfigDir();
        File file = new File("./config/AliyunWebhook/config.json");
        try {
            Settings settings = gson.fromJson(new FileReader(file), Settings.class);
            for (String forward : settings.forwarding) {
                String[] split = forward.split("->");
                forwarding.put(split[0].trim(), split[1].trim());
            }
        } catch (FileNotFoundException e) {
            Settings settings = new Settings();
            settings.forwarding = new ArrayList<>();
            settings.forwarding.add("SubPath -> SessionId");
            try (FileWriter fw = new FileWriter(file)) {
                fw.write(gson.toJson(settings));
                logger.info("已新建配置文件");
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        } catch (NullPointerException e) {
            logger.error("配置文件有问题，请检查配置文件后重新运行");
            return false;
        }
        return true;
    }

    @Override
    public void run() {
        UniverseChannel.addHttpHandler("/AliyunWebhook/", exchange -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                String postData = URLDecoder.decode(response.toString(), StandardCharsets.UTF_8);
                logger.debug("POST 数据: {}", postData);

                // 解析post过来的数据
                Map<String, String> params = new HashMap<>();
                for (String param : postData.split("&")) {
                    String[] keyValue = param.split("=");
                    params.put(keyValue[0], keyValue[1]);
                }

                // 可以在这里处理接收到的数据，并准备响应
                StringBuilder logMsg = new StringBuilder().append("已将").append(params.get("instanceName")).append("@").append(params.get("groupId")).append("的").append("alertName").append(params.get("triggerLevel")).append("警报发送到:");
                String subpath = exchange.getRequestURI().toString().split("AliyunWebhook/", 2)[1];
                StringBuilder message = new StringBuilder();
                message.append("云服务安全警报");
                message.append("\n").append(params.get("instanceName")).append("@").append(params.get("groupId"));
                message.append("\n").append(params.get("alertName")).append(": ").append(params.get("triggerLevel"));
                for (String sessionId : forwarding.get(subpath)) {
                    if (sessionId == null || sessionId.isEmpty()) continue;
                    MessageChannelManager.sendMessage(sessionId, message.toString());
                    logMsg.append(" ").append(sessionId).append(";");
                }
                logger.info(logMsg.toString());
                logger.debug("Path: {}", subpath);
                String res = "OK";
                exchange.sendResponseHeaders(200, res.getBytes().length);
                exchange.getResponseBody().write(res.getBytes());
            }
        });
    }

    @Override
    public void stop() {
        UniverseChannel.removeHttpHandler("/AliyunWebhook/");
    }
}

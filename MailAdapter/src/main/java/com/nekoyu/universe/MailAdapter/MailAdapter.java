package com.nekoyu.universe.MailAdapter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.nekoyu.Universe.LawsLoader.Law;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 邮箱适配器
 * SessionId 格式：mail_[my_mail_address]:[sender_mail_address]
 * 其中 my_mail_address 中所有特殊符号替换为 _ ，sender不受影响
 * 例如：mail_moyu_universe_nekoyu_com:sora@nekoyu.com
 * channel id 可 在配置文件中 覆写，否则以上述默认规则为准
 */
public class MailAdapter extends Law {
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private Config[] config;
    private final java.util.List<MailChannel> channels = new java.util.ArrayList<>();

    @Override
    public boolean prepare() {
        var configFile = getConfigDir().toPath().resolve("config.json");
        config = null;
        try {
            if (Files.notExists(configFile)) {
                try (var writer = Files.newBufferedWriter(configFile, StandardCharsets.UTF_8)) {
                    gson.toJson(new Config[]{new Config()}, writer);
                }
                logger.info("已新建 MailAdapter 配置文件：{}", configFile);
            }
            try (var reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
                config = gson.fromJson(reader, Config[].class);
            }
            if (config == null) {
                logger.error("MailAdapter 配置文件为空，请检查：{}", configFile);
                return false;
            }
        } catch (IOException | JsonParseException e) {
            logger.error("无法读取 MailAdapter 配置文件：{}", configFile, e);
            return false;
        }
        return true;
    }

    @Override
    public void run() {
        // 根据配置文件中定义的邮箱创建各自的 Channel
        if (!channels.isEmpty() || config == null) return;
        try {
            for (Config mailbox : config) {
                if (mailbox == null || !mailbox.enabled) continue;
                String id = mailbox.id == null || mailbox.id.isBlank()
                        ? "mail_" + mailbox.username.replaceAll("[^a-zA-Z0-9]", "_") : mailbox.id;
                MailChannel channel = new MailChannel(id, mailbox, getDataDir().toPath().resolve(id + ".properties"));
                channel.load();
                channels.add(channel);
            }
        } catch (RuntimeException e) {
            stop();
            throw e;
        }
    }

    @Override
    public void stop() {
        channels.forEach(MailChannel::stop);
        channels.clear();
    }
}

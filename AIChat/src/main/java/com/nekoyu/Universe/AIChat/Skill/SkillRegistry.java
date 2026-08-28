package com.nekoyu.Universe.AIChat.Skill;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SkillRegistry {
    private static final Logger logger = LoggerFactory.getLogger(SkillRegistry.class);
    private static final Map<String, Skill> skills = new ConcurrentHashMap<>();
    private static final Gson gson = new Gson();

    public static void register(Skill skill) {
        if (skill == null || skill.id == null) {
            logger.warn("尝试注册无效的 Skill");
            return;
        }
        skills.put(skill.id, skill);
        logger.info("注册 Skill: {} ({})", skill.name, skill.id);
    }

    public static Skill get(String id) {
        return skills.get(id);
    }

    public static Map<String, Skill> getAll() {
        return skills;
    }

    public static void loadFromDir(String dirPath) {
        File dir = new File(dirPath);
        if (!dir.isDirectory()) {
            logger.info("Skills 目录不存在，创建目录: {}", dirPath);
            dir.mkdirs();
            return;
        }

        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null || files.length == 0) {
            logger.info("Skills 目录为空: {}", dirPath);
            return;
        }

        int loaded = 0;
        for (File file : files) {
            try (FileReader reader = new FileReader(file)) {
                Skill skill = gson.fromJson(reader, Skill.class);
                if (skill != null && skill.id != null) {
                    register(skill);
                    loaded++;
                }
            } catch (Exception e) {
                logger.error("加载 Skill 配置失败: {}", file.getName(), e);
            }
        }
        logger.info("从 {} 加载了 {} 个 Skills", dirPath, loaded);
    }

    public static void clear() {
        skills.clear();
    }
}

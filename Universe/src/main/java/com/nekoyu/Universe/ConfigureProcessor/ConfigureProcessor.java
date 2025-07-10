package com.nekoyu.Universe.ConfigureProcessor;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ConfigureProcessor {
    File configureFile = null;
    Map configure;
    List<Checker> requireNodes = new ArrayList<>();
    int type = 0;
    private boolean allowAutoCreate = false;

    public boolean isAllowAutoCreate() {
        return allowAutoCreate;
    }

    public void setAllowAutoCreate(boolean allowAutoCreate) {
        this.allowAutoCreate = allowAutoCreate;
    }

    public ConfigureProcessor(String uri){
        setURI(uri);
    }

    public void setURI(String uri) {
        this.configureFile = new File(uri);
    }

    public ConfigureProcessor(File file) {
        this.configureFile = file;
    }

    public void read() throws CFGFileSyntaxException, IOException {
        if (configureFile.isFile()) { // 将军说配置文件必须是文件
            String content;
            FileReader fileReader = null;
            try {
                fileReader = new FileReader(configureFile);
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            }
            try (BufferedReader br = new BufferedReader(fileReader)) {
                content = br.lines().collect(Collectors.joining("\n"));
            }

            // 先尝试用 Gson 读取
            try {
                configure = new Gson().fromJson(content, Map.class);
                if (configure == null) throw new JsonSyntaxException("");
                type = 2;
                return;
            } catch (JsonSyntaxException ignored) {

            }
            // 再尝试作为 Yaml 读取
            try {
                configure = new Yaml().loadAs(content, Map.class);
                if (configure == null) throw new YAMLException("");
                type = 1;
                return;
            } catch (YAMLException ignored) {

            }

            throw new CFGFileSyntaxException("配置文件格式不正确");
        } else { // 将军问配置文件不是文件怎么办
            if (!configureFile.exists()) {
                if (configureFile.createNewFile()) {
                    configure = new HashMap<>();
                }
            }
        }
    }

    public Object getNode(String node){
        String[] keys = node.split("\\.");
        Map<String, Object> currentMap = configure;
        Object value = null;

        for (String key : keys) {
            value = currentMap.get(key);

            // 如果值是 Map，继续深入
            if (value instanceof Map) {
                currentMap = (Map<String, Object>) value;
            } else {
                // 如果值不是 Map，返回该值（可能是叶子节点）
                return value;
            }
        }

        // 如果循环完成后，value 仍是 Map，意味着这是一个中间节点，返回它
        return value;
    }

    public void setNode(String node, Object value) {
        String[] keys = node.split("\\.");
        Map<String, Object> currentMap = configure;

        for (int i = 0; i < keys.length - 1; i++) {
            String key = keys[i];

            // 如果不存在当前 key，创建一个新的子 Map
            if (!currentMap.containsKey(key) || !(currentMap.get(key) instanceof Map)) {
                currentMap.put(key, new HashMap<String, Object>());
            }

            currentMap = (Map<String, Object>) currentMap.get(key);
        }

        // 设置最后一个键的值
        currentMap.put(keys[keys.length - 1], value);
    }

    public void requireNode(String node, Pattern pattern) {
        requireNodes.add(new Checker(node, pattern));
    }

    public void requireNode(String node, Pattern pattern, String defaultValue) {
        requireNodes.add(new Checker(node, pattern, defaultValue));
    }

    /** 返回错误的数量 */
    public int checkFor() {
        boolean isChanged = false;
        int errors = 0;
        for (Checker checker : requireNodes) {
            Object value = getNode(checker.node);
            Pattern pattern = checker.pattern;
            if (value == null) {
                if (checker.defaultValue != null) {
                    if (allowAutoCreate) {
                        setNode(checker.node, checker.defaultValue);
                        isChanged = true;
                    }
                } else {
                    if (allowAutoCreate) {
                        setNode(checker.node, "");
                        isChanged = true;
                        errors++;
                    }
                    errors++;
                }
            } else {
                if (!pattern.matcher(value.toString()).matches()) {
                    errors++;
                }
            }
        }
        if (isChanged) write();
        return errors;
    }

    private void write() {
        System.out.println(type);
        try {
            try (FileOutputStream fos = new FileOutputStream(configureFile)) {
                String content;
                switch (type) {
                    case 2:
                        content = new Gson().toJson(configure);
                        break;
                    case 1:
                    default:
                        content = new Yaml().dump(configure);
                }
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private class Checker {
        String node;
        Pattern pattern;
        String defaultValue = null;

        public Checker(String node, Pattern pattern) {
            this.node = node;
            this.pattern = pattern;
        }

        public Checker(String node, Pattern pattern, String defaultValue) {
            this.node = node;
            this.pattern = pattern;
            this.defaultValue = defaultValue;
        }
    }
}

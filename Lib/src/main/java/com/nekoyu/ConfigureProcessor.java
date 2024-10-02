package com.nekoyu;

import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.util.Map;

public class ConfigureProcessor {
    File ConfigureFile = null;
    Map Configure;

    public ConfigureProcessor(File file){
        this.ConfigureFile = file;
    }

    public boolean readAsYaml(){
        try {
            Configure = new Yaml().loadAs(new FileReader(ConfigureFile), Map.class);
            return true;
        } catch (FileNotFoundException e) {
            System.out.println("未找到配置文件");
            return false;
        }
    }

    public Object getNode(String node){
        String[] keys = node.split("\\.");
        Map<String, Object> currentMap = Configure;
        Object value = null;

        for (int i = 0; i < keys.length; i++) {
            value = currentMap.get(keys[i]);

            if (i == keys.length - 1) {
                return value; // 到达路径末尾，返回值
            }

            // 如果值是另一个 Map，继续深入
            if (value instanceof Map) {
                currentMap = (Map<String, Object>) value;
            } else {
                return null; // 路径无效
            }
        }

        return null;
    }
}

package com.nekoyu;

import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.util.HashMap;
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
        String[] args = node.split("\\.");
        if (args.length == 0){
            return null;
        } else if (args.length == 1) {
            return Configure.get(args[0]);
        } else {
            Object object = Configure;
            for(int i = 0; i < args.length - 1; i++){
                if (object == null){
                    return null;
                } else if (object instanceof Map){
                    object = ((Map<?, ?>) object).get(i);
                } else {
                    return null;
                }
            }
            return object;
        }
    }
}

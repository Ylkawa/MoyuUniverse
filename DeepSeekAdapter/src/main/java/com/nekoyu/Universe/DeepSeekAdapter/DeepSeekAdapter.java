package com.nekoyu.Universe.DeepSeekAdapter;

import com.nekoyu.Universe.ConfigureProcessor.CFGFileSyntaxException;
import com.nekoyu.Universe.ConfigureProcessor.ConfigureProcessor;
import com.nekoyu.Universe.LawsLoader.Law;
import com.nekoyu.Universe.Universe;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.File;
import java.io.IOException;
import java.util.Objects;
import java.util.regex.Pattern;

public class DeepSeekAdapter extends Law {

    @Override
    public boolean prepare() {
        File configDic = new File("./config/DeepSeekAdapter/");
        if (!configDic.exists()) configDic.mkdir();
        for (File file : Objects.requireNonNull(configDic.listFiles())) {
            if (file.getName().endsWith(".yml")) {
                ConfigureProcessor cp = new ConfigureProcessor(file, true);
                cp.requireNode("id", "\\w+");
                cp.requireNode("base_url", "(https?)://[-A-Za-z0-9+&@#/%?=~_|!:,.;]+[-A-Za-z0-9+&@#/%=~_|]", "https://api.deepseek.com");
                cp.requireNode("api_key", "[-\\w]+");
                try {
                    cp.read();
                } catch (CFGFileSyntaxException e) {
                    return false;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                int checkFor = cp.checkFor();
                if (checkFor == 0) {
                    DeepSeekChannel dsc = new DeepSeekChannel(cp.getNode("id").toString(), cp.getNode("base_url").toString(), cp.getNode("api_key").toString());
                    Universe.Providers.put(dsc.id, dsc);
                    logger.info("已载入 DeepSeek 适配器 {}", dsc.id);
                } else {
                    logger.warn("配置文件 {} 仍存在 {} 个错误，将不会被加载", file.getName(), checkFor);
                }
            }
        }
        return true;
//        cp.requireNode("base_url", Pattern.compile("(https?)://[-A-Za-z0-9+&@#/%?=~_|!:,.;]+[-A-Za-z0-9+&@#/%=~_|]"), "https://api.deepseek.com");
//        cp.requireNode("api_key", Pattern.compile("\\w+"));
//        int checkFor = cp.checkFor();
//        if (checkFor == 0) {
//            return true;
//        } else {
//            return false;
//        }
    }

    @Override
    public void run() {

    }

    @Override
    public void stop() {

    }
}
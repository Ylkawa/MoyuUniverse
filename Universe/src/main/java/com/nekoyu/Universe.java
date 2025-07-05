package com.nekoyu;

import com.nekoyu.API.MessageChannel;
import com.nekoyu.LawsLoader.LawsManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class Universe {
    static public Logger logger = LoggerFactory.getLogger(Universe.class);
    static public ConfigureProcessor PublicConfig = new ConfigureProcessor("YAML://config.yml", logger);
    static public LawsManager LawsManager;
    static public Map<String, MessageChannel> MessageChannels = new HashMap<>();

    public static void main(String[] args) {

        File[] necessaryDictionaries = {new File("./config/"), new File("./laws/")};
        for (File necessaryDictionary : necessaryDictionaries) {
            if (!necessaryDictionary.exists()) {
                necessaryDictionary.mkdir();
            } else if (necessaryDictionary.isFile()) {
                logger.error("请检查必须的文件夹的路径是否被占用");
                System.exit(1);
            }
        }

        File lawsDir = new File("laws/");
        if (lawsDir.exists()) {
            if (!lawsDir.isDirectory()) {
                logger.warn("无法加载宇宙法则，因为laws路径被文件占用");
            }
        } else {
            lawsDir.mkdir();
        }

        // 加载配置文件
        PublicConfig.read();

        LawsManager = new LawsManager(lawsDir);

        // 加载宇宙法则
        LawsManager.loadLaws();
        LawsManager.enableLaws();


        //程序退出动作
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Exiting");
            LawsManager.disableLaws();
        }));
    }
}
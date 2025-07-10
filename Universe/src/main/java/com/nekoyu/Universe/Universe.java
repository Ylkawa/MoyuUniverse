package com.nekoyu.Universe;

import com.nekoyu.Universe.API.MessageChannel;
import com.nekoyu.Universe.API.UniverseChannel;
import com.nekoyu.Universe.ConfigureProcessor.CFGFileSyntaxException;
import com.nekoyu.Universe.ConfigureProcessor.ConfigureProcessor;
import com.nekoyu.Universe.LawsLoader.LawsManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public class Universe {
    static public Logger logger = LoggerFactory.getLogger(Universe.class);
    static public ConfigureProcessor PublicConfig = new ConfigureProcessor("config.yml");
    static public LawsManager LawsManager;
    static public Map<String, MessageChannel> MessageChannels = new HashMap<>();
    static public UniverseChannel UniverseChannel = new UniverseChannel(2576);

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
        PublicConfig.setAllowAutoCreate(true);
        try {
            PublicConfig.read();
        } catch (CFGFileSyntaxException e) {
            logger.error(e.getMessage());
            throw new RuntimeException(e);
        } catch (IOException e) {
            logger.error(e.getMessage());
            throw new RuntimeException(e);
        }

        // 检查 & 修复配置文件
        for (String[] check : new String[][]{
                {"UniverseChannel.Port", "^(0|[1-9]\\d{0,3}|[1-5]\\d{4}|6[0-4]\\d{3}|65[0-4]\\d{2}|655[0-2]\\d|6553[0-5])$", "2576"},
                {"UniverseChannel.Token", "^.+$", ""},
                {"UniverseChannel.Enable", "^(true|false)$", "true"}
        }) {
            if (check.length == 2) {
                PublicConfig.requireNode(check[0], Pattern.compile(check[1]));
            } else {
                PublicConfig.requireNode(check[0], Pattern.compile(check[1]), check[2]);
            }
        }
        int errorsOfConfig = PublicConfig.checkFor();
        if (errorsOfConfig != 0) {
            logger.error("配置文件还有 {} 个错误，请修复", errorsOfConfig);
            System.exit(2);
        }

        // 加载宇宙标准消息通道
        if (PublicConfig.getNode("UniverseChannel.Enable").equals("true")) {
            String port = (String) PublicConfig.getNode("UniverseChannel.port");
            if (port != null) {
                int portI = Integer.getInteger(port);
                UniverseChannel.setPort(portI);
            }
            String token = (String) PublicConfig.getNode("UniverseChannel.Token");
            if (token != null) {
                UniverseChannel.setToken(token);
            }
            UniverseChannel.load();
        }

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
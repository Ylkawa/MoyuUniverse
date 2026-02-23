package com.nekoyu.Universe;

import com.nekoyu.Universe.API.MessageChannel.MessageChannelManager;
import com.nekoyu.Universe.API.MessageChannel.MessageField.ImageField;
import com.nekoyu.Universe.API.MessageChannel.Solver;
import com.nekoyu.Universe.API.UniverseChannel;
import com.nekoyu.Universe.LawsLoader.LawsManager;
import com.nekoyu.Universe.Utils.ImageUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/** >- 末屿宇宙 -< */
public class Universe {
    static public Logger logger = LoggerFactory.getLogger(Universe.class);
    static public Properties universeChannelProp = new Properties();
    static public LawsManager LawsManager;
    static public MessageChannelManager MessageChannelManager = new MessageChannelManager();
    static public Map<String, Object> Providers = new HashMap<>();
    static public Solver pictureSolver = null;
    static public Solver voiceSolver = null;

    public static void main(String[] args) {

        File lawsDir = new File("./laws/");
        File configsDir = new File("./config/");
        File dataDir = new File("./data/");
        File cacheDir = new File("./cache/");
        File repostsDir = new File("./cache/reposts/");
        if (cacheDir.exists()) {
            cleanCache(cacheDir);
        }
        File[] necessaryDictionaries = {lawsDir, configsDir, dataDir, cacheDir, repostsDir};
        for (File necessaryDictionary : necessaryDictionaries) {
            if (!necessaryDictionary.exists()) {
                necessaryDictionary.mkdir();
            } else if (necessaryDictionary.isFile()) {
                logger.error("请检查必须的文件夹的路径是否被占用");
                System.exit(1);
            }
        }

        if (lawsDir.exists()) {
            if (!lawsDir.isDirectory()) {
                logger.warn("无法加载宇宙法则，因为laws路径被文件占用");
            }
        } else {
            lawsDir.mkdir();
        }

        // 加载主配置文件
        Properties properties = new Properties();
        try {
            properties.load(new FileReader("./Config.properties"));
        } catch (FileNotFoundException e) {
            properties.put("Debug", "false");
            try (FileOutputStream fos = new FileOutputStream("./Config.properties")) {
                properties.store(fos, "Moyu Universe Main Config");
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if (properties.get("Debug").toString().equalsIgnoreCase("debug")) {
            Configurator.setRootLevel(Level.DEBUG);
            logger.warn("Logger level has been setting to DEBUG");
        }
        if (properties.get("Debug").toString().equalsIgnoreCase("trace")) {
            Configurator.setRootLevel(Level.TRACE);
            logger.warn("Logger level has been setting to TRACE");
        }

        // 加载宇宙通道配置文件
        try {
            universeChannelProp.load(new FileReader("./UniverseChannel.properties"));
        } catch (FileNotFoundException e) { // 出这个错就新建配置文件 并以默认配置继续运行
            universeChannelProp.put("WsPort", "2576");
            universeChannelProp.put("HttpPort", "2577");
            universeChannelProp.put("Token", "token");
            universeChannelProp.put("Enable", "false");
            try (FileOutputStream fos = new FileOutputStream("./UniverseChannel.properties")) {
                universeChannelProp.store(fos, "Moyu Universe Network Channel Config");
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // 加载宇宙标准消息通道
        if (universeChannelProp.get("Enable").equals("true")) {
            Object wsPort = universeChannelProp.get("WsPort");
            if (wsPort != null) {
                UniverseChannel.setWsPort(Integer.parseInt(wsPort.toString()));
            } else {
                UniverseChannel.setWsPort(2576);
            }
            Object httpPort = universeChannelProp.get("HttpPort");
            if (httpPort != null) {
                UniverseChannel.setHttpPort(Integer.parseInt(httpPort.toString()));
            } else {
                UniverseChannel.setHttpPort(2577);
            }
            Object token = universeChannelProp.get("Token");
            if (token != null) {
                UniverseChannel.setToken(token.toString());
            }
            Object outboundHttpAddress = universeChannelProp.get("OutboundHttpAddress");
            if (outboundHttpAddress != null) {
                UniverseChannel.setOutboundHttpAddress(outboundHttpAddress.toString());
            }
            UniverseChannel.load();
        }

        // 加载宇宙法则
        LawsManager = new LawsManager();
        LawsManager.loadLaws();
        LawsManager.prepareLaws();
        LawsManager.enableLaws();
        logger.info("宇宙法则加载完毕.");
        System.gc();

        //程序退出动作
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { 
            logger.info("Exiting");
            LawsManager.stopLaws();
            if (!cacheDir.exists()) return;

            cleanCache(cacheDir);
        }));
    }

    private static void cleanCache(File cacheDir) {
        try {
            Files.walk(cacheDir.toPath())
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
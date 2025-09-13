package com.nekoyu.Universe;

import com.nekoyu.Universe.API.MessageChannel.MessageChannelManager;
import com.nekoyu.Universe.API.MessageChannel.PictureSolver;
import com.nekoyu.Universe.API.UniverseChannel;
import com.nekoyu.Universe.LawsLoader.LawsManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/** >- 末屿宇宙 -< */
public class Universe {
    static public Logger logger = LoggerFactory.getLogger(Universe.class);
    static public Properties universeChannelProp = new Properties();
    static public LawsManager LawsManager;
    static public MessageChannelManager MessageChannelManager = new MessageChannelManager();
    static public UniverseChannel UniverseChannel = null;
    static public Map<String, Object> Providers = new HashMap<>();
    static public PictureSolver pictureSolver = null;

    public static void main(String[] args) {

        File lawsDir = new File("./laws/");
        File configsDir = new File("./config/");
        File dataDir = new File("./data/");
        File[] necessaryDictionaries = {lawsDir, configsDir, dataDir};
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

        // 加载配置文件
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
            UniverseChannel = new UniverseChannel();
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
            UniverseChannel.load();
        }

        // 加载宇宙法则
        LawsManager = new LawsManager();
        LawsManager.loadLaws();
        LawsManager.prepareLaws();
        LawsManager.enableLaws();

        // 清理内存
        System.gc();

        //程序退出动作
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Exiting");
            LawsManager.stopLaws();
        }));
    }
}
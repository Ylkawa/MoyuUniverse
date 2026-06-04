package com.nekoyu.MoyuUniverse.Nya.OnebotAdapter;

import com.google.gson.Gson;
import com.nekoyu.Universe.LawsLoader.Law;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;


/**
 * OnebotAdapter 的作用应该是，解析配置文件，然后按各 Channel 的配置 new channel，再运行 channel ，注册到 宇宙消息通道
 */
public class OnebotAdapter extends Law {
    Gson gson = new Gson();
    boolean isReady = true;
    Logger logger = LoggerFactory.getLogger(this.getClass());
    List<OnebotChannel> ocs = new ArrayList<>();

    @Override
    public boolean prepare() {
        logger.info("正在准备 Onebot 组件");
        // 确保 config/Onebot 文件夹存在
        File configDir = new File("./config/Onebot/");
        if (!configDir.exists()) {
            logger.info(String.valueOf(configDir.mkdirs()));
        }
        // 按照目录下的 配置文件 依次加载若干个 OnebotChannel
        File configFileLoc = new File("./config/Onebot/Onebot.json.template");
        if (!configFileLoc.exists()) { // 这个配置文件不存在，创建一个
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("config-template/Onebot.json")) {
                if (in == null) {
                    logger.error("无法在JAR包中找到默认配置文件 config-template/Onebot.json");
                    stop();
                    isReady = false;
                    return false;
                }
                // 复制文件
                Files.copy(in, configFileLoc.toPath(), StandardCopyOption.REPLACE_EXISTING);
                logger.info("模板配置文件已生成: ./config/Onebot/Onebot.json.template");
            } catch (IOException e) {
                logger.error("生成模板配置文件时出错", e);
                stop();
                isReady = false;
            }
        } else if (configFileLoc.isDirectory()) { // 这里就不是我们该管的了，抽象
            stop();
            isReady = false;
            return false;
        }
        // 对配置文件模板的存在状况检查完成
        // 开始按照各个配置文件依次加载MessageChannel
        File cfgDic = new File("./config/Onebot/");
        if (cfgDic.isDirectory()) {
            File[] cfgFiles = cfgDic.listFiles();
            if (cfgFiles != null) {
                for (File file : cfgFiles) {
                    // 筛选出 后缀名为 yml 的文件
                    if (file.getName().endsWith(".json")) {
                        try {
                            Config config = gson.fromJson(new FileReader(file), Config.class);
                            OnebotChannel oc = new OnebotChannel(config.ID);
                            oc.token = config.Token;
                            oc.uri = new URI(config.URI);
                            oc.enableQZone = config.EnableQZone;
                            oc.blockedUsers = new HashSet<>(config.BlockedUsers);
                            oc.degradedUsers = new HashSet<>(config.DegradedUsers);
                            if (config.RemoteWebDriver != null && config.RemoteWebDriver.url != null) {
                                try {
                                    oc.remoteWebDriverURL = new URL(config.RemoteWebDriver.url);
                                } catch (MalformedURLException e) {
                                    logger.warn("Onebot Channel {} 配置的远程SeleniumWebDriver URL有误，QZone能力不启用", config.ID);
                                    oc.enableQZone = false;
                                }
                            }
                            ocs.add(oc);
                            logger.info("已载入 Onebot 配置 {}", oc.ID);
                        } catch (FileNotFoundException e) {
                            throw new RuntimeException(e);
                        } catch (URISyntaxException e) {
                            logger.warn("URI无效, {} 将不会被加载", file.getName());
                        }
                    }
                }
            }
        } else {
            if (!cfgDic.mkdir()) logger.error("默认配置文件夹加载失败");
            return false;
        }
        return true;
    }

    @Override
    public void run() {
        if (isReady) for (OnebotChannel oc : ocs) {
            logger.info("正在加载{}", oc.ID);
            oc.load();
        }
    }

    @Override
    public void stop() {
        for (OnebotChannel oc : ocs) {
            oc.stop();
        }
    }
}

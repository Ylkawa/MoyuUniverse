package com.nekoyu.MoyuUniverse.Nya.OnebotAdapter;

import com.nekoyu.ConfigureProcessor;
import com.nekoyu.LawsLoader.Law;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;


/**
 * OnebotAdapter 的作用应该是，解析配置文件，然后按各 Channel 的配置 new channel，再运行 channel ，注册到 宇宙消息通道
 */
public class OnebotAdapter extends Law {
    boolean isReady = true;
    Logger logger = LoggerFactory.getLogger(this.getClass());
    List<OnebotChannel> ocs = new ArrayList<>();

    @Override
    public void prepare() {
        logger.info("正在准备 Onebot 组件");
        // 确保 config/Onebot 文件夹存在
        File configDir = new File("./config/Onebot/");
        if (!configDir.exists()) {
            logger.info(String.valueOf(configDir.mkdirs()));
        }
        // 按照目录下的 配置文件 依次加载若干个 OnebotChannel
        File configFileLoc = new File("./config/Onebot/Onebot.yml.template");
        if (!configFileLoc.exists()) { // 这个配置文件不存在，创建一个
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("config-template/Onebot.yml")) {
                if (in == null) {
                    logger.error("无法在JAR包中找到默认配置文件 config-template/Onebot.yml");
                    stop();
                    isReady = false;
                    return;
                }
                // 复制文件
                Files.copy(in, configFileLoc.toPath(), StandardCopyOption.REPLACE_EXISTING);
                logger.info("模板配置文件已生成: ./config/Onebot/Onebot.yml.template");
            } catch (IOException e) {
                logger.error("生成模板配置文件时出错", e);
                stop();
                isReady = false;
            }
        } else if (configFileLoc.isDirectory()) { // 这里就不是我们该管的了，抽象
            stop();
            isReady = false;
            return;
        }
        // 对配置文件模板的存在状况检查完成
        // 开始按照各个配置文件依次加载MessageChannel
        File cfgDic = new File("./config/Onebot/");
        if (cfgDic.isDirectory()) for (File file : cfgDic.listFiles()) {
            // 筛选出 后缀名为 yml 的文件
            if (file.getName().endsWith(".yml")) {
                ConfigureProcessor cp = new ConfigureProcessor(file, logger);
                OnebotChannel oc = new OnebotChannel();
                oc.token = cp.getNode("Token").toString();
                oc.ID = cp.getNode("ID").toString();
                try {
                    URI uri = new URI(cp.getNode("URI").toString());
                    oc.uri = uri;
                } catch (URISyntaxException e) {
                    logger.error(e.getMessage());
                    break;
                }
                ocs.add(oc);
                logger.info("已载入 Onebot 配置 {}", oc.ID);
            }
        }
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

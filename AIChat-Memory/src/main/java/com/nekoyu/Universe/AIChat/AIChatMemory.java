package com.nekoyu.Universe.AIChat;

import com.nekoyu.Universe.AIChat.Event.RequestEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public class AIChatMemory extends AIChatPlugin {
    Logger logger = LoggerFactory.getLogger(getClass());
    Yaml yaml = new Yaml();
    Properties config;
    boolean ready = true;

    public AIChatMemory(AIChat aiChat) {
        super(aiChat);
    }

    @Override
    public void onEnable() {
        getConfigDir();
        try {
            config = yaml.loadAs(new FileReader("./config/AIChat/Plugins/Memory/config.yml"), Properties.class);
        } catch (FileNotFoundException e) {
            ready = false;
            config = new Properties();
            config.put("URL", "jdbc:mysql://host:3306/database?useSSL=true&serverTimezone=UTC");
            config.put("Username", "username");
            config.put("Password", "password");
            File cfgFile = new File("./config/AIChat/Plugins/Memory/config.yml");
            try {
                if (cfgFile.createNewFile()) logger.info("配置文件已生成，请修改配置文件再重新启动");
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
            try (var bw = new BufferedWriter(new FileWriter(cfgFile))) {
                bw.write(yaml.dump(config));
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
            ready = false;
            return;
        }
        try {
            DriverManager.registerDriver(new com.mysql.cj.jdbc.Driver());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        if (ready) try (var sqlConn = DriverManager.getConnection(config.getProperty("URL"), config.getProperty("Username"), config.getProperty("Password"))) {
            try (var stmt = sqlConn.createStatement()) {
                stmt.executeUpdate("CREATE Table IF NOT EXIST");
            }
            logger.info("数据库可用，组件已激活");
        } catch (SQLException e) {
            ready = false;
            logger.error("无法创建 SQL 连接", e);
        }
    }

    @Override
    public void onDisable() {

    }

    @Override
    public void onRequest(RequestEvent event) {
        logger.info("处理请求");
    }
}

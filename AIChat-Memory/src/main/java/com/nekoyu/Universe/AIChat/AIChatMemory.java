package com.nekoyu.Universe.AIChat;

import com.nekoyu.Universe.AIChat.Event.RequestEvent;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.LLMFunction;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

public class AIChatMemory extends AIChatPlugin {
    Logger logger = LoggerFactory.getLogger(getClass());
    Yaml yaml = new Yaml();
    Properties config;
    boolean ready = true;
    HikariDataSource ds;

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
            logger.error("加载 MySQL 驱动失败");
            throw new RuntimeException(e);
        }
        HikariConfig hkrCfg = new HikariConfig();
        hkrCfg.setJdbcUrl(config.getProperty("URL"));
        hkrCfg.setUsername(config.getProperty("Username"));
        hkrCfg.setPassword(config.getProperty("Password"));
        hkrCfg.setMinimumIdle(2);
        hkrCfg.setMaximumPoolSize(4);
        hkrCfg.setConnectionTimeout(20000);
        ds = new HikariDataSource(hkrCfg);

        if (ready) try (var sqlConn = ds.getConnection()) {
            try (var stmt = sqlConn.createStatement()) {
                stmt.executeUpdate("CREATE TABLE IF NOT EXISTS memories (" +
                        "id INT AUTO_INCREMENT PRIMARY KEY NOT NULL," +
                        "loc VARCHAR(64) NOT NULL," +
                        "usr_loc VARCHAR(64)," +
                        "content TEXT NOT NULL," +
                        "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                        "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP" +
                        ")");
            }
            logger.info("数据库可用，组件已激活");
        } catch (SQLException e) {
            ready = false;
            logger.error("无法创建 SQL 连接", e);
        }


        registerFunction("Memory", LLMFunction.Builder()
                .name("AddMemory")
                .description("""
                        提交的信息将会被持久化记录，并存入 System Prompt
                        调用时应保持正文输出，此 tool 调用后不会发起循环调用""")
                .parameters("object", new HashMap<>() {{
                            put("user", LLMFunction.Parameters.Property.Builder()
                                    .type("string")
                                    .description("如果此记忆针对某一个用户，请填写该用户的 LocationId")
                                    .build()
                            );
                            put("content", LLMFunction.Parameters.Property.Builder()
                                    .type("string")
                                    .description("记忆的正文，请用第三人称客观描述")
                                    .build()
                            );
                        }},
                        new String[]{"content"}
                )
                .callback(args -> {
                    // TODO: complete code here
                    try (var conn = ds.getConnection()) {
                        PreparedStatement ps = conn.prepareStatement("INSERT INTO " +
                                "memories(loc, usr_loc, content)" +
                                "VALUES (?, ?, ?)");
                        ps.setString(1, args.get("_LocationID"));
                        ps.setString(2, args.get("user"));
                        ps.setString(3, args.get("content"));
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                    return null;
                })
                .build()
        );

        registerFunction("Memory", LLMFunction.Builder()
                .name("ChangeMemory")
                .description("""
                        根据记忆 ID 修改某一条记忆的内容
                        调用时应保持正文输出，此 tool 调用后不会发起循环调用""")
                .parameters("object", new HashMap<>() {{
                    put("id", LLMFunction.Parameters.Property.Builder()
                            .type("integer")
                            .description("记忆条目的 ID")
                            .build()
                    );
                    put("content", LLMFunction.Parameters.Property.Builder()
                            .type("string")
                            .build()
                    );
                }}, new String[]{"id", "content"})
                .callback(args -> {
                    try (var conn = ds.getConnection()) {
                        PreparedStatement ps = conn.prepareStatement("UPDATE memories SET content=? WHERE id=?");
                        ps.setString(1, args.get("content"));
                        ps.setString(2, args.get("id"));
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                    return null;
                })
                .build()
        );

        registerFunction("Memory", LLMFunction.Builder()
                .name("DeleteMemory")
                .description("""
                        根据记忆 ID 删除某一条记忆
                        调用时应保持正文输出，此 tool 调用后不会发起循环调用""")
                .parameters("object", new HashMap<>() {{
                    put("id", LLMFunction.Parameters.Property.Builder()
                            .type("integer")
                            .build()
                    );
                }}, new String[]{"id"})
                .callback(args -> {
                    try (var conn = ds.getConnection()) {
                        PreparedStatement ps = conn.prepareStatement("DELETE FROM memories WHERE id=?");
                        ps.setInt(1, Integer.getInteger(args.get("id")));
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                    return null;
                })
                .build()
        );
    }

    @Override
    public void onDisable() {

    }

    @Override
    public void onRequest(RequestEvent event) {
        Set<String> locations = new HashSet<>(); // 所有生效的记忆作用域都在这了
        if (!event.messageList.isEmpty()) {
            event.messageList.get(0).getLocationId();
            event.messageList.forEach(message -> locations.add(message.sender.getLocationId()));
        }

        String placeholders = String.join(",", Collections.nCopies(locations.size(), "?"));
        try (var conn = ds.getConnection()) {
            PreparedStatement ps = conn.prepareStatement("SELECT * FROM memories WHERE usr_loc IN (" + placeholders + ") OR loc LIKE ?");
            int i = 1;
            for (String loc : locations) {
                ps.setString(i, loc);
                i++;
            }
            ps.setString(i, event.locationId);
            ResultSet rs = ps.executeQuery();
            StringBuilder sb = new StringBuilder();
            sb.append("记忆: ");
            while (rs.next()) {
                sb.append("\n");
                sb.append(rs.getInt("id"));
                sb.append(": ");
                sb.append(rs.getString("content"));
            }
            event.placeholders.put("Memory", sb.toString());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        logger.info("处理请求");
    }
}

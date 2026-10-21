package com.nekoyu.universe.MailAdapter;

/** 单个邮箱账号的连接与收发配置；config.json 顶层为此配置的数组。密码可填写邮箱服务商提供的客户端授权码。 */
public class Config {
    /** 留空时根据邮箱地址生成 mail_ 前缀的通道 ID。 */
    public String id = "";
    /** 填好连接信息后再启用。 */
    public boolean enabled = false;

    /** 登录账号，通常为完整邮箱地址。 */
    public String username = "";
    public String password = "";

    public Imap imap = new Imap();
    public Smtp smtp = new Smtp();

    public static class Imap {
        public boolean enabled = true;
        public String host = "";
        /** 隐式 TLS 通常使用 993；STARTTLS 通常使用 143，需同步调整端口。 */
        public int port = 993;
        /** 建立连接时立即使用 TLS；与 startTls 互斥。 */
        public boolean ssl = true;
        /** 启用时必须成功升级为 TLS，不允许回退到明文。 */
        public boolean startTls = false;
        public String folder = "INBOX";
        /** 收信检查间隔，单位为秒，必须大于 0。 */
        public int pollIntervalSeconds = 60;
        /** 首次收取最近多少天的邮件；0 表示全部历史，后续按进度增量收取。 */
        public int initialLookbackDays = 0;
        /** 成功处理后是否将邮件标记为已读。 */
        public boolean markAsRead = false;
        public int connectionTimeoutMillis = 10000;
        public int readTimeoutMillis = 30000;
        public int writeTimeoutMillis = 30000;
    }

    public static class Smtp {
        /** 默认关闭发信，按需启用。 */
        public boolean enabled = false;
        public String host = "";
        /** STARTTLS 通常使用 587；隐式 TLS 通常使用 465，需同步调整端口。 */
        public int port = 587;
        public boolean auth = true;
        /** 留空时复用顶层账号和密码；用于收发服务器凭据不同的情况。 */
        public String username = "";
        public String password = "";
        /** 建立连接时立即使用 TLS；与 startTls 互斥。 */
        public boolean ssl = false;
        /** 启用时必须成功升级为 TLS，不允许回退到明文。 */
        public boolean startTls = true;
        /** 发件人邮箱地址；留空时使用顶层 username。 */
        public String from = "";
        public String fromName = "";
        /** 回复地址；留空时不额外设置 Reply-To。 */
        public String replyTo = "";
        public int connectionTimeoutMillis = 10000;
        public int readTimeoutMillis = 30000;
        public int writeTimeoutMillis = 30000;
    }
}

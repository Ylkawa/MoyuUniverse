package com.nekoyu.Universe.AIChat;

/**
 * 此类用于收取各平台商务推送的电子邮件，例如特定AI平台的最新模型等的推送，每天定时收取并交给 Librarian 处理
 */
public class MailReceiver {
    private final AIChat aichat;
    private final Config.Mail config;

    public MailReceiver(AIChat aichat) {
        this.aichat = aichat;
        this.config = aichat.globalCfg.Mail;
    }

    /**
     *
     * @param second 每天第多少秒发起调用
     */
    public void scheduledTask(int[] second) {

    }
}

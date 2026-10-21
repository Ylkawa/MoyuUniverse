package com.nekoyu.Universe.AIChat.Web;

/** 保留 Web 插件原入口；实现由公共模块提供，供网页与邮件共同使用。 */
public final class HtmlMarkdown {
    private HtmlMarkdown() {}

    public static String convert(String html, String pageUrl) {
        return com.nekoyu.Universe.Utils.HtmlMarkdown.convert(html, pageUrl);
    }
}

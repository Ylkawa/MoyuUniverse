package com.nekoyu.Universe.Utils;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 网页和邮件正文的基础 Markdown 表示；仅解析本地 HTML，不加载外部资源。 */
public final class HtmlMarkdown {
    private static final Pattern BACKTICKS = Pattern.compile("`+");
    private static final Pattern HIDDEN_STYLE = Pattern.compile(
            "(?i)(?:^|;)\\s*(?:display\\s*:\\s*none|visibility\\s*:\\s*hidden)\\s*(?:!important\\s*)?(?:;|$)");
    private static final Pattern PIXEL_SIZE = Pattern.compile("(?i)\\s*[01](?:\\.0+)?(?:px)?\\s*");

    private HtmlMarkdown() {}

    public static String convert(String html, String pageUrl) {
        Element body = Jsoup.parse(html, pageUrl).body();
        body.select("script, style, noscript, template, [hidden], [aria-hidden=true]").remove();
        return children(body).strip();
    }

    /** 邮件没有可靠的网页基址；保留正文和绝对链接，不推测相对链接的来源。 */
    public static String convertEmail(String html) {
        Element body = Jsoup.parse(html).body();
        body.select("script, style, noscript, template, [hidden], [aria-hidden=true]").remove();
        for (Element element : body.select("[style]")) {
            String style = element.attr("style");
            if (HIDDEN_STYLE.matcher(style).find()) element.remove();
        }
        for (Element image : body.select("img")) {
            // Do not discard unlabelled charts/photos just because alt is absent.
            if (PIXEL_SIZE.matcher(image.attr("width")).matches()
                    || PIXEL_SIZE.matcher(image.attr("height")).matches()) image.remove();
        }
        // Only explicitly marked presentation tables are layout; unmarked/data tables retain cell structure.
        for (Element table : body.select("table[role=presentation]")) {
            for (Element row : table.select("tr")) {
                if (row.closest("table") != table) continue;
                for (Element cell : row.children())
                    if (cell.normalName().equals("td") || cell.normalName().equals("th")) cell.tagName("div");
                row.tagName("div");
            }
            table.tagName("div");
        }
        return children(body).strip();
    }

    private static String children(Node node) {
        StringBuilder result = new StringBuilder();
        for (Node child : node.childNodes()) append(result, render(child));
        return result.toString();
    }

    // 合并相邻块的边界空行，不处理块内部，保留代码块中的空白。
    private static void append(StringBuilder result, String fragment) {
        if (fragment.isEmpty()) return;
        if (fragment.startsWith("\n")) {
            while (!result.isEmpty() && Character.isWhitespace(result.charAt(result.length() - 1)))
                result.setLength(result.length() - 1);
            if (!result.isEmpty()) result.append("\n\n");
            result.append(fragment.stripLeading());
        } else if (!(fragment.isBlank() && (result.isEmpty() || result.charAt(result.length() - 1) == '\n'))) {
            result.append(fragment);
        }
    }

    private static String block(String text) {
        return text.isBlank() ? "" : "\n\n" + text.strip() + "\n\n";
    }

    private static String render(Node node) {
        if (node instanceof TextNode text)
            return escape(text.getWholeText().replaceAll("[\\s\\u00a0]+", " "));
        if (!(node instanceof Element element)) return "";
        String tag = element.normalName();
        return switch (tag) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                String text = children(element).strip();
                yield text.isEmpty() ? "" : block("#".repeat(tag.charAt(1) - '0') + " " + text);
            }
            case "p", "div", "section", "article", "main", "header", "footer", "nav",
                    "aside", "figure", "figcaption", "address", "dl", "dt", "dd" -> block(children(element));
            case "br" -> "  \n";
            case "hr" -> block("---");
            case "strong", "b" -> emphasis(element, "**");
            case "em", "i" -> emphasis(element, "*");
            case "del", "s", "strike" -> emphasis(element, "~~");
            case "pre" -> codeBlock(element);
            case "code", "kbd", "samp" -> inlineCode(element.wholeText());
            case "blockquote" -> block("> " + children(element).strip().replace("\n", "\n> "));
            case "ul", "ol" -> list(element);
            case "a" -> link(element);
            case "img" -> image(element);
            case "table" -> table(element);
            default -> children(element);
        };
    }

    private static String emphasis(Element element, String marker) {
        String content = children(element);
        if (content.isBlank()) return content;
        return (content.startsWith(" ") ? " " : "") + marker + content.strip() + marker
                + (content.endsWith(" ") ? " " : "");
    }

    private static String escape(String text) {
        StringBuilder result = new StringBuilder();
        for (char c : text.toCharArray()) {
            if ("\\`*_{}[]<>#|~!+-=.".indexOf(c) >= 0) result.append('\\');
            result.append(c);
        }
        return result.toString();
    }

    private static String fence(String text) {
        int length = 2;
        var matcher = BACKTICKS.matcher(text);
        while (matcher.find()) length = Math.max(length, matcher.group().length());
        return "`".repeat(length + 1);
    }

    private static String inlineCode(String text) {
        if (text.isEmpty()) return "";
        String marker = fence(text);
        // 内联代码不需要三反引号，只有内容中存在反引号时才扩展。
        if (!text.contains("`")) marker = "`";
        boolean pad = text.startsWith("`") || text.endsWith("`")
                || (text.startsWith(" ") && text.endsWith(" ") && !text.isBlank());
        return marker + (pad ? " " : "") + text + (pad ? " " : "") + marker;
    }

    private static String codeBlock(Element element) {
        String text = element.wholeText();
        String marker = fence(text);
        String language = "";
        Element code = element.selectFirst("code");
        if (code != null) {
            for (String className : code.classNames()) {
                if (className.matches("language-[A-Za-z0-9_+.-]+")) {
                    language = className.substring("language-".length());
                    break;
                }
            }
        }
        return block(marker + language + "\n" + text + (text.endsWith("\n") ? "" : "\n") + marker);
    }

    private static String list(Element element) {
        StringBuilder result = new StringBuilder();
        int number = integer(element.attr("start"), 1);
        for (Element item : element.children()) {
            if (!item.normalName().equals("li")) continue;
            number = integer(item.attr("value"), number);
            String marker = element.normalName().equals("ol") ? number++ + ". " : "- ";
            String content = children(item).strip();
            if (!result.isEmpty()) result.append('\n');
            result.append(marker).append(content.replace("\n", "\n" + " ".repeat(marker.length())));
        }
        return block(result.toString());
    }

    private static int integer(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String destination(Element element, String attribute, boolean image) {
        String url = element.absUrl(attribute);
        if (!(url.startsWith("http://") || url.startsWith("https://")
                || (!image && url.startsWith("mailto:")))) return "";
        return "<" + url.replace("\\", "%5C").replace("<", "%3C").replace(">", "%3E")
                .replace(" ", "%20").replace("\r", "").replace("\n", "") + ">";
    }

    private static String link(Element element) {
        String label = children(element).strip();
        String target = destination(element, "href", false);
        // 文档站常用无可见文字的锚点图标；不要凭 href 给标题补上一段网址。
        if (target.isEmpty() || label.isEmpty()) return label;
        return "[" + label + "](" + target + ")";
    }

    private static String image(Element element) {
        String alt = escape(element.attr("alt"));
        String target = destination(element, "src", true);
        return target.isEmpty() ? alt : "![" + alt + "](" + target + ")";
    }

    private static String table(Element table) {
        List<List<String>> rows = new ArrayList<>();
        boolean hasHeader = false;
        int columns = 0;
        for (Element row : table.select("tr")) {
            if (row.closest("table") != table) continue;
            List<String> cells = new ArrayList<>();
            for (Element cell : row.children()) {
                if (!(cell.normalName().equals("th") || cell.normalName().equals("td"))) continue;
                if (rows.isEmpty() && cell.normalName().equals("th")) hasHeader = true;
                cells.add(children(cell).strip().replace("\n", "<br>")
                        .replaceAll("(?<!\\\\)\\|", "\\\\|"));
            }
            if (!cells.isEmpty()) {
                rows.add(cells);
                columns = Math.max(columns, cells.size());
            }
        }
        if (rows.isEmpty()) return block(children(table));
        StringBuilder result = new StringBuilder();
        Element caption = table.selectFirst("caption");
        if (caption != null) result.append(children(caption).strip()).append("\n\n");
        // 没有 th 的表格添加空表头，避免把第一行数据误当成列名。
        result.append(tableRow(hasHeader ? rows.get(0) : List.of(), columns));
        result.append(tableRow(java.util.Collections.nCopies(columns, "---"), columns));
        for (int i = hasHeader ? 1 : 0; i < rows.size(); i++) result.append(tableRow(rows.get(i), columns));
        return block(result.toString());
    }

    private static String tableRow(List<String> cells, int columns) {
        StringBuilder result = new StringBuilder("|");
        for (int i = 0; i < columns; i++)
            result.append(' ').append(i < cells.size() ? cells.get(i) : "").append(" |");
        return result.append('\n').toString();
    }
}

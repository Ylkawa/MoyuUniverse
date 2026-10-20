import com.nekoyu.Universe.AIChat.Web.HtmlMarkdown;

/** 基础 HTML 语义、代码空白、链接解析及噪声过滤的离线回归。 */
public class HtmlMarkdownTest {
    private static String convert(String html) {
        return HtmlMarkdown.convert(html, "https://example.org/docs/page");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        String article = convert("""
                <h1>文章</h1><p>第一段 <strong>重点</strong> 与 <em>解释</em>。</p>
                <p>第二段<br>换行 <del>旧内容</del></p>
                <h2>步骤</h2><ol start="3"><li>安装<ul><li>子项</li></ul></li><li>运行</li></ol>
                <blockquote><p>引用</p><p>下一段</p></blockquote><hr>
                """);
        check(article.startsWith("# 文章\n\n第一段 **重点** 与 *解释*"), "heading, paragraphs and emphasis");
        check(article.contains("第二段  \n换行 ~~旧内容~~"), "line break and deletion");
        check(article.contains("## 步骤"), "heading depth");
        check(article.contains("3. 安装\n   \n   - 子项\n4. 运行"), "ordered and nested list indentation");
        check(article.contains("> 引用\n> \n> 下一段"), "multi-paragraph quotation");
        check(article.endsWith("---"), "horizontal rule");

        String links = convert("""
                <p><a href="../guide?q=1&amp;x=2">指南</a> <a href="#part">章节</a>
                <img alt="示例" src="/images/a.png"><a href="javascript:alert(1)">无效链接</a></p>
                """);
        check(links.contains("[指南](<https://example.org/guide?q=1&x=2>)"), "relative URL resolved");
        check(links.contains("[章节](<https://example.org/docs/page#part>)"), "fragment URL resolved");
        check(links.contains("![示例](<https://example.org/images/a.png>)"), "image alt and URL");
        check(links.contains("无效链接") && !links.contains("javascript:"), "unsupported URL retains label");
        check(convert("<base href='https://cdn.example.org/'><a href='guide'>文档</a>")
                .equals("[文档](<https://cdn.example.org/guide>)"), "HTML base URL respected");
        check(convert("<h2>章节<a href='#section'><span aria-hidden='true'>#</span></a></h2>")
                .equals("## 章节"), "empty permalink does not append a URL to heading");
        check(convert("<h2><a href='#empty'></a></h2>").isEmpty(), "empty heading does not create Markdown syntax");
        check(convert("<a href='/guide'><img src='/icon.png' alt='指南'></a>")
                .equals("[![指南](<https://example.org/icon.png>)](<https://example.org/guide>)"), "linked image is preserved");

        String code = convert("<pre><code class='language-java'>  a &lt; b;\n\n\n    ```\n</code></pre>");
        check(code.equals("````java\n  a < b;\n\n\n    ```\n````"), "code whitespace and fence retained");
        check(convert("<p><code>a`b</code> <code> x </code></p>").contains("`  x  `"), "inline code space padding");
        check(convert("<p>*literal* [text] &amp; &lt;tag&gt;</p>")
                .equals("\\*literal\\* \\[text\\] & \\<tag\\>"), "plain text does not become Markdown markup");

        String table = convert("""
                <table><caption>比较</caption><tr><th>项目</th><th>说明</th></tr>
                <tr><td>A|B</td><td><strong>重点</strong><br>下一行</td></tr></table>
                """);
        check(table.equals("比较\n\n| 项目 | 说明 |\n| --- | --- |\n| A\\|B | **重点**  <br>下一行 |"),
                "table preserves cell boundaries and inline formatting");
        check(convert("<table><tr><td>第一行</td></tr><tr><td>第二行</td><td>数据</td></tr></table>")
                .equals("|  |  |\n| --- | --- |\n| 第一行 |  |\n| 第二行 | 数据 |"), "headerless table preserves data rows");
        check(convert("<table><tr><th>代码</th></tr><tr><td><code>a|b</code></td></tr></table>")
                .contains("| `a\\|b` |"), "pipe in inline code does not split table cell");

        check(convert("""
                <script>脚本</script><style>样式</style><noscript>备用</noscript><template>模板</template>
                <div hidden>隐藏</div><span aria-hidden="true">装饰</span><p>正文</p>
                """).equals("正文"), "non-content elements omitted");
        check(convert("<script>only script</script>").isEmpty(), "non-content-only page is empty");
        check(convert("<div>前文<span>内联</span>后文</div><div>下一段</div>")
                .equals("前文内联后文\n\n下一段"), "inline text order and block boundaries");
        System.out.println("HtmlMarkdownTest passed");
    }
}

package com.nekoyu.Universe.AIChat;

import java.util.List;

/** Run directly; does not require a model, MySQL, or Qdrant. */
public class KnowledgeQueryParserTest {
    public static void main(String[] args) {
        String json = """
                {"items":[
                  {"question":"A|B 模式何时上线？","conclusion":"A|B 模式于 2026 年上线。","confidence":0.95,"ttl_days":180},
                  {"question":"A|B 模式何时上线？","conclusion":"重复问题","confidence":0.95,"ttl_days":180},
                  {"question":"不可靠的信息","conclusion":"无依据","confidence":0.7,"ttl_days":30},
                  {"question":"错误期限","conclusion":"无效","confidence":0.95,"ttl_days":-1},
                  {"question":"第二个有效问题","conclusion":"第二条结论","confidence":0.9,"ttl_days":365},
                  {"question":"第三个有效问题","conclusion":"第三条结论","confidence":0.9,"ttl_days":365}
                ]}
                """;
        List<KnowledgeQueryParser.Entry> entries = KnowledgeQueryParser.parse(json, 2);
        check(entries.size() == 2, "deduplicate, validate, and limit items");
        check(entries.get(0).question().contains("|"), "preserve pipes inside JSON strings");
        check(entries.get(0).conclusion().contains("|"), "preserve conclusion punctuation");
        check(KnowledgeQueryParser.parse("{\"items\":[]}", 2).isEmpty(), "accept empty result");
        try {
            KnowledgeQueryParser.parse("问题|结论|0.9|30", 2);
            throw new AssertionError("old delimited output was accepted");
        } catch (IllegalArgumentException expected) {
            // Malformed model output cannot reach the index.
        }
        System.out.println("KnowledgeQueryParserTest passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

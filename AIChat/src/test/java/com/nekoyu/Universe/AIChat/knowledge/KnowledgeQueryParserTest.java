package com.nekoyu.Universe.AIChat.knowledge;

import java.util.List;

/** 可直接运行，无需模型、MySQL 或 Qdrant。 */
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
        for (String invalid : List.of("{}", "{\"items\":null}", "{\"items\":false}", "{\"items\":[],\"extra\":1}")) {
            try {
                KnowledgeQueryParser.parse(invalid, 2);
                throw new AssertionError("invalid items structure was accepted: " + invalid);
            } catch (IllegalArgumentException expected) {
                // 顶层结构缺失、类型错误或多余字段不能进入索引流程。
            }
        }
        check(KnowledgeQueryParser.parse("""
                {"items":[{"question":"缺少结论","confidence":0.9,"ttl_days":365},
                          {"question":"多余字段","conclusion":"结论","confidence":0.9,"ttl_days":365,"extra":1},
                          {"question":"字符串数值","conclusion":"结论","confidence":"0.9","ttl_days":365}]}
                """, 3).isEmpty(), "reject missing, extra and mistyped entry fields");
        try {
            KnowledgeQueryParser.parse("问题|结论|0.9|30", 2);
            throw new AssertionError("old delimited output was accepted");
        } catch (IllegalArgumentException expected) {
            // 格式错误的模型输出不能写入索引。
        }
        System.out.println("KnowledgeQueryParserTest passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

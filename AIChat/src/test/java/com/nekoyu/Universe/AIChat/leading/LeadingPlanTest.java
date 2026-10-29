package com.nekoyu.Universe.AIChat.leading;

import java.util.Set;

/** 可直接运行，无需模型、Qdrant 或数据库。 */
public class LeadingPlanTest {
    public static void main(String[] args) {
        String json = """
                {"add":[{"location_id":"user:1","content":"用户喜欢爵士乐","confidence":0.9,"importance":0.8},
                        {"location_id":"user:other","content":"不应写入","confidence":0.9,"importance":0.8}],
                 "update":[{"id":1,"content":"用户喜欢爵士乐","confidence":0.9,"importance":0.8},
                           {"id":99,"content":"不存在","confidence":0.9,"importance":0.8}],
                 "delete":[1,2],
                 "memory_queries":[{"question":"用户喜欢什么音乐？","location_id":"user:1"},
                                   {"question":"用户喜欢什么音乐？","location_id":"user:1"},
                                   {"question":"越权查询","location_id":"user:other"}],
                 "knowledge_queries":["爵士乐何时起源？","爵士乐何时起源？","爵士乐有哪些风格？","第三个问题","第四个问题","第五个问题","第六个问题","超出预算的问题"]}
                """;
        LeadingPlan plan = parse(json, Set.of("user:1"), Set.of(1, 2));
        check(plan.additions.size() == 1, "reject unknown LocationId for writes");
        check(plan.updates.size() == 1 && plan.deletions.equals(java.util.List.of(2)), "reject invalid or conflicting ids");
        check(plan.memoryQueries.size() == 1, "dedupe queries and reject unknown scope");
        check(plan.knowledgeQueries.equals(java.util.List.of("爵士乐何时起源？", "爵士乐有哪些风格？",
                "第三个问题", "第四个问题", "第五个问题", "第六个问题")),
                "preserve independent subquestions in priority order within budget");
        LeadingPlan nullable = parse("""
                {"add":[],"update":[],"delete":[],
                 "memory_queries":[{"question":"会话的既有记忆是什么？","location_id":null}],"knowledge_queries":[]}
                """, Set.of(), Set.of());
        check(nullable.memoryQueries.size() == 1 && nullable.memoryQueries.get(0).locationId() == null,
                "accept nullable query location");
        LeadingPlan memory = parse("""
                {"add":[{"location_id":"user:1","content":"第一条\\n多行记忆","confidence":0.9,"importance":0.8},
                        {"location_id":"user:1","content":"第二条","confidence":0.9,"importance":0.8},
                        {"location_id":"user:1","content":"第三条","confidence":0.9,"importance":0.8},
                        {"location_id":"user:1","content":"第四条","confidence":0.9,"importance":0.8},
                        {"location_id":"other","content":"越权","confidence":0.9,"importance":0.8},
                        {"location_id":"user:1","content":"越界分数","confidence":2,"importance":0.8}],
                 "update":[{"id":1,"content":"更新","confidence":0.9,"importance":0.8},
                           {"id":99,"content":"未知编号","confidence":0.9,"importance":0.8}],"delete":[1,2,99]}
                """, Set.of("user:1"), Set.of(1, 2));
        check(memory.additions.size() == 4 && memory.additions.get(0).content().contains("\n"),
                "memory construction keeps multiline facts and has no leading limit");
        check(memory.updates.size() == 1 && memory.deletions.equals(java.util.List.of(2)),
                "memory construction rejects unknown IDs and conflicting operations");
        check(parse("{\"add\":[],\"update\":[],\"delete\":[]}", Set.of(), Set.of())
                .additions.isEmpty(), "empty arrays replace END");
        try {
            LeadingPlan.parse("NEW 0.9 0.8 [user:1]: fact", Set.of("user:1"), Set.of(), Set.of());
            throw new AssertionError("legacy memory commands were accepted");
        } catch (IllegalArgumentException expected) {
            // 旧格式文本不能用于记忆写入。
        }
        try {
            LeadingPlan.parse("{\"memory_queries\":[]}", Set.of(), Set.of(), Set.of());
            throw new AssertionError("missing fields were accepted");
        } catch (IllegalArgumentException expected) {
            // 无效计划不得产生副作用或发起查询。
        }
        var routed = com.google.gson.JsonParser.parseString(emptyPlan()).getAsJsonObject();
        routed.addProperty("should_reply", true);
        routed.getAsJsonArray("addressed_message_ids").add(160742629L);
        check(LeadingPlan.parse(routed.toString(), Set.of(), Set.of(), Set.of(160742629L))
                        .addressedMessageIds.contains(160742629L), "accept the supplied original message ID");
        rejects(() -> LeadingPlan.parse(routed.toString(), Set.of(), Set.of(), Set.of(8L)));
        routed.getAsJsonArray("addressed_message_ids").set(0, new com.google.gson.JsonPrimitive(1));
        try {
            LeadingPlan.parse(routed.toString(), Set.of(), Set.of(), Set.of(160742629L));
            throw new AssertionError("short input ordinal was accepted as a message ID");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().equals("非本批真人消息 ID: 1"), "identify an out-of-batch message ID");
        }
        routed.getAsJsonArray("addressed_message_ids").set(0, new com.google.gson.JsonPrimitive(Long.MIN_VALUE));
        routed.getAsJsonArray("addressed_message_ids").add(Long.MAX_VALUE);
        Set<Long> signedIds = Set.of(Long.MIN_VALUE, Long.MAX_VALUE);
        check(LeadingPlan.parse(routed.toString(), Set.of(), Set.of(), signedIds).addressedMessageIds.equals(signedIds),
                "preserve signed long message IDs exactly");
        routed.addProperty("should_reply", "true");
        rejects(() -> LeadingPlan.parse(routed.toString(), Set.of(), Set.of(), signedIds));
        routed.addProperty("should_reply", true);
        routed.getAsJsonArray("addressed_message_ids").add(7.5);
        rejects(() -> LeadingPlan.parse(routed.toString(), Set.of(), Set.of(), signedIds));
        routed.getAsJsonArray("addressed_message_ids").set(2,
                new com.google.gson.JsonPrimitive(new java.math.BigInteger("9223372036854775808")));
        rejects(() -> LeadingPlan.parse(routed.toString(), Set.of(), Set.of(), signedIds));
        System.out.println("LeadingPlanTest passed");
    }

    public static String emptyPlan() {
        return "{\"add\":[],\"update\":[],\"delete\":[],\"memory_queries\":[],\"knowledge_queries\":[],\"should_reply\":false,\"addressed_message_ids\":[]}";
    }

    private static LeadingPlan parse(String json, Set<String> locations, Set<Integer> memories) {
        var object = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        object.addProperty("should_reply", false);
        object.add("addressed_message_ids", new com.google.gson.JsonArray());
        if (!object.has("memory_queries")) object.add("memory_queries", new com.google.gson.JsonArray());
        if (!object.has("knowledge_queries")) object.add("knowledge_queries", new com.google.gson.JsonArray());
        return LeadingPlan.parse(object.toString(), locations, memories, Set.of());
    }

    private static void rejects(Runnable action) {
        try { action.run(); throw new AssertionError("invalid routing was accepted"); }
        catch (IllegalArgumentException expected) { }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

package com.nekoyu.Universe.AIChat;

import java.util.Set;

/** Run directly; no model, Qdrant, or database is needed. */
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
                 "knowledge_queries":["爵士乐何时起源？","爵士乐何时起源？","爵士乐有哪些风格？","第三个问题"]}
                """;
        LeadingPlan plan = LeadingPlan.parse(json, Set.of("user:1"), Set.of(1, 2));
        check(plan.additions.size() == 1, "reject unknown LocationId for writes");
        check(plan.updates.size() == 1 && plan.deletions.equals(java.util.List.of(2)), "reject invalid or conflicting ids");
        check(plan.memoryQueries.size() == 1, "dedupe queries and reject unknown scope");
        check(plan.knowledgeQueries.size() == 2, "limit distinct knowledge queries");
        LeadingPlan nullable = LeadingPlan.parse("""
                {"add":[],"update":[],"delete":[],
                 "memory_queries":[{"question":"会话的既有记忆是什么？","location_id":null}],"knowledge_queries":[]}
                """, Set.of(), Set.of());
        check(nullable.memoryQueries.size() == 1 && nullable.memoryQueries.get(0).locationId() == null,
                "accept nullable query location");
        LeadingPlan memory = LeadingPlan.parseMemory("""
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
        check(LeadingPlan.parseMemory("{\"add\":[],\"update\":[],\"delete\":[]}", Set.of(), Set.of())
                .additions.isEmpty(), "empty arrays replace END");
        try {
            LeadingPlan.parseMemory("NEW 0.9 0.8 [user:1]: fact", Set.of("user:1"), Set.of());
            throw new AssertionError("legacy memory commands were accepted");
        } catch (IllegalArgumentException expected) {
            // Legacy text must not reach memory writes.
        }
        try {
            LeadingPlan.parse("{\"memory_queries\":[]}", Set.of(), Set.of());
            throw new AssertionError("missing fields were accepted");
        } catch (IllegalArgumentException expected) {
            // Invalid plans must have no effects or queries.
        }
        System.out.println("LeadingPlanTest passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

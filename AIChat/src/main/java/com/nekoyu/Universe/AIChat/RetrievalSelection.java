package com.nekoyu.Universe.AIChat;

import java.util.*;
import java.util.function.Function;

/** 本轮检索的纯聚合逻辑：先保障查询覆盖，再按统一分数补齐展示名额。 */
final class RetrievalSelection {
    private RetrievalSelection() {}

    private static <K, V> List<V> select(Map<K, V> all, Collection<K> reserved,
                                         Comparator<V> order, Function<V, K> key, int limit) {
        if (limit <= 0) return List.of();
        Set<K> selected = new HashSet<>(reserved);
        List<V> ranked = all.values().stream().sorted(order).toList();
        for (V value : ranked) {
            if (selected.size() >= limit) break;
            selected.add(key.apply(value));
        }
        return ranked.stream().filter(value -> selected.contains(key.apply(value))).limit(limit).toList();
    }

    static final class MemoryHits {
        private final Map<UUID, Memory.Item> all = new HashMap<>();
        private final Map<Integer, Map<UUID, Memory.Item>> byQuery = new HashMap<>();

        void add(int query, List<Memory.Item> hits) {
            for (Memory.Item item : hits) {
                all.merge(item.id, item, (a, b) -> b.score > a.score ? b : a);
                byQuery.computeIfAbsent(query, key -> new HashMap<>())
                        .merge(item.id, item, (a, b) -> b.score > a.score ? b : a);
            }
        }

        Collection<Memory.Item> all() { return all.values(); }
        int size() { return all.size(); }

        List<Memory.Item> selected() {
            return select(all, byQuery.values().stream()
                            .map(hits -> hits.values().stream().min(Memory.RETRIEVAL_ORDER).orElseThrow().id).toList(),
                    Memory.RETRIEVAL_ORDER, item -> item.id, 6);
        }
    }

    private record FactKey(String question, String conclusion) {
        static FactKey of(KnowledgeStore.KnowledgeMatch fact) {
            return new FactKey(fact.question(), fact.conclusion());
        }
    }

    static final class KnowledgeHits {
        private final Map<Long, KnowledgeStore.KnowledgeResult> all = new HashMap<>();
        private final Map<Integer, KnowledgeStore.KnowledgeResult> queryBest = new HashMap<>();
        // 每条事实保留各查询中的原始匹配分，跨查询最高分不影响各查询的最佳事实选择。
        private final Map<Long, Map<FactKey, Map<Integer, KnowledgeStore.KnowledgeMatch>>> provenance = new HashMap<>();

        void add(int query, List<KnowledgeStore.KnowledgeResult> hits) {
            for (KnowledgeStore.KnowledgeResult hit : hits) {
                List<KnowledgeStore.KnowledgeMatch> hitFacts = hit.factMatches();
                if (hitFacts.isEmpty()) continue;
                KnowledgeStore.KnowledgeResult perQuery = new KnowledgeStore.KnowledgeResult();
                perQuery.documentId = hit.documentId;
                KnowledgeStore.KnowledgeResult merged = all.computeIfAbsent(hit.documentId, id -> {
                    KnowledgeStore.KnowledgeResult result = new KnowledgeStore.KnowledgeResult();
                    result.documentId = id;
                    result.title = hit.title;
                    result.url = hit.url;
                    result.content = hit.content;
                    return result;
                });
                for (KnowledgeStore.KnowledgeMatch fact : hitFacts) {
                    perQuery.addMatch(fact);
                    merged.addMatch(fact);
                    provenance.computeIfAbsent(hit.documentId, id -> new HashMap<>())
                            .computeIfAbsent(FactKey.of(fact), key -> new HashMap<>())
                            .merge(query, fact, (a, b) -> KnowledgeStore.KnowledgeMatch.ORDER.compare(a, b) <= 0 ? a : b);
                }
                queryBest.merge(query, perQuery,
                        (a, b) -> KnowledgeStore.KnowledgeResult.ORDER.compare(a, b) <= 0 ? a : b);
            }
        }

        int size() { return all.size(); }

        List<KnowledgeStore.KnowledgeResult> selected() {
            return select(all, queryBest.values().stream().map(hit -> hit.documentId).toList(),
                    KnowledgeStore.KnowledgeResult.ORDER, hit -> hit.documentId, 4);
        }

        List<KnowledgeStore.KnowledgeMatch> facts(long documentId) {
            Map<FactKey, KnowledgeStore.KnowledgeMatch> facts = new HashMap<>();
            for (KnowledgeStore.KnowledgeMatch fact : all.get(documentId).matches) facts.put(FactKey.of(fact), fact);
            Map<Integer, KnowledgeStore.KnowledgeMatch> best = new HashMap<>();
            for (var queries : provenance.getOrDefault(documentId, Map.of()).values()) {
                queries.forEach((query, fact) -> best.merge(query, fact,
                        (a, b) -> KnowledgeStore.KnowledgeMatch.ORDER.compare(a, b) <= 0 ? a : b));
            }
            return select(facts, best.values().stream().map(FactKey::of).toList(),
                    KnowledgeStore.KnowledgeMatch.ORDER, FactKey::of, 3);
        }
    }
}

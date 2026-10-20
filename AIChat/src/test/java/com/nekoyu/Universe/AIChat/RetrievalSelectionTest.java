package com.nekoyu.Universe.AIChat;

import com.nekoyu.Universe.AIChat.knowledge.KnowledgeStore;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalSelectionTest {
    static KnowledgeStore.KnowledgeMatch fact(String question, String conclusion, float score) {
        return new KnowledgeStore.KnowledgeMatch(question, conclusion, 0.9f, score);
    }

    static KnowledgeStore.KnowledgeResult document(long id, KnowledgeStore.KnowledgeMatch... facts) {
        var result = new KnowledgeStore.KnowledgeResult();
        result.documentId = id;
        result.title = "title-" + id;
        result.url = "https://example.invalid/" + id;
        result.content = "x".repeat(1_000);
        for (var fact : facts) result.addMatch(fact);
        return result;
    }

    static Memory.Item memory(long id, float score) {
        Memory.Item item = new Memory.Item();
        item.id = new UUID(0, id);
        item.content = "memory-" + id;
        item.score = score;
        item.confidence = 0.9f;
        item.importance = 0.8f;
        item.locationId = "Test:user";
        return item;
    }

    @Test void documentRetainsDistinctConclusionsAndDerivesLegacyFields() {
        var doc = document(7, fact(" Q ", " A ", 0.7f), fact("Q2", "A2", 0.8f));
        doc.addMatch(new KnowledgeStore.KnowledgeMatch("Q", "A", 0.95f, 0.9f));
        doc.addMatch(fact("Q", "conflicting answer", 0.85f));
        assertEquals(3, doc.matches.size());
        assertEquals("Q", doc.bestQuery);
        assertEquals("A", doc.bestConclusion);
        assertEquals(0.9f, doc.bestScore);
        assertEquals(0.95f, doc.bestConfidence);
        assertEquals(List.of("Q", "Q2"), doc.matchedQueries);
    }

    @Test void mergesQueriesWithoutMutatingInputsAndSupportsLegacyResults() {
        var first = document(7, fact("condition", "eligible", 0.95f));
        var second = new KnowledgeStore.KnowledgeResult();
        second.documentId = 7;
        second.bestQuery = "quota";
        second.bestConclusion = "100";
        second.bestScore = 0.7f;
        var hits = new RetrievalSelection.KnowledgeHits();
        hits.add(0, List.of(first, first));
        hits.add(1, List.of(second));
        assertEquals(1, hits.size());
        assertEquals(List.of("eligible", "100"), hits.facts(7).stream().map(KnowledgeStore.KnowledgeMatch::conclusion).toList());
        assertEquals(1, first.matches.size());
        assertTrue(second.matches.isEmpty());
    }

    @Test void knowledgeLimitsPreserveEachQueriesBestDocumentAndFact() {
        var first = new ArrayList<KnowledgeStore.KnowledgeResult>();
        for (int i = 1; i <= 6; i++) first.add(document(i, fact("q" + i, "a" + i, 1f - i * 0.01f)));
        first.set(0, document(1, fact("a", "a", 0.99f), fact("b", "b", 0.98f),
                fact("c", "c", 0.97f), fact("d", "d", 0.96f)));
        var second = List.of(document(9, fact("quota", "quota answer", 0.75f)),
                document(1, fact("low", "second query answer", 0.65f), fact("a", "a", 0.61f)));
        var hits = new RetrievalSelection.KnowledgeHits();
        hits.add(0, first);
        hits.add(1, second);
        assertEquals(List.of(1L, 2L, 3L, 9L), hits.selected().stream().map(d -> d.documentId).toList());
        assertEquals(List.of("a", "b", "low"), hits.facts(1).stream().map(KnowledgeStore.KnowledgeMatch::question).toList());
        var reversed = new RetrievalSelection.KnowledgeHits();
        var reversedFirst = new ArrayList<>(first);
        Collections.reverse(reversedFirst);
        reversed.add(0, second);
        reversed.add(1, reversedFirst);
        assertEquals(hits.selected().stream().map(d -> d.documentId).toList(), reversed.selected().stream().map(d -> d.documentId).toList());
        assertEquals(hits.facts(1), reversed.facts(1));
    }

    @Test void sharedFactCoversBothQueriesAndTiesAreStable() {
        var hits = new RetrievalSelection.KnowledgeHits();
        hits.add(0, List.of(document(2, fact("z", "z", 0.9f)),
                document(1, fact("c", "c", 0.9f), fact("b", "b", 0.9f), fact("a", "a", 0.9f), fact("d", "d", 0.9f))));
        hits.add(1, List.of(document(1, fact("a", "a", 0.85f))));
        assertEquals(List.of(1L, 2L), hits.selected().stream().map(d -> d.documentId).toList());
        assertEquals(List.of("a", "b", "c"), hits.facts(1).stream().map(KnowledgeStore.KnowledgeMatch::question).toList());
    }

    @Test void memoryKeepsLowerScoringQueryCoveredAndUsesBestDuplicateScore() {
        var first = new ArrayList<Memory.Item>();
        for (int i = 1; i <= 8; i++) first.add(memory(i, 1f - i * 0.02f));
        var original = first.get(7);
        var better = memory(8, 0.99f);
        var second = List.of(memory(9, 0.61f), memory(8, 0.60f));
        var hits = new RetrievalSelection.MemoryHits();
        hits.add(0, first);
        hits.add(0, List.of(better));
        hits.add(1, second);
        assertEquals(List.of(8L, 1L, 2L, 3L, 4L, 9L), ids(hits.selected()));
        assertEquals(9, hits.size());
        assertEquals(0.84f, original.score, 0.0001f);
        assertSame(better, hits.selected().get(0));
        var reversed = new RetrievalSelection.MemoryHits();
        Collections.reverse(first);
        reversed.add(0, second);
        reversed.add(1, List.of(better));
        reversed.add(1, first);
        assertEquals(ids(hits.selected()), ids(reversed.selected()));
    }

    @Test void secondQueryCanSupplyHighestScoreAndSharedWinnerUsesOneSlot() {
        var hits = new RetrievalSelection.MemoryHits();
        hits.add(0, List.of(memory(1, 0.9f), memory(2, 0.8f)));
        hits.add(1, List.of(memory(1, 0.99f), memory(3, 0.8f)));
        assertEquals(List.of(1L, 2L, 3L), ids(hits.selected()));
        assertEquals(0.99f, hits.selected().get(0).score);
        assertEquals(3, hits.size());
    }

    @Test void memoryRanksByExistingCompositeScore() {
        var semanticallyCloser = memory(1, 0.95f);
        semanticallyCloser.importance = 0;
        var important = memory(2, 0.8f);
        var hits = new RetrievalSelection.MemoryHits();
        hits.add(0, List.of(semanticallyCloser, important));
        assertEquals(List.of(2L, 1L), ids(hits.selected()));
        assertEquals(important.score * 0.5f + important.importance * 0.3f + important.confidence * 0.2f,
                Memory.retrievalScore(important));
    }

    @Test void emptyAndSingleQueriesAreSupported() {
        var memories = new RetrievalSelection.MemoryHits();
        var knowledge = new RetrievalSelection.KnowledgeHits();
        memories.add(0, List.of());
        knowledge.add(0, List.of());
        assertTrue(memories.selected().isEmpty());
        assertTrue(knowledge.selected().isEmpty());
        memories.add(0, List.of(memory(2, 0.9f), memory(1, 0.9f)));
        knowledge.add(0, List.of(document(1, fact("q", "a", 0.9f), fact("q2", "a2", 0.8f))));
        assertEquals(List.of(1L, 2L), ids(memories.selected()));
        assertEquals(2, knowledge.facts(1).size());
    }

    private static List<Long> ids(List<Memory.Item> items) {
        return items.stream().map(item -> item.id.getLeastSignificantBits()).toList();
    }
}

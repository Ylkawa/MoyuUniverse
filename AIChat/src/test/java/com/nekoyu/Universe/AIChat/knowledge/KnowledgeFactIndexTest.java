package com.nekoyu.Universe.AIChat.knowledge;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import com.nekoyu.Universe.AIChat.knowledge.KnowledgeFactTable.Source;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Fact merging and source lifetime regressions against the storage boundary; JDBC is tested separately. */
class KnowledgeFactIndexTest {
    private static final long NOW = 1_790_000_000_000L;
    private static final List<Float> VECTOR = List.of(0.3f, 0.7f);

    @Test void twoUrlsSupportOneFactWithoutBoostingConfidenceOrExtendingOtherSources() throws Exception {
        Fixture f = new Fixture();
        f.replace(1, fact("Model A price in region R on date D?", "$2/$10", 7), Instant.ofEpochMilli(NOW));
        var first = f.only();
        f.replace(2, fact("Model A price in region R on date D?", "$2/$10", 30), Instant.ofEpochMilli(NOW + 1));
        var merged = f.only();
        assertEquals(first.id(), merged.id());
        assertEquals(first.embedding(), merged.embedding());
        assertEquals(2, f.sources(merged).size());
        assertEquals(List.of(1L, 2L), merged.fact().sources().stream().map(Source::documentId).toList());
        assertEquals(0.85, merged.fact().confidence(), 0.0001);
        assertEquals(NOW, merged.fact().createdAt().toEpochMilli());
        assertEquals(NOW + 7 * 86_400_000L, f.sources(merged).get(0).reviewDueAt().toEpochMilli());
        assertEquals(NOW + 1 + 30 * 86_400_000L, f.sources(merged).get(1).reviewDueAt().toEpochMilli());
        assertEquals(0, f.matchCalls, "exact duplicates need no model round trip");
    }

    @Test void paraphraseUsesMatcherButDifferentVersionOrConclusionStaysSeparate() throws Exception {
        Fixture f = new Fixture();
        f.replace(1, fact("Model A v1 price?", "$2/$10", 30), Instant.ofEpochMilli(NOW));
        f.matcher = (incoming, candidates) -> incoming.conclusion().equals(candidates.get(0).conclusion()) ? 0 : -1;
        f.replace(2, fact("How much does Model A v1 cost?", "$2/$10", 30), Instant.ofEpochMilli(NOW + 1));
        assertEquals(1, f.points.size());
        assertEquals(1, f.matchCalls);
        f.replace(3, fact("Model A v2 price?", "$4/$20", 30), Instant.ofEpochMilli(NOW + 2));
        assertEquals(2, f.points.size());
        assertEquals(2, f.matchCalls);
    }

    @Test void reindexingEitherSourceOnlyRemovesItsOwnObsoleteSupport() throws Exception {
        Fixture f = new Fixture();
        var original = fact("Model A price?", "$2/$10", 30);
        f.replace(1, original, Instant.ofEpochMilli(NOW));
        f.replace(2, original, Instant.ofEpochMilli(NOW + 1));
        f.replace(1, fact("Model A price?", "$4/$20", 30), Instant.ofEpochMilli(NOW + 2));
        assertEquals(2, f.points.size());
        var old = f.byConclusion("$2/$10");
        assertEquals(List.of(2L), f.sources(old).stream().map(Source::documentId).toList());
        assertEquals(2, old.fact().sources().get(0).documentId());
        f.replace(2, fact("Model A price?", "$4/$20", 30), Instant.ofEpochMilli(NOW + 3));
        assertEquals(1, f.points.size(), "last obsolete source removal deletes only the unsupported fact");
        assertEquals(2, f.sources(f.only()).size());
    }

    @Test void sameSourceRetryIsIdempotentAndSecondaryMembershipIsFound() throws Exception {
        Fixture f = new Fixture();
        var entry = fact("Model A price?", "$2/$10", 30);
        f.replace(1, entry, Instant.ofEpochMilli(NOW));
        f.replace(2, entry, Instant.ofEpochMilli(NOW + 1));
        f.replace(2, entry, Instant.ofEpochMilli(NOW + 2));
        assertEquals(1, f.points.size());
        assertEquals(2, f.sources(f.only()).size());
        assertEquals(2, f.table.lastDocumentId);
    }

    @Test void expiryOfOneSourceDoesNotHideOrRefreshAnotherSource() throws Exception {
        Fixture f = new Fixture();
        f.replace(1, fact("Model A price?", "$2/$10", 7), Instant.ofEpochMilli(NOW));
        f.replace(2, fact("Model A price?", "$2/$10", 30), Instant.ofEpochMilli(NOW + 1));
        long later = NOW + 8 * 86_400_000L;
        assertEquals(List.of(2L), f.only().fact().activeSources(Instant.ofEpochMilli(later)).stream()
                .map(Source::documentId).toList());
        f.replace(3, fact("Model A price?", "$2/$10", 30), Instant.ofEpochMilli(later));
        assertEquals(1, f.points.size());
        assertEquals(2, f.only().fact().activeSources(Instant.ofEpochMilli(later)).get(0).documentId());
        assertEquals(List.of(2L, 3L), f.only().fact().activeSources(Instant.ofEpochMilli(later)).stream()
                .map(Source::documentId).toList());
        assertEquals(NOW + 7 * 86_400_000L, f.sources(f.only()).get(0).reviewDueAt().toEpochMilli());
    }

    @Test void partialBatchFailureRetainsOldFactsAndRetryConverges() throws Exception {
        Fixture f = new Fixture();
        f.replace(1, fact("Old question?", "Old answer", 30), Instant.ofEpochMilli(NOW));
        var oldId = f.only().id();
        var entries = List.of(fact("New question A?", "New answer A", 30), fact("New question B?", "New answer B", 30));
        f.writesUntilFailure = 1;
        assertThrows(IOException.class, () -> f.index.replace(1, entries, List.of(VECTOR, VECTOR), Instant.ofEpochMilli(NOW + 1)));
        assertTrue(f.points.containsKey(oldId));
        assertEquals(2, f.points.size());
        f.writesUntilFailure = -1;
        f.index.replace(1, entries, List.of(VECTOR, VECTOR), Instant.ofEpochMilli(NOW + 2));
        assertFalse(f.points.containsKey(oldId));
        assertEquals(2, f.points.size());
        for (var point : f.points.values()) assertEquals(1, f.sources(point).size());
    }

    @Test void failedWriteOrInvalidMatchCannotRemovePreviouslyStoredSupport() throws Exception {
        Fixture f = new Fixture();
        f.replace(1, fact("Model A price?", "$2/$10", 30), Instant.ofEpochMilli(NOW));
        var oldId = f.only().id();
        f.failWrite = true;
        assertThrows(IOException.class, () -> f.replace(1, fact("Model A price?", "$4/$20", 30), Instant.ofEpochMilli(NOW + 1)));
        assertTrue(f.points.containsKey(oldId));
        assertEquals(1, f.sources(f.only()).size());
        f.failWrite = false;
        f.matcher = (entry, candidates) -> 99;
        assertThrows(IOException.class, () -> f.replace(1, fact("Model A price?", "$4/$20", 30), Instant.ofEpochMilli(NOW + 2)));
        assertTrue(f.points.containsKey(oldId));
        f.matcher = (entry, candidates) -> { throw new IOException("offline classifier unavailable"); };
        assertThrows(IOException.class, () -> f.replace(1, fact("Model A price?", "$4/$20", 30), Instant.ofEpochMilli(NOW + 3)));
        assertTrue(f.points.containsKey(oldId));
    }

    @Test void researchFragmentUpdatesOnlyConflictingOwnSupportAndKeepsUnrelatedFacts() throws Exception {
        Fixture f = new Fixture();
        var oldA = fact("Model v1 limit?", "100", 30);
        var factB = fact("Model v1 platform?", "Linux", 30);
        f.index.replace(1, List.of(oldA, factB), List.of(VECTOR, VECTOR), Instant.ofEpochMilli(NOW));
        f.replace(2, oldA, Instant.ofEpochMilli(NOW + 1));

        f.index.merge(1, List.of(fact("Model v1 limit?", "200", 30)),
                List.of(VECTOR), Instant.ofEpochMilli(NOW + 2), KnowledgeFactIndexTest::sameQuestionDifferentAnswer);

        assertEquals(3, f.points.size());
        assertEquals(List.of(2L), f.sources(f.byConclusion("100")).stream()
                .map(Source::documentId).toList(), "another URL still supports the old fact");
        assertEquals(List.of("200", "Linux"), f.index.documentSources(1).stream()
                .map(Source::conclusion).sorted().toList());
        assertEquals(List.of("100"), f.index.documentSources(2).stream()
                .map(Source::conclusion).toList());
    }

    @Test void researchFragmentCanAppendWithoutReplacingExistingFacts() throws Exception {
        Fixture f = new Fixture();
        var a = fact("Question A?", "Answer A", 30);
        var b = fact("Question B?", "Answer B", 30);
        f.index.replace(1, List.of(a, b), List.of(VECTOR, VECTOR), Instant.ofEpochMilli(NOW));

        f.index.merge(1, List.of(fact("Question C?", "Answer C", 30)),
                List.of(VECTOR), Instant.ofEpochMilli(NOW + 1), (incoming, ownOld) -> false);

        assertEquals(3, f.points.size());
        assertEquals(List.of("Answer A", "Answer B", "Answer C"), f.index.documentSources(1).stream()
                .map(Source::conclusion).sorted().toList());
    }

    @Test void failedConflictDecisionCannotWriteOrRemoveAnyFact() throws Exception {
        Fixture f = new Fixture();
        f.replace(1, fact("Model v1 limit?", "100", 30), Instant.ofEpochMilli(NOW));
        var before = Map.copyOf(f.points);

        assertThrows(IOException.class, () -> f.index.merge(1,
                List.of(fact("Model v1 limit?", "200", 30)), List.of(VECTOR), Instant.ofEpochMilli(NOW + 1),
                (incoming, ownOld) -> { throw new IOException("offline classifier unavailable"); }));

        assertEquals(before, f.points);
    }

    @Test void researchBatchWriteFailureKeepsOldFactsAndRetryConverges() throws Exception {
        Fixture f = new Fixture();
        var oldA = fact("Question A?", "Old A", 30);
        var b = fact("Question B?", "Answer B", 30);
        f.index.replace(1, List.of(oldA, b), List.of(VECTOR, VECTOR), Instant.ofEpochMilli(NOW));
        var entries = List.of(fact("Question A?", "New A", 30), fact("Question C?", "Answer C", 30));
        f.writesUntilFailure = 1;

        assertThrows(IOException.class, () -> f.index.merge(1, entries,
                List.of(VECTOR, VECTOR), Instant.ofEpochMilli(NOW + 1), KnowledgeFactIndexTest::sameQuestionDifferentAnswer));

        assertEquals(3, f.points.size());
        assertEquals(1, f.sources(f.byConclusion("Old A")).size());
        var partiallyWrittenId = f.byConclusion("New A").id();
        f.writesUntilFailure = -1;
        f.index.merge(1, entries, List.of(VECTOR, VECTOR), Instant.ofEpochMilli(NOW + 2),
                KnowledgeFactIndexTest::sameQuestionDifferentAnswer);

        assertEquals(3, f.points.size());
        assertEquals(partiallyWrittenId, f.byConclusion("New A").id());
        assertEquals(List.of("Answer B", "Answer C", "New A"), f.index.documentSources(1).stream()
                .map(Source::conclusion).sorted().toList());
    }

    @Test void relearningExpiredOwnFactRefreshesItsOriginalPoint() throws Exception {
        Fixture f = new Fixture();
        var entry = fact("Model v1 limit?", "100", 7);
        f.replace(1, entry, Instant.ofEpochMilli(NOW));
        var original = f.only();
        long later = NOW + 8 * 86_400_000L;

        f.index.merge(1, List.of(entry), List.of(VECTOR), Instant.ofEpochMilli(later),
                (incoming, ownOld) -> { throw new AssertionError("identical facts cannot conflict"); });

        assertEquals(original.id(), f.only().id());
        assertEquals(original.embedding(), f.only().embedding());
        assertEquals(NOW, f.only().fact().createdAt().toEpochMilli());
        assertEquals(later + 7 * 86_400_000L, f.index.documentSources(1).get(0).reviewDueAt().toEpochMilli());
        f.replace(1, entry, Instant.ofEpochMilli(later + 1));
        assertEquals(original.id(), f.only().id(), "full rebuild also renews the existing fact");
    }

    @Test void equivalentEntriesInOneResearchBatchKeepLatestSupportAndOtherSources() throws Exception {
        Fixture f = new Fixture();
        var original = fact("Model v1 limit?", "100", 30);
        f.replace(1, original, Instant.ofEpochMilli(NOW));
        f.replace(2, original, Instant.ofEpochMilli(NOW + 1));
        var id = f.only().id();
        f.matcher = (incoming, candidates) -> {
            for (int i = 0; i < candidates.size(); i++)
                if (incoming.conclusion().equals(candidates.get(i).conclusion())) return i;
            return -1;
        };
        var entries = List.of(fact("What is the Model v1 limit?", "100", 7),
                fact("How high is the Model v1 limit?", "100", 90));

        f.index.merge(1, entries, List.of(VECTOR, VECTOR), Instant.ofEpochMilli(NOW + 2),
                (incoming, ownOld) -> false);

        assertEquals(id, f.only().id());
        assertEquals(2, f.sources(f.only()).size());
        assertEquals(entries.get(1).question(), f.index.documentSources(1).get(0).question());
        assertEquals(NOW + 2 + 90 * 86_400_000L, f.index.documentSources(1).get(0).reviewDueAt().toEpochMilli());
        assertEquals(NOW + 1 + 30 * 86_400_000L, f.index.documentSources(2).get(0).reviewDueAt().toEpochMilli());
    }

    private static boolean sameQuestionDifferentAnswer(KnowledgeQueryParser.Entry incoming,
                                                       KnowledgeFactIndex.Candidate ownOld) {
        return incoming.question().equals(ownOld.question()) && !incoming.conclusion().equals(ownOld.conclusion());
    }

    @Test void matchSelectionRejectsOutOfRangeFractionalAndUnstructuredAnswers() {
        assertEquals(-1, KnowledgeSources.parseMatchIndex("{\"match_index\":-1}", 2));
        assertEquals(1, KnowledgeSources.parseMatchIndex("{\"match_index\":1}", 2));
        for (String answer : List.of("{\"match_index\":2}", "{\"match_index\":-2}", "{\"match_index\":0.5}",
                "{\"match_index\":\"0\"}", "{\"match_index\":true}", "{\"match_index\":0,\"extra\":1}", "[]"))
            assertThrows(RuntimeException.class, () -> KnowledgeSources.parseMatchIndex(answer, 2), answer);
    }

    private static KnowledgeQueryParser.Entry fact(String question, String answer, int days) {
        return new KnowledgeQueryParser.Entry(question, answer, 0.85f, days);
    }

    static class Fixture {
        final Map<UUID, FactTableFixture.Stored> points;
        boolean failWrite;
        int writesUntilFailure = -1;
        int matchCalls;
        KnowledgeFactIndex.Matcher matcher = (incoming, candidates) -> -1;
        final KnowledgeFactIndex index;
        final FactTableFixture table;

        Fixture() {
            table = new FactTableFixture() {
                @Override protected void beforeWrite() throws IOException {
                    if (failWrite || writesUntilFailure == 0) throw new IOException("offline write failure");
                    if (writesUntilFailure > 0) writesUntilFailure--;
                }
            };
            points = table.points;
            index = new KnowledgeFactIndex(table, 10,
                    (entry, candidates) -> { matchCalls++; return matcher.select(entry, candidates); });
        }
        void replace(long id, KnowledgeQueryParser.Entry entry, Instant now) throws IOException {
            index.replace(id, List.of(entry), List.of(VECTOR), now);
        }
        FactTableFixture.Stored only() { assertEquals(1, points.size()); return points.values().iterator().next(); }
        FactTableFixture.Stored byConclusion(String conclusion) {
            return points.values().stream().filter(p -> p.fact().conclusion().equals(conclusion)).findFirst().orElseThrow();
        }
        List<Source> sources(FactTableFixture.Stored p) { return p.fact().sources(); }
    }
}

package com.nekoyu.Universe.AIChat;

import com.nekoyu.Universe.AIChat.leading.LeadingPlanTest;
import com.nekoyu.Universe.AIChat.leading.MemoryStore;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nekoyu.Universe.API.MessageChannel.*;
import com.nekoyu.Universe.API.MessageChannel.MessageField.*;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Message;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.nekoyu.Universe.AIChat.DriveMessageTest.*;
import static com.nekoyu.Universe.AIChat.SessionReplyQueueTest.check;

/** 使用真实会话入口和状态机，时钟、模型、存储与通道均为离线替身。 */
class UnifiedLeadingTest {
    @Test void existingContracts() throws Exception {
        SessionReplyQueueTest.main(new String[0]);
        DriveMessageTest.main(new String[0]);
        LeadingPlanTest.main(new String[0]);
        StructuredOutputsTest.main(new String[0]);
        StructuredTurnTest.main(new String[0]);
        KnowledgeQueryParserTest.main(new String[0]);
        XmlMarksTest.main(new String[0]);
    }

    @Test void inactiveAndEmptyKeyword() {
        try (Fixture f = new Fixture(false)) {
            f.config.Trigger = "keyword";
            f.config.Keyword = "";
            f.ai.startReplyService();
            send(f, "ordinary");
            f.time.advance(10_000);
            check(f.ai.activatingTopics.isEmpty(), "blank keyword cannot activate every message");
            f.config.Keyword = "bot";
            f.annotator.output = messages -> routedPlan(messages, false);
            send(f, "they said bot yesterday");
            f.time.drainWorkers();
            check(f.annotator.calls.size() == 1 && f.provider.calls.isEmpty(), "keyword skips waiting but model may decline reply");
        }
    }

    @Test void everyHumanMessageResetsDebounceAndKeepsMemory() {
        try (Fixture f = new Fixture(false)) {
            FakeMemory store = new FakeMemory();
            f.ai.memory = store;
            f.ai.startReplyService();
            send(f, "activate");
            f.time.advance(5_000);
            f.config.Trigger = "keyword";
            f.annotator.output = messages -> withAddition(routedPlan(messages, false), "a reusable preference");
            send(f, "first side conversation");
            f.time.advance(4_000);
            send(f, "second side conversation");
            f.time.advance(4_999);
            check(f.annotator.calls.size() == 1, "untriggered chatter resets the timer");
            f.time.advance(1);
            check(f.annotator.calls.size() == 2 && f.provider.calls.size() == 1, "one leading batch without a main reply");
            check(store.items.size() == 1, "side conversation still writes memory");
            f.time.advance(5_000);
            check(f.annotator.calls.size() == 2, "empty queue never runs another leading");
        }
    }

    @Test void atAndReplyAreImmediateAndMetadataSurvives() {
        try (Fixture f = new Fixture(false)) {
            f.config.Trigger = "keyword";
            f.config.nativeImage = true;
            f.ai.startReplyService();
            MCMessage at = f.user("question");
            at.time = 123456;
            at.messageFields.add(new AtField(f.channel.loginAccount));
            f.ai.handleSessionMessage(f.config, f.provider, at);
            f.time.drainWorkers();
            check(f.provider.calls.size() == 1, "direct at skips debounce without level metadata");
            MCMessage normalized = f.topic().getMessage(0);
            check(normalized.id == at.id && normalized.time == at.time && normalized.receiver == at.receiver,
                    "retain real message identity, timestamp and receiver");
            check(at.id == 160742629L && f.topic().replyLeading.addressedMessageIds().equals(Set.of(at.id)),
                    "route the reproduced original message ID instead of ordinal 1");
            String directory = lastPrompt(f);
            check(directory.contains("@目标=[Test:bot]") && directory.contains("主助手：bot(Test:bot)"), "leading receives bot and at identity");
            check(directory.contains("来源=真人；消息ID=" + at.id + "；")
                            && normalized.solveAll().contains("[" + at.id + "]")
                            && !newInputDirectory(f.annotator.calls.get(0)).contains("编号="),
                    "body and both directories use only the original message ID");
            MCMessage reply = f.user("follow up");
            reply.messageFields.add(new ReplyField("1"));
            reply.messageFields.add(new ReplyField(Long.toString(at.id)));
            f.ai.handleSessionMessage(f.config, f.provider, reply);
            f.time.drainWorkers();
            check(f.provider.calls.size() == 2 && lastPrompt(f).contains("回复目标=[1, " + at.id + "]"),
                    "reply to bot also skips waiting and retains original reference IDs");
            check(lastPrompt(f).contains("<reply to=\"" + at.id + "\"/>"), "reply marker shares the original ID");
            check(f.annotator.calls.get(1).stream().anyMatch(m -> "assistant".equals(m.role) && m.toString().contains("answer 1")),
                    "leading sees prior bot output as assistant");
        }
    }

    @Test void batchObjectChangesUpdateCountExactly() {
        try (Fixture f = new Fixture(false)) {
            f.ai.startReplyService();
            f.annotator.output = messages -> {
                JsonObject plan = plan(false);
                plan.getAsJsonArray("addressed_message_ids").add(newHumanIds(messages).get(1));
                return plan.toString();
            };
            f.time.advance(100);
            send(f, "talking to Alice");
            f.time.advance(100);
            f.messageNumber = -987654322L;
            send(f, "thanks to bot");
            f.messageNumber = 4294967295L;
            send(f, "still talking to Alice");
            f.time.advance(5_000);
            check(newHumanIds(f.annotator.calls.get(0)).equals(List.of(160742629L, -987654321L, 4294967296L)),
                    "retain original signed long IDs in input order");
            check(f.topic().replyLeading.addressedMessageIds().equals(Set.of(-987654321L))
                            && f.topic().lastAddressedAt == 200 && f.topic().unaddressedMessages == 1,
                    "match a negative original ID while counting by input order and arrival time");
            check(f.provider.calls.isEmpty(), "addressed thanks may reset lifetime without a reply");
        }
    }

    @Test void lengthBoundaryFlushesAllEvidenceBeforeTrimAndClose() {
        try (Fixture f = new Fixture(false)) {
            f.ai.startReplyService();
            FakeMemory store = new FakeMemory();
            f.ai.memory = store;
            f.config.maxTokens = 10;
            f.annotator.output = messages -> {
                JsonObject plan = plan(false);
                for (long id : newHumanIds(messages)) plan.getAsJsonArray("add").add(addition("fact " + id));
                return plan.toString();
            };
            for (int i = 1; i <= 20; i++) send(f, "side message " + i);
            f.time.drainWorkers();
            check(f.ai.activatingTopics.isEmpty(), "twentieth unaddressed message closes after leading");
            check(f.annotator.calls.size() == 1 && store.items.size() == 20, "trim and old three-item cap cannot lose final evidence");
            check(lastPrompt(f).contains("side message 1") && lastPrompt(f).contains("side message 20"), "both ends survive in evidence directory");
            f.time.advance(1_000_000);
            check(f.annotator.calls.size() == 1 && f.provider.calls.isEmpty(), "old timers cannot reopen a closed topic");
        }
    }

    @Test void lengthBoundaryCanBeResetByLeading() {
        try (Fixture f = new Fixture(false)) {
            f.ai.startReplyService();
            for (int i = 0; i < 20; i++) send(f, "talking to main");
            f.time.drainWorkers();
            check(f.topic() != null && f.topic().unaddressedMessages == 0 && f.provider.calls.size() == 1,
                    "provisional length check does not close an addressed batch");
        }
    }

    @Test void timeBoundaryFlushesPendingAndDoesNotSummarizeAgain() {
        try (Fixture f = new Fixture(false)) {
            f.ai.startReplyService();
            f.annotator.output = messages -> routedPlan(messages, false);
            send(f, "activate");
            f.time.advance(5_000);
            f.time.advance(AIChat.TOPIC_IDLE_MILLIS - 5_002);
            send(f, "last message before timeout");
            f.time.advance(2);
            check(f.annotator.calls.size() == 2 && f.ai.activatingTopics.isEmpty(), "expiry forces final batch before its five-second deadline");
            check(lastPrompt(f).contains("last message before timeout"), "final message reaches leading");
        }
        try (Fixture f = new Fixture(false)) {
            f.ai.startReplyService();
            send(f, "question");
            f.time.advance(5_000);
            f.time.advance(AIChat.TOPIC_IDLE_MILLIS - 5_000);
            check(f.ai.activatingTopics.isEmpty() && f.annotator.calls.size() == 1, "idle expiry does not call a whole-history summarizer");
        }
    }

    @Test void addressedNoReplyResetsTime() {
        try (Fixture f = new Fixture(false)) {
            f.ai.startReplyService();
            send(f, "question");
            f.time.advance(5_000);
            f.time.advance(500_000);
            f.annotator.output = messages -> {
                JsonObject plan = plan(false);
                newHumanIds(messages).forEach(id -> plan.getAsJsonArray("addressed_message_ids").add(id));
                return plan.toString();
            };
            send(f, "unused thanks");
            f.time.drainWorkers();
            f.time.advance(95_000);
            check(f.topic() != null && f.provider.calls.size() == 1, "thanks resets lifetime but does not reply");
            f.time.advance(505_000);
            check(f.ai.activatingTopics.isEmpty(), "new ten-minute deadline is enforced");
        }
    }

    @Test void failuresRetainEvidenceAndDirectCallsFallback() {
        try (Fixture f = new Fixture(false)) {
            f.ai.startReplyService();
            f.annotator.fail = true;
            send(f, "ordinary failed evidence");
            f.time.advance(5_000);
            check(f.provider.calls.isEmpty() && f.topic().failedLeadingInputs.size() == 1, "ordinary failures stay silent and retain evidence");
            send(f, "unused direct request");
            f.time.drainWorkers();
            check(f.provider.calls.size() == 1 && f.topic().failedLeadingInputs.size() == 2, "explicit call falls back and preserves facts");
            check(f.topic().replyLeading.addressedMessageIds().equals(Set.of(160742630L))
                            && f.topic().unaddressedMessages == 0 && f.topic().lastAddressedAt == 5_000,
                    "direct-call fallback uses the original message ID to update lifetime");
            f.annotator.fail = false;
            f.annotator.output = messages -> routedPlan(messages, false);
            send(f, "recover");
            f.time.advance(5_000);
            check(f.topic().failedLeadingInputs.isEmpty(), "successful next batch consumes failed evidence");
            check(lastPrompt(f).contains("ordinary failed evidence") && newHumanIds(f.annotator.calls.get(2)).size() == 1,
                    "old failed facts do not become new routing messages");
        }
    }

    @Test void shortOrdinalAndOldEvidenceCannotRouteNewMessages() {
        try (Fixture f = new Fixture(false)) {
            f.ai.startReplyService();
            f.annotator.output = ignored -> {
                JsonObject result = plan(true);
                result.getAsJsonArray("addressed_message_ids").add(1);
                return result.toString();
            };
            send(f, "first message");
            f.time.advance(5_000);
            check(f.provider.calls.isEmpty() && f.topic().failedLeadingInputs.size() == 1,
                    "short ordinal cannot route an original message ID");
            f.annotator.output = ignored -> {
                JsonObject result = plan(true);
                result.getAsJsonArray("addressed_message_ids").add(160742629L);
                return result.toString();
            };
            send(f, "second message");
            f.time.advance(5_000);
            check(f.provider.calls.isEmpty() && f.topic().failedLeadingInputs.size() == 2
                            && newHumanIds(f.annotator.calls.get(1)).equals(List.of(160742630L)),
                    "old history and retained evidence cannot route a later batch");
            f.annotator.output = messages -> routedPlan(messages, true);
            send(f, "recover");
            f.time.advance(5_000);
            check(f.provider.calls.size() == 1 && f.topic().failedLeadingInputs.isEmpty()
                            && f.topic().replyLeading.addressedMessageIds().equals(Set.of(160742631L)),
                    "a valid current original ID recovers and consumes retained evidence");
        }
    }

    @Test void invalidJsonAndMissingConfigFollowFallback() {
        try (Fixture f = new Fixture(false)) {
            f.ai.startReplyService();
            f.annotator.output = ignored -> "invalid JSON";
            send(f, "ordinary");
            f.time.advance(5_000);
            check(f.provider.calls.isEmpty(), "invalid JSON cannot trigger ordinary reply");
            f.ai.globalCfg.Annotator = null;
            send(f, "unused explicit");
            f.time.drainWorkers();
            check(f.provider.calls.size() == 1, "missing annotator reports failure and preserves direct fallback");
        }
    }

    @Test void finalFailedBatchIsRetriedOnlyOnce() {
        try (Fixture f = new Fixture(false)) {
            f.ai.startReplyService();
            f.annotator.fail = true;
            send(f, "ordinary");
            f.time.advance(5_000);
            f.time.advance(AIChat.TOPIC_IDLE_MILLIS - 5_000);
            check(f.annotator.calls.size() == 2 && f.ai.activatingTopics.isEmpty(), "one final supplement and bounded closure");
            f.time.advance(AIChat.TOPIC_IDLE_MILLIS);
            check(f.annotator.calls.size() == 2, "no automatic retry loop after failure");
        }
    }

    @Test void partialWritesRetryOnlyFailuresWithStableIds() {
        try (Fixture f = new Fixture(false)) {
            FakeMemory store = new FakeMemory();
            Memory.Item first = store.seed("old first");
            Memory.Item second = store.seed("old second");
            store.failures.put("fact-a", 1);
            store.failures.put("new first", 1);
            f.ai.memory = store;
            f.ai.startReplyService();
            f.annotator.output = ignored -> {
                JsonObject plan = plan(false);
                plan.getAsJsonArray("add").add(addition("fact-a"));
                plan.getAsJsonArray("add").add(addition("fact-b"));
                JsonObject update = addition("new first");
                update.remove("location_id");
                update.addProperty("id", 1);
                plan.getAsJsonArray("update").add(update);
                plan.getAsJsonArray("delete").add(2);
                return plan.toString();
            };
            send(f, "new supported facts");
            f.time.advance(5_000);
            check(f.topic().failedMemoryWrites.size() == 2, "failed add and update remain pending");
            check(!f.topic().activatingMemory.containsKey(first.id) && !store.items.containsKey(second.id), "cache changes only after confirmed success");
            f.annotator.output = messages -> routedPlan(messages, false);
            send(f, "next batch");
            f.time.advance(5_000);
            check(f.topic().failedMemoryWrites.isEmpty(), "retry confirms pending writes");
            List<UUID> ids = store.attempts.get("fact-a");
            check(ids.size() == 2 && ids.get(0).equals(ids.get(1)), "failed add keeps original UUID");
            check(store.attempts.get("fact-b").size() == 1 && store.deleteAttempts.get(second.id) == 1, "successful writes never repeat");
            check(store.items.get(first.id).content.equals("new first")
                    && f.topic().activatingMemory.get(first.id).locationId.equals("Test:user"), "updated cache retains memory ownership");
        }
    }

    @Test void newerCorrectionReplacesUnconfirmedOldWrite() {
        try (Fixture f = new Fixture(false)) {
            FakeMemory store = new FakeMemory();
            Memory.Item item = store.seed("original");
            store.failures.put("old correction", 100);
            f.ai.memory = store;
            f.ai.startReplyService();
            f.annotator.output = ignored -> updatePlan("old correction");
            send(f, "first correction");
            f.time.advance(5_000);
            f.annotator.output = ignored -> updatePlan("latest correction");
            send(f, "new correction");
            f.time.advance(5_000);
            f.annotator.output = messages -> routedPlan(messages, false);
            send(f, "another message");
            f.time.advance(5_000);
            check(store.items.get(item.id).content.equals("latest correction") && f.topic().failedMemoryWrites.isEmpty(),
                    "obsolete failed write cannot override newer successful correction");
        }
    }

    @Test void similarExistingMemoryIsActivatedInsteadOfInserted() {
        try (Fixture f = new Fixture(false)) {
            FakeMemory store = new FakeMemory();
            Memory.Item existing = store.seed("用户的父亲拥有一个使用了十几年的美国Google老账号。");
            existing.score = 0.97f;
            store.queryHits = List.of(existing);
            f.ai.memory = store;
            f.ai.startReplyService();
            f.annotator.output = messages -> withAddition(routedPlan(messages, false),
                    "用户的父亲拥有一个十几年的美国Google老账号。");
            send(f, "父亲的 Google 老账号");
            f.time.advance(5_000);
            check(store.items.size() == 1 && store.attempts.isEmpty(), "similar memory does not create a second point");
            check(f.topic().activatingMemory.containsKey(existing.id), "existing point becomes active in the topic");
            store.hideCandidates = true;
            f.annotator.output = messages -> routedPlan(messages, false);
            send(f, "下一条消息");
            f.time.advance(5_000);
            check(lastPrompt(f).contains(existing.content), "next leading receives the activated memory");
        }
    }

    @Test void similarityCheckKeepsDifferentFactsAndDeduplicatesOneBatch() {
        try (Fixture f = new Fixture(false)) {
            FakeMemory store = new FakeMemory();
            Memory.Item other = store.seed("不同的事实");
            other.score = 0.80f;
            store.queryHits = List.of(other);
            store.embeddingVectors.put("同批事实甲", new double[]{1, 0});
            store.embeddingVectors.put("同批事实乙", new double[]{1, 0});
            f.ai.memory = store;
            f.ai.startReplyService();
            f.annotator.output = messages -> {
                JsonObject result = plan(false);
                result.getAsJsonArray("add").add(addition("同批事实甲"));
                result.getAsJsonArray("add").add(addition("同批事实乙"));
                return result.toString();
            };
            send(f, "两个候选事实");
            f.time.advance(5_000);
            check(store.items.size() == 2 && store.attempts.containsKey("同批事实甲")
                            && !store.attempts.containsKey("同批事实乙"),
                    "low-score existing point is retained and similar additions in one plan write once");
        }
    }

    @Test void failedSimilarityLookupDoesNotWriteAnUncheckedMemory() {
        try (Fixture f = new Fixture(false)) {
            FakeMemory store = new FakeMemory();
            store.queryFailure = true;
            f.ai.memory = store;
            f.ai.startReplyService();
            f.annotator.output = messages -> withAddition(routedPlan(messages, false), "可能重复的事实");
            send(f, "可能重复的事实");
            f.time.advance(5_000);
            check(store.items.isEmpty() && store.attempts.isEmpty(), "lookup failure cannot bypass duplicate check");
            check(!f.topic().failedLeadingInputs.isEmpty(), "human evidence remains available for retry");
        }
    }

    @Test void arrivalsDuringLeadingBelongToNextBatch() {
        try (Fixture f = new Fixture(false)) {
            f.ai.startReplyService();
            f.annotator.onCall = messages -> {
                if (f.annotator.calls.size() == 1) {
                    send(f, "unused next direct call");
                    check(messages.stream().noneMatch(m -> m.toString().contains("next direct call")), "leading snapshot remains unchanged");
                }
            };
            send(f, "first");
            f.time.advance(5_000);
            check(f.annotator.calls.size() == 2 && f.provider.calls.size() == 2, "direct arrival resumes after first round without waiting");
            check(f.provider.calls.get(0).stream().noneMatch(m -> m.toString().contains("next direct call")), "main also uses first batch snapshot");
        }
    }

    @Test void driverAndToolsNeverBecomeHumanEvidence() {
        try (Fixture f = new Fixture(true)) {
            f.ai.startReplyService();
            f.annotator.output = messages -> routedPlan(messages, false);
            f.ai.driveMessage(f.sessionId, new MFChain("driver"));
            f.time.advance(5_000);
            check(f.provider.calls.size() == 1 && f.topic().unaddressedMessages == 0 && f.topic().lastAddressedAt == 0,
                    "driver must reply but does not reset human lifetime");
            String driverDirectory = newInputDirectory(f.annotator.calls.get(0));
            check(driverDirectory.startsWith("来源=driver；时间=") && !driverDirectory.contains("消息ID="),
                    "driver has provenance without a fabricated message ID");
            f.addPendingTool("work", 700_000);
            f.ai.armAsyncWait(f.topic());
            send(f, "side conversation while working");
            f.time.advance(5_000);
            check(f.annotator.calls.size() == 2 && f.provider.calls.size() == 1, "leading is not blocked by pending tools");
            f.time.advance(AIChat.TOPIC_IDLE_MILLIS - 10_000);
            check(f.topic() != null, "expiry protects unfinished tool work");
            check(f.ai.deliverAsyncToolResult(f.sessionId, "work", "done"), "accept pending result");
            f.time.advance(5_000);
            check(f.provider.calls.size() == 2 && f.ai.activatingTopics.isEmpty(), "tool continuation replies then completes overdue closure");
            check(newHumanIds(f.annotator.calls.get(2)).isEmpty(), "tool result cannot be classified as human evidence");
            String toolDirectory = newInputDirectory(f.annotator.calls.get(2));
            check(toolDirectory.contains("来源=tool；工具调用ID=work；时间=") && !toolDirectory.contains("消息ID="),
                    "tool result has a call ID without a fabricated message ID");
        }
    }

    @Test void driverOnlyModelCannotWriteUserFacts() {
        try (Fixture f = new Fixture(false)) {
            f.ai.startReplyService();
            FakeMemory store = new FakeMemory();
            f.ai.memory = store;
            f.annotator.output = messages -> withAddition(routedPlan(messages, false), "invented user fact");
            f.ai.driveMessage(f.sessionId, new MFChain("internal result"));
            f.time.advance(5_000);
            check(store.items.isEmpty() && f.provider.calls.size() == 1, "reject writes without real human evidence but keep driver reply");
        }
    }

    @Test void closingDuringLeadingDiscardsWritesAndReply() {
        try (Fixture f = new Fixture(false)) {
            f.ai.startReplyService();
            FakeMemory store = new FakeMemory();
            f.ai.memory = store;
            f.annotator.output = messages -> withAddition(routedPlan(messages, true), "late write");
            f.annotator.onCall = ignored -> f.ai.closeTopic(f.sessionId);
            send(f, "question");
            f.time.advance(5_000);
            check(store.items.isEmpty() && f.provider.calls.isEmpty() && f.ai.activatingTopics.isEmpty(),
                    "late leading output cannot write memory or reply after close");
        }
    }

    @Test void postUsesSameCoreWithoutChatOrReply() throws Exception {
        try (Fixture f = new Fixture(false)) {
            FakeMemory store = new FakeMemory();
            f.ai.memory = store;
            f.annotator.output = messages -> withAddition(routedPlan(messages, false), "post preference");
            MCPost post = new MCPost();
            post.sessionId = f.sessionId;
            post.poster = f.user("poster").sender;
            post.timestamp = 4567;
            post.messageFields.add(new TextField("I prefer jazz"));
            f.ai.leadPost(f.config, post);
            check(store.items.size() == 1 && f.ai.activatingTopics.isEmpty() && f.provider.calls.isEmpty(), "post uses shared leading memory path only");
            check(lastPrompt(f).contains("时间=4567") && lastPrompt(f).contains("Test:user"), "post provenance survives");
        }
    }

    @Test void mainStillReceivesTrimmedBatchInOrder() {
        try (Fixture f = new Fixture(false)) {
            f.ai.startReplyService();
            f.config.maxTokens = 10;
            send(f, "first request");
            send(f, "second request");
            f.time.advance(5_000);
            List<Message> main = f.provider.calls.get(0);
            int first = -1, second = -1;
            for (int i = 0; i < main.size(); i++) {
                if (main.get(i).toString().contains("first request")) first = i;
                if (main.get(i).toString().contains("second request")) second = i;
            }
            check(first >= 0 && second > first, "pending batch survives main snapshot trimming in arrival order");
        }
    }

    @Test void cancelledToolTimerCannotShortenNewHumanDebounce() {
        try (Fixture f = new Fixture(true)) {
            f.ai.startReplyService();
            f.ai.driveMessage(f.sessionId, new MFChain("create"));
            f.time.advance(5_000);
            f.addPendingTool("old", 1_000);
            f.ai.armAsyncWait(f.topic());
            Runnable stale = f.time.timers.peek().action;
            f.ai.deliverAsyncToolResult(f.sessionId, "old", "done");
            f.time.advance(5_000);
            send(f, "new human");
            f.time.drainWorkers();
            stale.run();
            f.time.drainWorkers();
            check(f.annotator.calls.size() == 2, "cancelled async timer cannot start a new batch early");
            f.time.advance(5_000);
            check(f.annotator.calls.size() == 3, "new human keeps full five-second debounce");
        }
    }

    @Test void ragCompletesOnceAndInjectsWithoutPlaceholder() {
        try (Fixture f = new Fixture(false)) {
            FakeMemory store = new FakeMemory();
            Memory.Item hit = store.seed("retrieved preference");
            store.queryHits = List.of(hit, hit);
            f.ai.memory = store;
            f.ai.startReplyService();
            String embeddingId = f.providerId + "-embedding";
            com.nekoyu.Universe.API.Providers.LLMProvider.Embedding embedding = request -> {
                var response = new com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.EmbeddingResponse();
                for (int i = 0; i < request.message.size(); i++) {
                    var data = new com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.EmbeddingResponse.Embedding();
                    data.index = i;
                    data.embedding = new double[]{1, 0};
                    response.data.add(data);
                }
                return response;
            };
            com.nekoyu.Universe.Universe.Providers.put(embeddingId, embedding);
            f.ai.globalCfg.Annotator.embeddingProvider = embeddingId;
            List<String> knowledgeQueries = new ArrayList<>();
            f.ai.leadingKnowledge = (question, limit) -> {
                knowledgeQueries.add(question);
                KnowledgeStore.KnowledgeResult result = new KnowledgeStore.KnowledgeResult();
                result.documentId = 7;
                result.title = "reference title";
                result.url = "https://example.invalid/source";
                result.content = "x".repeat(1_000);
                result.bestScore = 0.9f;
                result.bestQuery = question;
                result.bestConclusion = "retrieved conclusion";
                return List.of(result, result);
            };
            f.annotator.output = messages -> {
                JsonObject result = JsonParser.parseString(routedPlan(messages, true)).getAsJsonObject();
                JsonObject query = new JsonObject();
                query.addProperty("question", "user preference?");
                query.addProperty("location_id", "Test:user");
                result.getAsJsonArray("memory_queries").add(query);
                result.getAsJsonArray("knowledge_queries").add("technical question?");
                return result.toString();
            };
            try {
                send(f, "question");
                f.time.advance(5_000);
                check(f.annotator.calls.size() == 1 && store.queryCount == 1 && knowledgeQueries.size() == 1,
                        "leading actually queries both stores once");
                String annotation = f.provider.calls.get(0).stream().filter(m -> "system".equals(m.role)
                        && m.toString().contains("本轮预检索")).findFirst().orElseThrow().toString();
                check(annotation.contains("retrieved preference") && annotation.contains("retrieved conclusion"),
                        "main receives both results without LEADING placeholder");
                check(annotation.indexOf("reference title") == annotation.lastIndexOf("reference title")
                        && !annotation.contains("x".repeat(801)), "deduplicate documents and bound source summary");
                f.annotator.output = messages -> {
                    JsonObject result = JsonParser.parseString(routedPlan(messages, false)).getAsJsonObject();
                    result.getAsJsonArray("knowledge_queries").add("unnecessary query");
                    return result.toString();
                };
                send(f, "side discussion");
                f.time.advance(5_000);
                check(knowledgeQueries.size() == 1 && store.queryCount == 1, "side conversation does not perform RAG");
            } finally {
                com.nekoyu.Universe.Universe.Providers.remove(embeddingId);
            }
        }
    }

    private static void send(Fixture f, String text) { f.ai.handleSessionMessage(f.config, f.provider, f.user(text)); }
    private static String lastPrompt(Fixture f) {
        List<Message> messages = f.annotator.calls.get(f.annotator.calls.size() - 1);
        return messages.get(messages.size() - 1).toString();
    }
    private static JsonObject plan(boolean reply) {
        JsonObject plan = JsonParser.parseString(LeadingPlanTest.emptyPlan()).getAsJsonObject();
        plan.addProperty("should_reply", reply);
        return plan;
    }
    private static JsonObject addition(String content) {
        JsonObject item = new JsonObject();
        item.addProperty("location_id", "Test:user");
        item.addProperty("content", content);
        item.addProperty("confidence", 0.9);
        item.addProperty("importance", 0.8);
        return item;
    }
    private static String withAddition(String json, String content) {
        JsonObject plan = JsonParser.parseString(json).getAsJsonObject();
        plan.getAsJsonArray("add").add(addition(content));
        return plan.toString();
    }
    private static String updatePlan(String content) {
        JsonObject plan = plan(false);
        JsonObject update = addition(content);
        update.remove("location_id");
        update.addProperty("id", 1);
        plan.getAsJsonArray("update").add(update);
        return plan.toString();
    }

    static final class FakeMemory implements MemoryStore {
        final Map<UUID, Memory.Item> items = new LinkedHashMap<>();
        final Map<String, Integer> failures = new HashMap<>();
        final Map<String, List<UUID>> attempts = new HashMap<>();
        final Map<UUID, Integer> deleteAttempts = new HashMap<>();
        final Map<String, double[]> embeddingVectors = new HashMap<>();
        List<Memory.Item> queryHits = List.of();
        int queryCount;
        boolean hideCandidates;
        boolean queryFailure;
        @Override public double[] embedding(String text) {
            double[] specified = embeddingVectors.get(text);
            if (specified != null) return specified;
            Random random = new Random(text.hashCode());
            double[] vector = new double[32];
            for (int i = 0; i < vector.length; i++) vector[i] = random.nextDouble() * 2 - 1;
            return vector;
        }
        Memory.Item seed(String content) {
            Memory.Item item = new Memory.Item();
            item.id = UUID.randomUUID();
            item.locationId = "Test:user";
            item.content = content;
            item.confidence = 0.9f;
            item.importance = 0.8f;
            items.put(item.id, item);
            return item;
        }
        private void attempt(Memory.Item item) {
            attempts.computeIfAbsent(item.content, ignored -> new ArrayList<>()).add(item.id);
            int remaining = failures.getOrDefault(item.content, 0);
            if (remaining > 0) {
                failures.put(item.content, remaining - 1);
                throw new IllegalStateException("fake storage failure");
            }
            items.put(item.id, item);
        }
        @Override public List<Memory.Item> query(List<Float> vector, List<String> locations) {
            queryCount++;
            if (queryFailure) throw new IllegalStateException("fake query failure");
            return queryHits;
        }
        @Override public List<Memory.Item> getLastMemoryItems(List<String> locations, int limit) {
            return hideCandidates ? List.of() : new ArrayList<>(items.values());
        }
        @Override public List<Memory.Item> getHighImportanceItems(List<String> locations, int limit, float importance) { return List.of(); }
        @Override public void insert(List<Memory.Item> additions) { additions.forEach(this::attempt); }
        @Override public void update(UUID id, float confidence, float importance, String content) {
            Memory.Item original = items.get(id);
            Memory.Item changed = new Memory.Item();
            changed.id = id;
            changed.locationId = original.locationId;
            changed.content = content;
            changed.confidence = confidence;
            changed.importance = importance;
            attempt(changed);
        }
        @Override public void delete(UUID id) {
            deleteAttempts.merge(id, 1, Integer::sum);
            items.remove(id);
        }
    }
}

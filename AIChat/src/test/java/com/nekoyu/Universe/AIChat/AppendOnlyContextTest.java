package com.nekoyu.Universe.AIChat;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.nekoyu.Universe.AIChat.Skill.Skill;
import com.nekoyu.Universe.AIChat.Skill.SkillRegistry;
import com.nekoyu.Universe.AIChat.knowledge.KnowledgeStore;
import com.nekoyu.Universe.AIChat.leading.MemoryStore;
import com.nekoyu.Universe.API.MessageChannel.MCMessage;
import com.nekoyu.Universe.API.Providers.LLMProvider.LLMProvider;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.*;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.CompletionsResponse;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AppendOnlyContextTest {
    private static final Gson JSON = new Gson();

    @Test void cutoffFiltersOnlyOldConversationAndKeepsReferenceOrder() {
        ChatContext context = new ChatContext();
        for (int i = 0; i < 105; i++) {
            if (i == 2 || i == 20 || i == 40) context.append(ChatContext.retainedMessage(
                    i == 2 ? "skill" : i == 20 ? "knowledge" : "memory", "entry-" + i));
            else context.userMsg(new Message("entry-" + i));
        }
        List<String> original = texts(context.getMessageList());
        assertEquals(0, context.getHistoryStart());
        context.setHistoryStart(50);
        List<String> expected = new ArrayList<>(List.of("entry-2", "entry-20", "entry-40"));
        expected.addAll(original.subList(50, 105));
        assertEquals(expected, texts(context.getMessageList()));
        assertEquals(105, context.getBase().size(), "truncation does not delete queue entries");
        context.userMsg(new Message("new input"));
        assertEquals(expected, texts(context.getMessageList()).subList(0, expected.size()));
        context.setHistoryStart(100);
        assertEquals(List.of("entry-2", "entry-20", "entry-40", "entry-100", "entry-101",
                "entry-102", "entry-103", "entry-104", "new input"), texts(context.getMessageList()));
        assertThrows(IllegalArgumentException.class, () -> context.setHistoryStart(0));
    }

    @Test void budgetAdvancesCursorAtInferenceAndCountsRetainedAndTailContent() {
        ChatContext context = new ChatContext();
        context.setInputBudget(65);
        context.append(ChatContext.retainedMessage("knowledge", "k".repeat(20))); // 14
        for (int i = 0; i < 5; i++) context.userMsg(new Message("u".repeat(20) + i)); // 14 each
        context.getTail().add(ChatContext.retainedMessage("tail", "t".repeat(20))); // 14
        assertEquals(0, context.getHistoryStart(), "appending does not trim");
        assertEquals(4, context.getMessageList().size(), "one retained entry, two recent inputs, tail");
        assertEquals(4, context.getHistoryStart());
        assertEquals(6, context.getBase().size());
        context.setInputBudget(100_000);
        context.getMessageList();
        assertEquals(4, context.getHistoryStart(), "older history never reappears automatically");
    }

    @Test void retainedContentAndCurrentInputSurviveEvenWhenTheyExceedBudget() {
        ChatContext context = new ChatContext();
        context.setInputBudget(1);
        context.userMsg(new Message("old"));
        context.append(ChatContext.retainedMessage("memory", "large memory".repeat(100)));
        context.userMsg(new Message("current"));
        assertEquals(List.of("large memory".repeat(100), "current"), texts(context.getMessageList()));
        assertEquals(3, context.getBase().size());
    }

    @Test void cutoffNeverSeparatesNativeToolCallsFromTheirResults() {
        ChatContext context = new ChatContext();
        context.userMsg(new Message("old"));
        Message assistant = new Message();
        assistant.tool_calls = new Tool_call[]{call("a", "one"), call("b", "two")};
        context.assistantMsg(assistant);
        context.toolMsg(result("a", "first"));
        context.toolMsg(result("b", "second"));
        context.userMsg(new Message("recent"));
        context.setHistoryStart(3);
        assertEquals(1, context.getHistoryStart(), "cut inside tool block includes its assistant anchor");
        assertEquals(List.of("assistant", "tool", "tool", "user"),
                context.getMessageList().stream().map(m -> m.role).toList());
        context.protectAdditionalMessages(List.of(context.getBase().get(2)));
        context.setHistoryStart(4);
        assertEquals(List.of("assistant", "tool", "tool", "user"),
                context.getMessageList().stream().map(m -> m.role).toList(), "protection covers the whole block");
    }

    @Test void protectedCurrentBatchDoesNotForceAnExtraOldMessageIntoAnOverfullRequest() {
        ChatContext context = new ChatContext();
        context.setInputBudget(1);
        context.userMsg(new Message("old history"));
        context.userMsg(new Message("current batch"));
        context.protectAdditionalMessages(List.of(context.getBase().get(1)));
        assertEquals(List.of("current batch"), texts(context.getMessageList()));
        assertEquals(1, context.getHistoryStart());
        assertEquals(2, context.getBase().size());
    }

    @Test void skillAndKnowledgeArrivingDuringToolsAppendAfterCompleteBlock() {
        ChatContext context = new ChatContext();
        context.userMsg(new Message("question"));
        context.getTail().add(ChatContext.retainedMessage("tail", "fixed tail"));
        Message assistant = new Message();
        assistant.tool_calls = new Tool_call[]{call("a", "activate"), call("b", "lookup")};
        context.assistantMsg(assistant);
        List<String> existing = serialized(context.getMessageList());
        assistant.tool_calls[0].function.arguments = "mutated by executor";
        context.append(ChatContext.retainedMessage("skill", "skill rules"));
        context.toolMsg(result("a", "activated"));
        context.append(ChatContext.retainedMessage("knowledge", "new knowledge"));
        context.toolMsg(result("b", "looked up"));
        List<Message> request = context.getMessageList();
        assertEquals(existing.subList(0, 2), serialized(request).subList(0, 2), "existing messages remain byte-identical");
        assertEquals(List.of("user", "assistant", "tool", "tool", "system", "system", "system"),
                request.stream().map(m -> m.role).toList());
        assertEquals(List.of("skill rules", "new knowledge", "fixed tail"), texts(request).subList(4, 7));
    }

    @Test void referencesDeduplicateScoresAndAppendRevisionsWithoutRewritingOldVersions() {
        Topic topic = new Topic(new SessionConfig());
        Memory.Item memory = RetrievalSelectionTest.memory(1, 0.9f);
        int number = topic.rememberMemory(memory);
        List<String> before = serialized(topic.getChatContext().getMessageList());
        memory.score = 0.7f;
        assertEquals(number, topic.rememberMemory(memory));
        assertEquals(before, serialized(topic.getChatContext().getMessageList()));
        memory.content = "corrected preference";
        assertEquals(number, topic.rememberMemory(memory));
        topic.forgetMemory(memory.id);
        assertEquals(3, topic.getMessageCount());
        assertEquals(before.get(0), serialized(topic.getChatContext().getMessageList()).get(0));
        assertTrue(topic.getMessage(2).solveAll().contains("已删除/撤回"));
        topic.getChatContext().setHistoryStart(3);
        assertEquals(3, topic.getChatContext().getMessageList().size());

        var fact = RetrievalSelectionTest.fact("question", "conclusion", 0.99f);
        var doc = RetrievalSelectionTest.document(5, fact);
        topic.rememberKnowledge(doc, List.of(fact));
        int count = topic.getMessageCount();
        topic.rememberKnowledge(doc, List.of(RetrievalSelectionTest.fact("question", "conclusion", 0.61f)));
        assertEquals(count, topic.getMessageCount(), "retrieval scores do not create new knowledge versions");
    }

    @Test void mainTemplatesRemainStableAndDynamicValuesStayAtTail() {
        Topic topic = new Topic(new SessionConfig());
        topic.initSystemPrompts("Rules; time=%TIME%; %MEMORY% %LEADING%", "Session rules", "last=%TIME%", "global last");
        topic.getChatContext().userMsg(new Message("first question"));
        topic.rememberMemory(RetrievalSelectionTest.memory(1, 0.9f));
        ChatContext first = topic.getOpenAIContext(null, Map.of("TIME", "time-one", "LEADING", "first status"));
        List<String> prefix = serialized(first.getMessageList()).subList(0, topic.getMessageCount());
        first.assistantMsg(new Message("answer"));
        topic.finishTurn(first);
        topic.getChatContext().userMsg(new Message("next question"));
        ChatContext next = topic.getOpenAIContext(null, Map.of("TIME", "time-two", "LEADING", "second status"));
        assertEquals(prefix, serialized(next.getMessageList()).subList(0, prefix.size()));
        assertTrue(texts(next.getMessageList()).get(next.getMessageList().size() - 1).contains("TIME=time-two"));
        assertEquals("global last", texts(next.getMessageList()).get(next.getMessageList().size() - 2));
        assertFalse(texts(topic.createChatContextCopy().getMessageList()).stream().anyMatch(t -> t.contains("Session rules")),
                "main persona does not become a leading instruction");
    }

    @Test void asyncFeedbackAppendsWithoutChangingCompletedNativeToolBlock() {
        Topic topic = new Topic(new SessionConfig());
        ChatContext context = topic.getChatContext();
        Message assistant = new Message();
        assistant.tool_calls = new Tool_call[]{call("async", "research")};
        context.assistantMsg(assistant);
        context.toolMsg(result("async", "started"));
        context.assistantMsg(new Message("waiting"));
        List<String> original = serialized(context.getMessageList());
        topic.registerPendingCall("async", 60_000);
        topic.addToolCallback("async", "progress", 1);
        topic.addAsyncInput("async", "final result", 2);
        List<Message> next = context.getMessageList();
        assertEquals(original, serialized(next).subList(0, original.size()));
        assertEquals("user", next.get(3).role);
        assertTrue(next.get(3).toString().contains("工具调用ID=async"));
        assertNull(next.get(3).tool_call_id);
        assertEquals(Set.of("async"), topic.respondedCallsIn(context));
        assertTrue(topic.peekInputs().stream().noneMatch(Topic.Input::human));
    }

    @Test void leadingAndMainKeepKnowledgeAndMemoryAcrossTurnsAndDeduplicateRepeatedHits() {
        try (DriveMessageTest.Fixture fixture = new DriveMessageTest.Fixture(false)) {
            fixture.ai.memory = new FakeMemory();
            var fact = RetrievalSelectionTest.fact("known question", "known conclusion", 0.9f);
            fixture.ai.leadingKnowledge = (question, limit) -> List.of(RetrievalSelectionTest.document(7, fact));
            fixture.annotator.output = messages -> {
                var plan = JsonParser.parseString(DriveMessageTest.routedPlan(messages, true)).getAsJsonObject();
                plan.getAsJsonArray("knowledge_queries").add("research");
                return plan.toString();
            };
            fixture.ai.startReplyService();
            fixture.ai.handleSessionMessage(fixture.config, fixture.provider, fixture.user("first"));
            fixture.time.advance(5_000);
            List<String> firstMain = serialized(fixture.provider.calls.get(0));
            List<String> firstLeading = serialized(fixture.annotator.calls.get(0));
            int before = fixture.topic().getMessageCount();
            fixture.ai.handleSessionMessage(fixture.config, fixture.provider, fixture.user("second"));
            fixture.time.advance(5_000);
            assertEquals(firstMain.subList(0, firstMain.size() - 1),
                    serialized(fixture.provider.calls.get(1)).subList(0, firstMain.size() - 1));
            assertEquals(firstLeading.subList(0, firstLeading.size() - 1),
                    serialized(fixture.annotator.calls.get(1)).subList(0, firstLeading.size() - 1));
            assertEquals(before + 2, fixture.topic().getMessageCount(), "only new user input and assistant answer append");
            assertTrue(texts(fixture.annotator.calls.get(1)).stream().anyMatch(t -> t.contains("known conclusion")));
            assertTrue(texts(fixture.provider.calls.get(1)).stream().anyMatch(t -> t.contains("memory-1")));
        }
    }

    @Test void skillActivationPersistsInToolOrderAndSurvivesFollowingMainTurn() {
        Skill skill = new Skill();
        skill.id = "append-only-test-skill";
        skill.description = "test activation";
        skill.systemPrompt = "Distinctive skill instructions; current time=%TIME%";
        SkillRegistry.register(skill);
        try (DriveMessageTest.Fixture fixture = new DriveMessageTest.Fixture(false)) {
            fixture.config.availableSkills = new String[]{skill.id};
            fixture.ai.startReplyService();
            fixture.ai.handleSessionMessage(fixture.config, fixture.provider, fixture.user("activate"));
            AtomicInteger round = new AtomicInteger();
            List<List<Message>> requests = new ArrayList<>();
            fixture.topic().provider = new LLMProvider() {
                @Override public CompletionsResponse completions(String model, List<Message> messages, List<LLMFunction> tools,
                                                                 CompletionsRequest options, BufferCallback callback) {
                    requests.add(new ArrayList<>(messages));
                    if (round.getAndIncrement() == 0) {
                        String name = tools.stream().filter(t -> "ManageSkills".equals(t.name))
                                .findFirst().orElseThrow().name;
                        CompletionsResponse response = response("tool_calls");
                        Tool_call call = call("activate-call", name);
                        call.function.arguments = "{\"skillId\":\"" + skill.id + "\"}";
                        response.choices[0].message.tool_calls = new Tool_call[]{call};
                        return response;
                    }
                    return response("stop");
                }
            };
            fixture.time.advance(5_000);
            assertEquals(2, requests.size());
            List<Message> second = requests.get(1);
            int toolIndex = indexOfRole(second, "tool");
            assertTrue(second.get(toolIndex + 1).toString().contains("Distinctive skill instructions"));
            fixture.ai.handleSessionMessage(fixture.config, fixture.topic().provider, fixture.user("continue"));
            fixture.time.advance(5_000);
            assertEquals(serialized(second).subList(0, second.size() - 1),
                    serialized(requests.get(2)).subList(0, second.size() - 1));
            assertEquals(1, fixture.topic().getChatContext().getBase().stream()
                    .filter(m -> skill.id.equals(m.getMetainfo("skillId"))).count());
        } finally { SkillRegistry.getAll().remove(skill.id); }
    }

    private static final class FakeMemory implements MemoryStore {
        final Memory.Item item = RetrievalSelectionTest.memory(1, 0.9f);
        public double[] embedding(String text) { return new double[]{1}; }
        public List<Memory.Item> query(List<Float> vector, List<String> locations) { return List.of(item); }
        public List<Memory.Item> getLastMemoryItems(List<String> locations, int limit) { return List.of(item); }
        public List<Memory.Item> getHighImportanceItems(List<String> locations, int limit, float importance) { return List.of(item); }
        public void insert(List<Memory.Item> items) { fail("not expected"); }
        public void update(UUID id, float confidence, float importance, String content) { fail("not expected"); }
        public void delete(UUID id) { fail("not expected"); }
    }

    private static int indexOfRole(List<Message> messages, String role) {
        for (int i = 0; i < messages.size(); i++) if (role.equals(messages.get(i).role)) return i;
        throw new AssertionError("Missing " + role);
    }
    private static Tool_call call(String id, String name) {
        Tool_call call = new Tool_call();
        call.id = id; call.type = "function"; call.function.name = name; call.function.arguments = "{}";
        return call;
    }
    private static Message result(String id, String content) {
        Message message = new Message(content); message.tool_call_id = id; return message;
    }
    private static CompletionsResponse response(String finish) {
        CompletionsResponse response = new CompletionsResponse();
        response.choices = new CompletionsResponse.Choice[]{new CompletionsResponse.Choice()};
        response.choices[0].finish_reason = finish;
        response.choices[0].message.content = "answer";
        return response;
    }
    private static List<String> serialized(List<Message> messages) { return messages.stream().map(JSON::toJson).toList(); }
    private static List<String> texts(List<Message> messages) { return messages.stream().map(Message::toString).toList(); }
}

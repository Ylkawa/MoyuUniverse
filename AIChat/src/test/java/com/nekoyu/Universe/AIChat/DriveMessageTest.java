package com.nekoyu.Universe.AIChat;

import com.nekoyu.Universe.AIChat.leading.LeadingPlanTest;

import com.nekoyu.Universe.API.MessageChannel.*;
import com.nekoyu.Universe.API.MessageChannel.Features.SessionChat;
import com.nekoyu.Universe.API.MessageChannel.MessageField.ImageField;
import com.nekoyu.Universe.API.Providers.LLMProvider.LLMProvider;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.*;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ContentPiece.ImageUrlPiece;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.CompletionsResponse;
import com.nekoyu.Universe.Universe;

import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.function.Consumer;

import static com.nekoyu.Universe.AIChat.SessionReplyQueueTest.check;

/** 真正调用 API、模型循环及消息通道，模型、通道和时钟均为离线替身。 */
public class DriveMessageTest {
    public static void main(String[] args) throws Exception {
        validationAndCreation();
        roleImagesAndCopy();
        xmlStreaming();
        ordinaryUserAndMerge();
        duringInference(false);
        duringInference(true);
        pendingToolsAndDelivery();
        resultDuringInference();
        toolTimeout();
        chatterDoesNotClosePendingBatch();
        inferenceFailure();
        closedAndStopped();
        System.out.println("DriveMessageTest passed");
    }

    private static void validationAndCreation() {
        try (Fixture f = new Fixture(false)) {
            expect(IllegalStateException.class, () -> f.ai.driveMessage(f.sessionId, new MFChain("before start")));
            f.ai.startReplyService();
            expect(IllegalArgumentException.class, () -> f.ai.driveMessage(null, new MFChain("x")));
            expect(IllegalArgumentException.class, () -> f.ai.driveMessage("invalid", new MFChain("x")));
            expect(IllegalArgumentException.class, () -> f.ai.driveMessage(f.sessionId, null));
            expect(IllegalArgumentException.class, () -> f.ai.driveMessage("unknown:session", new MFChain("x")));
            f.ai.driveMessage(f.sessionId, new MFChain("   "));
            f.time.advance(20_000);
            check(f.ai.activatingTopics.isEmpty() && f.provider.calls.isEmpty(), "empty input does not create or infer");
            f.config.SessionId = f.channel.ID + ":.*";
            f.ai.driveMessage(f.sessionId, new MFChain("no user trigger required"));
            check(f.ai.activatingTopics.size() == 1 && f.topic().lastTrigger == null, "create context from matching config");
            f.time.advance(4_999);
            check(f.provider.calls.isEmpty(), "API does not call the model synchronously");
            f.time.advance(1);
            check(f.channel.sent.equals(List.of("room:answer 1")), "send to actual session instead of config regex");
            check(f.provider.lastOptions.placeholders.get("_SessionId").equals(f.sessionId), "bind tools to actual session");
        }
    }

    private static void roleImagesAndCopy() throws Exception {
        try (Fixture f = new Fixture(false)) {
            f.ai.startReplyService();
            f.config.nativeImage = true;
            ImageField image = new ImageField(new URL("https://example.invalid/driver.png")) {
                @Override public String solveMetadata() { return "fake EXIF"; }
            };
            MFChain input = new MFChain("original");
            input.add(image);
            AIChatPlugin plugin = new AIChatPlugin(f.ai) {
                @Override public void onEnable() {}
                @Override public void onDisable() {}
            };
            plugin.driveMessage(f.sessionId, input);
            input.clear();
            input.addAll(new MFChain("mutated"));
            f.time.advance(5_000);
            List<Message> messages = f.provider.calls.get(0);
            Message driver = messages.stream().filter(m -> "user".equals(m.role) && m.toString().contains("[驱动消息]")).findFirst().orElseThrow();
            check(driver.role.equals("user") && driver.tool_call_id == null, "driver is user input, not a tool result");
            check(driver.toString().contains("original") && !driver.toString().contains("mutated"), "snapshot MFChain container");
            check(driver.content.stream().anyMatch(p -> p instanceof ImageUrlPiece), "preserve native image conversion");
            check(driver.toString().contains("<ReferenceId=") && driver.toString().contains("fake EXIF"), "reuse image references and metadata");
            check(messages.stream().anyMatch(m -> "system".equals(m.role) && m.toString().contains("来自外部模块")),
                    "explain driver origin in stable system prompt");
            MCMessage stored = f.topic().getChatContext().getBase().stream()
                    .filter(m -> Boolean.TRUE.equals(m.getMetainfo("driver"))).findFirst().orElseThrow();
            check(stored.retainedFiles.contains(image), "retain files in persistent context");
            check(!driver.toString().contains("bot("), "do not add a fake human nickname");
            check(!f.topic().getLocationIds().contains(null), "internal input does not add a null user location");
        }
    }

    private static void ordinaryUserAndMerge() {
        try (Fixture f = new Fixture(false)) {
            f.ai.startReplyService();
            f.ai.handleSessionMessage(f.config, f.provider, f.user("ordinary"));
            f.time.drainWorkers();
            check(f.provider.calls.isEmpty(), "every activates but ordinary input waits five seconds");
            f.time.advance(5_000);
            check(f.provider.calls.size() == 1, "ordinary user routes through leading after debounce");
            f.ai.driveMessage(f.sessionId, new MFChain("driver"));
            f.time.advance(4_000);
            f.ai.handleSessionMessage(f.config, f.provider, f.user("merged"));
            f.time.advance(4_999);
            check(f.provider.calls.size() == 1, "merged user resets driver debounce");
            f.time.advance(1);
            List<Message> snapshot = f.provider.calls.get(1);
            check(snapshot.stream().filter(m -> m.toString().contains("merged")).count() == 1, "user enters history only once");
            check(snapshot.stream().anyMatch(m -> m.toString().contains("[驱动消息]\ndriver")), "user and driver share a reply");
            f.time.advance(10_000);
            check(f.provider.calls.size() == 2, "no extra inference after merged batch");
        }
        try (Fixture f = new Fixture(true)) {
            f.ai.startReplyService();
            f.ai.handleSessionMessage(f.config, f.provider, f.user("async ordinary"));
            f.time.advance(4_999);
            check(f.provider.calls.isEmpty(), "ordinary async user keeps original debounce");
            f.time.advance(1);
            check(f.provider.calls.size() == 1, "ordinary async user replies");
            f.ai.driveMessage(f.sessionId, new MFChain("async driver"));
            f.time.advance(4_000);
            f.ai.handleSessionMessage(f.config, f.provider, f.user("async merged"));
            f.time.advance(4_999);
            check(f.provider.calls.size() == 1, "async user also resets driver deadline");
            f.time.advance(1);
            f.time.advance(10_000);
            check(f.provider.calls.size() == 2, "driver and async user have exactly one combined reply");
        }
    }

    /** 驱动回复保留 XML 分段规则，完整配对元素到齐后才交给 decoupler。 */
    private static void xmlStreaming() {
        MarkDecoupler previous = AIChat.markDecouplers.get("drive_xml");
        try (Fixture f = new Fixture(false)) {
            AIChat.registerMarkDecoupler("drive_xml", MarkDecoupler.Form.PAIRED,
                    element -> new MFChain("图表:" + element.getTextContent()));
            f.ai.startReplyService();
            f.provider.callbackChunks = List.of("前文\n\n<drive_xml>first\n\n", "second</drive_xml>\n\n尾文");
            f.ai.driveMessage(f.sessionId, new MFChain("render graph"));
            f.time.advance(5_000);
            check(f.channel.sent.equals(List.of("room:前文", "room:图表:first\n\nsecond", "room:尾文")),
                    "driver reply keeps complete XML elements across streaming chunks");
        } finally {
            if (previous == null) AIChat.markDecouplers.remove("drive_xml");
            else AIChat.markDecouplers.put("drive_xml", previous);
        }
    }

    private static void duringInference(boolean expired) {
        try (Fixture f = new Fixture(false)) {
            f.ai.startReplyService();
            f.provider.onCall = messages -> {
                if (f.provider.calls.size() == 1) {
                    f.ai.driveMessage(f.sessionId, new MFChain("next input"));
                    f.time.advance(expired ? 6_000 : 2_000);
                    check(messages.stream().noneMatch(m -> m.toString().contains("next input")), "current snapshot remains unchanged");
                    check(f.topic().getChatContext().getBase().stream().noneMatch(m -> m.solveAll().contains("next input")),
                            "driver stays queued until inference finishes");
                }
            };
            f.ai.driveMessage(f.sessionId, new MFChain("first"));
            f.time.advance(5_000);
            if (!expired) {
                check(f.provider.calls.size() == 1, "wait the remaining three seconds");
                f.time.advance(3_000);
            }
            check(f.provider.calls.size() == 2, "resume after current reply and debounce");
            List<Message> second = f.provider.calls.get(1);
            int answer = indexContaining(second, "answer 1");
            int driver = indexContaining(second, "next input");
            check(answer >= 0 && driver > answer, "persist first assistant reply before queued driver input");
        }
    }

    private static void pendingToolsAndDelivery() {
        try (Fixture f = new Fixture(true)) {
            f.ai.startReplyService();
            f.ai.driveMessage(f.sessionId, new MFChain("create"));
            f.time.advance(5_000);
            f.addPendingTool("background", 600_000);
            f.ai.driveMessage(f.sessionId, new MFChain("reply while background is running"));
            f.time.schedule(() -> f.topic().replies.requestReply(), 1_000, java.util.concurrent.TimeUnit.MILLISECONDS);
            f.time.advance(1_000);
            check(f.provider.calls.size() == 1, "async wake does not shorten driver debounce");
            f.time.advance(4_000);
            check(f.provider.calls.size() == 2 && f.topic().hasPendingCalls(), "driver bypasses tool waiting without removing record");
            AIChat.instance = f.ai;
            check(AIChat.submitAsyncToolResult(f.sessionId, "background", "completed result"), "existing static API still delivers");
            check(!f.ai.deliverAsyncToolResult(f.sessionId, "background", "duplicate"), "reject duplicate completion");
            f.time.advance(5_000);
            check(f.provider.calls.size() == 3 && !f.topic().hasPendingCalls(), "consume completed tool only after model sees result");
            check(f.provider.calls.get(2).stream().anyMatch(m -> m.toString().contains("completed result")), "tool result reaches model");
        }
    }

    private static void resultDuringInference() {
        try (Fixture f = new Fixture(true)) {
            f.ai.startReplyService();
            f.ai.driveMessage(f.sessionId, new MFChain("create"));
            f.time.advance(5_000);
            f.addPendingTool("late", 600_000);
            f.provider.onCall = messages -> {
                if (f.provider.calls.size() == 2)
                    check(f.ai.deliverAsyncToolResult(f.sessionId, "late", "late result"), "result arrives during inference");
            };
            f.ai.driveMessage(f.sessionId, new MFChain("another input"));
            f.time.advance(5_000);
            check(f.topic().hasPendingCalls(), "do not consume result absent from the inference snapshot");
            f.time.advance(5_000);
            check(f.provider.calls.size() == 3 && !f.topic().hasPendingCalls(), "late result gets a subsequent reply");
        }
    }

    private static void toolTimeout() {
        try (Fixture f = new Fixture(true)) {
            f.ai.startReplyService();
            f.ai.driveMessage(f.sessionId, new MFChain("create"));
            f.time.advance(5_000);
            f.addPendingTool("expired", 0);
            f.ai.armAsyncWait(f.topic());
            f.time.advance(0);
            check(f.provider.calls.size() == 2 && !f.topic().hasPendingCalls(), "timeout still triggers fallback inference");
            check(f.provider.calls.get(1).stream().anyMatch(m -> m.toString().contains("已超时")), "fallback reaches model");
        }
    }

    private static void inferenceFailure() {
        try (Fixture f = new Fixture(false)) {
            f.ai.startReplyService();
            f.provider.fail = true;
            f.ai.driveMessage(f.sessionId, new MFChain("fail once"));
            f.time.advance(5_000);
            f.time.advance(20_000);
            check(f.provider.calls.size() == 1 && f.channel.sent.isEmpty(), "failed model request does not auto retry");
            f.provider.fail = false;
            f.ai.driveMessage(f.sessionId, new MFChain("recover"));
            f.time.advance(5_000);
            check(f.provider.calls.size() == 2 && f.channel.sent.size() == 1, "failure releases inference lock");
        }
    }

    private static void chatterDoesNotClosePendingBatch() {
        for (boolean async : List.of(false, true)) {
            try (Fixture f = new Fixture(async)) {
                f.ai.startReplyService();
                f.config.Trigger = "keyword";
                f.ai.driveMessage(f.sessionId, new MFChain("pending driver"));
                Topic original = f.topic();
                for (int i = 0; i < 30; i++)
                    f.ai.handleSessionMessage(f.config, f.provider, f.user("untriggered chatter " + i));
                check(f.topic() == original, "chatter cannot close a session with pending driver input");
                f.time.advance(5_000);
                check(f.provider.calls.size() == 1, "pending driver still receives a reply");
            }
        }
    }

    private static void closedAndStopped() {
        try (Fixture f = new Fixture(false)) {
            f.ai.startReplyService();
            f.ai.driveMessage(f.sessionId, new MFChain("close before deadline"));
            f.time.drainWorkers();
            f.ai.closeTopic(f.sessionId);
            f.time.advance(10_000);
            check(f.provider.calls.isEmpty(), "closed session cannot be revived by old timer");
            f.provider.onCall = ignored -> f.ai.closeTopic(f.sessionId);
            f.ai.driveMessage(f.sessionId, new MFChain("close while running"));
            f.time.advance(5_000);
            check(f.channel.sent.isEmpty(), "discard output after session closes");
            f.ai.driveMessage(f.sessionId, new MFChain("stop before deadline"));
            f.ai.stop();
            f.time.advance(10_000);
            check(f.provider.calls.size() == 1 && f.ai.activatingTopics.isEmpty(), "stop clears queued work");
            expect(IllegalStateException.class, () -> f.ai.driveMessage(f.sessionId, new MFChain("after stop")));
        }
    }

    private static int indexContaining(List<Message> messages, String text) {
        for (int i = 0; i < messages.size(); i++) if (messages.get(i).toString().contains(text)) return i;
        return -1;
    }

    private static void expect(Class<? extends RuntimeException> expected, Runnable task) {
        try { task.run(); } catch (RuntimeException failure) {
            check(expected.isInstance(failure), "unexpected failure: " + failure);
            return;
        }
        throw new AssertionError("expected " + expected.getSimpleName());
    }

    private static int fixtures;
    static final class Fixture implements AutoCloseable {
        final SessionReplyQueueTest.ManualTime time = new SessionReplyQueueTest.ManualTime();
        final FakeProvider provider = new FakeProvider();
        final FakeLeadingProvider annotator = new FakeLeadingProvider();
        final FakeChannel channel = new FakeChannel("drive-test-" + fixtures++);
        final String sessionId = channel.ID + ":room";
        final String providerId = channel.ID + "-model";
        final SessionConfig config = new SessionConfig();
        final AIChat ai = new AIChat(time, time, () -> time.now);

        Fixture(boolean async) {
            ai.globalCfg = new Config();
            ai.globalCfg.Annotator = new Config.Annotator();
            ai.globalCfg.Annotator.provider = providerId + "-leading";
            ai.globalCfg.Annotator.model = "fake-leading";
            config.SessionId = sessionId;
            config.Provider = providerId;
            config.Model = "fake-model";
            config.Trigger = "every";
            config.Keyword = "unused";
            config.ToolLoop.EnableAsyncTools = async;
            ai.configs.add(config);
            Universe.Providers.put(providerId, provider);
            Universe.Providers.put(providerId + "-leading", annotator);
            MessageChannelManager.registerChannel(channel.ID, channel);
        }
        Topic topic() { return ai.activatingTopics.get(sessionId); }
        MCMessage user(String content) {
            MCMessage message = new MCMessage();
            message.sessionId = sessionId;
            message.sender.setLocationId("Test:user");
            message.sender.setPlatform("Test");
            message.sender.setName("human");
            message.receiver = channel.loginAccount;
            message.messageFields = new MFChain(content);
            message.messageString = content;
            message.id = ++messageNumber;
            return message;
        }
        long messageNumber;
        void addPendingTool(String id, long timeout) {
            Message assistant = new Message();
            Tool_call call = new Tool_call();
            call.id = id;
            call.function.name = "background";
            call.function.arguments = "{}";
            assistant.tool_calls = new Tool_call[]{call};
            topic().getChatContext().assistantMsg(assistant);
            Message placeholder = new Message("submitted");
            placeholder.tool_call_id = id;
            topic().getChatContext().toolMsg(placeholder);
            topic().registerPendingCall(id, timeout);
        }
        @Override public void close() {
            ai.stop();
            Universe.Providers.remove(providerId);
            Universe.Providers.remove(providerId + "-leading");
            MessageChannelManager.MessageChannels.remove(channel.ID);
        }
    }

    static final class FakeProvider extends LLMProvider {
        final List<List<Message>> calls = new ArrayList<>();
        Consumer<List<Message>> onCall = ignored -> {};
        CompletionsRequest lastOptions;
        List<String> callbackChunks;
        boolean fail;
        @Override public CompletionsResponse completions(String model, List<Message> messages,
                List<LLMFunction> tools, CompletionsRequest options, BufferCallback callback) throws IOException {
            calls.add(new ArrayList<>(messages));
            lastOptions = options;
            onCall.accept(messages);
            if (fail) throw new IOException("fake model failure");
            String output = callbackChunks == null ? "answer " + calls.size() : String.join("", callbackChunks);
            if (callback != null) {
                if (callbackChunks == null) callback.onCompletion(output);
                else callbackChunks.forEach(callback::onCompletion);
            }
            CompletionsResponse response = new CompletionsResponse();
            response.choices = new CompletionsResponse.Choice[]{new CompletionsResponse.Choice()};
            response.choices[0].finish_reason = "stop";
            response.choices[0].message.content = output;
            return response;
        }
    }

    static final class FakeChannel extends MessageChannel implements SessionChat {
        final List<String> sent = new ArrayList<>();
        FakeChannel(String id) {
            super(id);
            loginAccount = new Account();
            loginAccount.setName("bot");
            loginAccount.setLocationId("Test:bot");
            loginAccount.setPlatform("Test");
        }
        @Override public void load() {}
        @Override public void stop() {}
        @Override public Session getSession(String id) {
            Session session = new Session();
            session.setId(id);
            session.setName(id);
            session.setLocationId("Test:" + id);
            return session;
        }
        @Override public int sendMessage(String sessionId, MFChain message) {
            sent.add(sessionId + ":" + message);
            return sent.size();
        }
    }

    static final class FakeLeadingProvider extends LLMProvider {
        final List<List<Message>> calls = new ArrayList<>();
        java.util.function.Function<List<Message>, String> output = messages -> routedPlan(messages, true);
        Consumer<List<Message>> onCall = ignored -> {};
        boolean fail;
        @Override public CompletionsResponse completions(String model, List<Message> messages,
                List<LLMFunction> tools, CompletionsRequest options, BufferCallback callback) throws IOException {
            calls.add(new ArrayList<>(messages));
            onCall.accept(messages);
            if (fail) throw new IOException("fake leading failure");
            CompletionsResponse response = new CompletionsResponse();
            response.choices = new CompletionsResponse.Choice[]{new CompletionsResponse.Choice()};
            response.choices[0].finish_reason = "stop";
            response.choices[0].message.content = output.apply(messages);
            return response;
        }
    }

    static String routedPlan(List<Message> messages, boolean reply) {
        var plan = com.google.gson.JsonParser.parseString(LeadingPlanTest.emptyPlan()).getAsJsonObject();
        List<Long> ids = newHumanIds(messages);
        plan.addProperty("should_reply", reply && !ids.isEmpty());
        if (reply) ids.forEach(id -> plan.getAsJsonArray("addressed_message_ids").add(id));
        return plan.toString();
    }

    static List<Long> newHumanIds(List<Message> messages) {
        String directory = messages.get(messages.size() - 1).toString().split("本批新消息目录：\\n", 2)[1]
                .split("\\n允许维护记忆", 2)[0];
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("编号=(\\d+)；来源=真人").matcher(directory);
        List<Long> ids = new ArrayList<>();
        while (matcher.find()) ids.add(Long.parseLong(matcher.group(1)));
        return ids;
    }
}

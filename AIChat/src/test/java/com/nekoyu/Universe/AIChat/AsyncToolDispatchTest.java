package com.nekoyu.Universe.AIChat;

import com.nekoyu.Universe.API.MessageChannel.MFChain;
import com.nekoyu.Universe.API.Providers.LLMProvider.LLMProvider;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.*;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.CompletionsResponse;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.nekoyu.Universe.AIChat.knowledge.LibrarianResearchTest.*;

class AsyncToolDispatchTest {
    @Test void leadingCanSuppressProgressAndStillDeliverFullReport() throws Exception {
        runStreamingResearch(false);
    }

    @Test void leadingCanPublishUsefulProgressBeforeFullReport() throws Exception {
        runStreamingResearch(true);
    }

    private void runStreamingResearch(boolean publishProgress) throws Exception {
        CountDownLatch progressSent = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        AtomicReference<Calling> toolCallback = new AtomicReference<>();
        AtomicInteger rounds = new AtomicInteger();
        List<List<Message>> requests = new ArrayList<>();
        String group = "streaming-tool-" + UUID.randomUUID();
        LLMFunction tool = LLMFunction.builder().name("stream_probe").asyncCallback((args, calling) -> {
            toolCallback.set(calling);
            calling.calling(new Message("阶段结论：功能已经提供。"));
            progressSent.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { throw new RuntimeException(error); }
            calling.calling(new Message("完整最终报告：功能已经提供；仅限指定版本；来源 https://example.invalid/source"));
            finished.countDown();
        }).build();
        AIChat.registerFunction(group, tool);
        try (DriveMessageTest.Fixture fixture = new DriveMessageTest.Fixture(false)) {
            fixture.config.Tools = new String[]{group};
            fixture.annotator.output = messages -> DriveMessageTest.routedPlan(messages,
                    publishProgress || DriveMessageTest.newInputDirectory(messages).contains("完整最终报告"));
            fixture.ai.startReplyService();
            fixture.ai.driveMessage(fixture.sessionId, new MFChain("研究问题"));
            fixture.topic().provider = new LLMProvider() {
                @Override public CompletionsResponse completions(String model, List<Message> messages, List<LLMFunction> tools,
                                                                CompletionsRequest options, BufferCallback callback) {
                    requests.add(new ArrayList<>(messages));
                    int round = rounds.incrementAndGet();
                    if (round == 1) {
                        var result = toolResponse("", 1);
                        result.choices[0].message.tool_calls[0].function.name = "stream_probe";
                        return result;
                    }
                    String answer = "回复 " + round;
                    if (callback != null) callback.onCompletion(answer);
                    return response(answer, "stop");
                }
            };
            fixture.time.advance(5_000);
            assertTrue(progressSent.await(5, TimeUnit.SECONDS));
            fixture.time.advance(5_000);
            assertEquals(1, finished.getCount(), "研究仍在运行");
            int afterProgress = publishProgress ? 3 : 2;
            assertEquals(afterProgress, rounds.get(), "阶段反馈是否发言由 leading 决定");
            assertEquals(publishProgress ? 2 : 1, fixture.channel.sent.size());
            var stage = fixture.annotator.calls.get(1).stream().filter(m -> "user".equals(m.role)
                    && m.toString().contains("阶段结论")).findFirst().orElseThrow();
            assertTrue(stage.toString().contains("[工具反馈，非真人消息；工具调用ID=lookup-1]"));
            assertNull(stage.tool_call_id, "异步更新不是已结束调用块的另一个 tool 响应");
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            fixture.time.advance(5_000);
            assertEquals(afterProgress + 1, rounds.get());
            assertEquals(3, fixture.annotator.calls.size(), "阶段和最终消息都经过现有 leading");
            List<Message> finalRequest = requests.get(afterProgress);
            int stageIndex = index(finalRequest, "阶段结论");
            int finalIndex = index(finalRequest, "完整最终报告");
            assertTrue(stageIndex >= 0 && finalIndex > stageIndex);
            assertEquals(publishProgress ? 3 : 2, fixture.channel.sent.size());
            fixture.ai.closeTopic(fixture.sessionId);
            toolCallback.get().calling(new Message("会话关闭后的迟到消息"));
            fixture.time.advance(5_000);
            assertEquals(afterProgress + 1, rounds.get());
        } finally {
            release.countDown();
            AIChat.llmFunctions.removeAll(group);
        }
    }

    @Test void callbackContractStillSupportsSynchronousCallers() {
        LLMFunction function = LLMFunction.builder().name("two-parts").asyncCallback((args, calling) -> {
            calling.calling(new Message("第一段"));
            calling.calling(new Message("最终报告"));
        }).build();
        assertEquals(LLMFunction.Type.asynchronous, function.type);
        assertEquals("第一段最终报告", function.callback.callSync(null).toString());
    }

    private static int index(List<Message> messages, String text) {
        for (int i = 0; i < messages.size(); i++) if (messages.get(i).toString().contains(text)) return i;
        return -1;
    }
}

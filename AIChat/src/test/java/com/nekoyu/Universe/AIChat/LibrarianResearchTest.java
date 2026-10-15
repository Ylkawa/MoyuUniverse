package com.nekoyu.Universe.AIChat;

import com.nekoyu.Universe.API.Providers.LLMProvider.LLMProvider;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.*;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.CompletionsResponse;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class LibrarianResearchTest {
    private static final String URL = "https://example.invalid/evidence";
    private static final String FINAL = "完整研究报告\n\n早期结论：功能已经提供。依据：" + URL
            + "\n\n后续结论：仅限指定版本。限制：只有搜索摘要，尚未核对原文。";

    @Test void progressPrecedesToolsLocalMaterialJoinsAndFinalRepeatsEarlyFindings() throws Exception {
        List<String> trace = new CopyOnWriteArrayList<>();
        List<String> feedback = new CopyOnWriteArrayList<>();
        CountDownLatch localReady = new CountDownLatch(1);
        CountDownLatch ingested = new CountDownLatch(1);
        AtomicReference<Librarian.CompletedResearch> completed = new AtomicReference<>();
        String group = "research-test-" + UUID.randomUUID();
        LLMFunction tool = LLMFunction.builder().name("lookup").syncCallback(args -> {
            assertFalse(feedback.isEmpty(), "工具执行前已经回调完整进度");
            trace.add("tool");
            try { assertTrue(localReady.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { throw new RuntimeException(error); }
            return new Message("搜索摘要：功能已经提供。" + URL);
        }).build();
        AIChat.registerFunction(group, tool);
        AtomicInteger calls = new AtomicInteger();
        try {
            LLMProvider provider = provider((messages, request) -> {
                assertNull(request.responseFormat);
                assertTrue(messages.get(0).toString().contains("最终退出报告"));
                int round = calls.incrementAndGet();
                if (round > 1) assertEquals(1, messages.stream()
                        .filter(m -> m.toString().contains("本地测试材料")).count());
                if (round == 1) return toolResponse("早期结论：功能已经提供。\n\n下一步核对版本限制。", 1);
                if (round == 2) return toolResponse("", 2);
                return response(FINAL, "stop");
            });
            Config.Librarian config = config(group);
            Librarian librarian = new Librarian(provider, config, q -> {
                localReady.countDown();
                return "本地测试材料：历史版本信息";
            }, result -> {
                completed.set(result);
                ingested.countDown();
            });
            librarian.search("核查功能和版本", message -> {
                trace.add("feedback");
                feedback.add(message.toString());
            });
            assertEquals(3, calls.get(), "两次工具轮和一次最终报告，无额外合并请求");
            assertEquals("早期结论：功能已经提供。", feedback.get(0));
            assertEquals("下一步核对版本限制。", feedback.get(1));
            assertTrue(feedback.get(2).contains("接下来调用 lookup"));
            assertEquals(FINAL, feedback.get(3), "最终报告单独完整回调，重复前面已经说过的结论");
            assertTrue(ingested.await(5, TimeUnit.SECONDS));
            assertEquals(FINAL, completed.get().report());
            assertEquals(Set.of(URL), completed.get().evidence().keySet());
            assertFalse(completed.get().evidence().get(URL).contains("下一步核对版本"));
            assertTrue(trace.indexOf("feedback") < trace.indexOf("tool"));
            assertTrue(feedback.stream().noneMatch(s -> s.contains("SECRET_REASONING")));
        } finally { AIChat.llmFunctions.removeAll(group); }
    }

    @Test void toolBudgetStillReservesCompleteFinalReport() {
        AtomicInteger rounds = new AtomicInteger();
        AtomicInteger executed = new AtomicInteger();
        String group = "research-budget-" + UUID.randomUUID();
        AIChat.registerFunction(group, LLMFunction.builder().name("lookup")
                .syncCallback(args -> { executed.incrementAndGet(); return new Message("摘要 " + URL); }).build());
        try {
            LLMProvider provider = provider((messages, request) -> {
                int round = rounds.incrementAndGet();
                if ("none".equals(request.toolChoice)) {
                    assertEquals(5, round);
                    assertTrue(messages.get(0).toString().contains("禁止只列新增发现"));
                    return response(FINAL, "stop");
                }
                return toolResponse("正在核查第 " + round + " 项。", round);
            });
            Config.Librarian config = config(group);
            config.enableAsyncUpdate = false;
            assertEquals(FINAL, new Librarian(provider, config, q -> "", r -> fail("禁用入库")).search("问题"));
            assertEquals(4, executed.get());
        } finally { AIChat.llmFunctions.removeAll(group); }
    }

    @Test void truncatedAndFailedResponsesNeverTriggerIngestion() {
        for (boolean throwsError : List.of(false, true)) {
            AtomicInteger indexed = new AtomicInteger();
            LLMProvider provider = provider((messages, request) -> {
                if (throwsError) throw new IOException("offline network failure");
                return response("被截断的半篇报告", "length");
            });
            String result = new Librarian(provider, config(null), q -> "", r -> indexed.incrementAndGet()).search("问题");
            assertTrue(result.startsWith("研究失败"));
            assertEquals(0, indexed.get());
        }
    }

    @Test void slowOrFailedLocalLookupDoesNotBlockResearch() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Config.Librarian config = config(null);
        config.enableAsyncUpdate = false;
        try {
            assertEquals(FINAL, new Librarian(provider((messages, request) -> response(FINAL, "stop")), config,
                    q -> { release.await(); return "迟到本地材料"; }, result -> fail("禁用入库")).search("问题"));
            assertEquals(FINAL, new Librarian(provider((messages, request) -> response(FINAL, "stop")), config,
                    q -> { throw new IOException("offline local lookup failure"); }, result -> fail("禁用入库")).search("问题"));
        } finally { release.countDown(); }
    }

    @Test void timeoutRejectsLateProgressAndIngestion() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger indexed = new AtomicInteger();
        List<String> feedback = new CopyOnWriteArrayList<>();
        LLMProvider provider = provider((messages, request) -> {
            started.countDown();
            try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            return toolResponse("迟到进度", 1);
        });
        Librarian librarian = new Librarian(provider, config(null), q -> "", r -> indexed.incrementAndGet());
        librarian.search("问题", message -> feedback.add(message.toString()), 100);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        release.countDown();
        assertEquals(1, feedback.size());
        assertTrue(feedback.get(0).startsWith("研究超时"));
        assertEquals(0, indexed.get());
    }

    static Config.Librarian config(String group) {
        Config.Librarian config = new Config.Librarian();
        config.model = "offline-research";
        config.tools = group == null ? List.of() : List.of(group);
        return config;
    }

    interface Round { CompletionsResponse call(List<Message> messages, CompletionsRequest request) throws IOException; }
    static LLMProvider provider(Round round) {
        return new LLMProvider() {
            @Override public CompletionsResponse completions(String model, List<Message> messages, List<LLMFunction> tools,
                                                            CompletionsRequest request, BufferCallback callback) throws IOException {
                return round.call(messages, request);
            }
        };
    }
    static CompletionsResponse response(String text, String finish) {
        var response = new CompletionsResponse();
        response.choices = new CompletionsResponse.Choice[]{new CompletionsResponse.Choice()};
        response.choices[0].message.content = text;
        response.choices[0].message.reasoning_content = "SECRET_REASONING";
        response.choices[0].finish_reason = finish;
        return response;
    }
    static CompletionsResponse toolResponse(String text, int number) {
        var response = response(text, "tool_calls");
        Tool_call call = new Tool_call();
        call.id = "lookup-" + number;
        call.function.name = "lookup";
        call.function.arguments = "{\"n\":" + number + "}";
        response.choices[0].message.tool_calls = new Tool_call[]{call};
        return response;
    }
}

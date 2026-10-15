package com.nekoyu.Universe.AIChat;

import com.google.gson.Gson;
import com.nekoyu.Universe.API.Providers.LLMProvider.LLMProvider;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.CompletionsRequest;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.LLMFunction;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Message;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ResponseFormat;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Tool_call;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.CompletionsResponse;
import com.nekoyu.Universe.Universe;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 检查业务提示词与输出契约分别传递，通用 Schema 提示词由 Adapter 添加。 */
class StructuredPromptTest {
    private static final Gson GSON = new Gson();
    private static final String SCHEMA_MARKER = "字段定义：\n";

    @Test void leadingSendsItsCompleteContract() {
        try (DriveMessageTest.Fixture fixture = new DriveMessageTest.Fixture(false)) {
            fixture.ai.startReplyService();
            fixture.ai.handleSessionMessage(fixture.config, fixture.provider, fixture.user("ordinary question"));
            fixture.time.advance(5_000);
            String prompt = assertPrompt(fixture.annotator.calls.get(0), StructuredOutputs.leading());
            assertTrue(prompt.contains("原始消息 ID，允许负数"));
            assertTrue(prompt.contains("location_id 必须出现，非特定用户查询时使用 null"));
            assertTrue(prompt.contains("最多 500 字") && prompt.contains("最多 200 字"));
            assertTrue(prompt.contains("\"should_reply\":false,\"addressed_message_ids\":[]"));
        }
    }

    @Test void knowledgeSendsItsCompleteContractAndKeepsInputSeparate() throws Exception {
        ResponseFormat format = StructuredOutputs.knowledge();
        String before = GSON.toJson(format);
        String input = "资料正文\n包含\"引号\"与反斜杠\\，不是格式示例。";
        ChatContext context = KnowledgeStore.queryContext(input, 3);
        CompletionsRequest request = new CompletionsRequest();
        request.responseFormat = format;
        CapturingProvider provider = new CapturingProvider();
        provider.output = "{\"items\":[]}";
        var response = context.runTurn(provider, "offline", null, request, new ToolLoopOptions(), null);
        String prompt = assertPrompt(provider.calls.get(0), provider.options.get(0).responseFormat);
        assertEquals(before, GSON.toJson(request.responseFormat), "提示词生成不得修改请求中的 Schema");
        assertTrue(prompt.contains("本块最多 3 条") && prompt.contains("最多 300 字") && prompt.contains("最多 1000 字"));
        assertTrue(prompt.contains("{\"items\":[]}"));
        assertEquals(input, provider.calls.get(0).get(1).toString());
        assertTrue(KnowledgeQueryParser.parse(StructuredOutputs.content(response), 3).isEmpty());
    }

    @Test void librarianSendsContractsThroughToolResearchMergeAndConflict() throws Exception {
        String providerId = "structured-prompt-" + UUID.randomUUID();
        String toolGroup = providerId + "-tools";
        CapturingProvider provider = new CapturingProvider();
        AtomicInteger toolCalls = new AtomicInteger();
        LLMFunction tool = LLMFunction.builder().name("prompt_probe")
                .syncCallback(args -> {
                    toolCalls.incrementAndGet();
                    return new Message("离线工具结果");
                }).build();
        Universe.Providers.put(providerId, provider);
        AIChat.registerFunction(toolGroup, tool);
        try {
            Config.Librarian config = new Config.Librarian();
            config.provider = providerId;
            config.model = "offline";
            config.tools = List.of(toolGroup);
            Librarian librarian = new Librarian(null, config);
            LibrarianResult expected = new LibrarianResult("结论\n\"引号\"与\\路径", List.of(
                    new LibrarianResult.Source("https://example.invalid/source", "来源标题", "摘要\n第二行")));
            provider.output = expected.toJson();
            provider.useTool = true;
            Method research = Librarian.class.getDeclaredMethod("webSearch", String.class);
            research.setAccessible(true);
            assertEquals(expected, research.invoke(librarian, "离线研究问题"));
            assertEquals(1, toolCalls.get());
            assertEquals(2, provider.calls.size());
            for (int i = 0; i < provider.calls.size(); i++) {
                String prompt = assertPrompt(provider.calls.get(i), provider.options.get(i).responseFormat);
                assertTrue(prompt.contains("三个字段均为字符串，url 不得为空"));
                assertTrue(prompt.contains("网址只来自输入明确提供的网址或本轮工具实际返回的网址"));
            }
            assertTrue(provider.calls.get(1).stream().anyMatch(message -> "tool".equals(message.role)));

            provider.clear();
            Method merge = Librarian.class.getDeclaredMethod("mergeResults", String.class, String.class, LibrarianResult.class);
            merge.setAccessible(true);
            assertEquals(expected, merge.invoke(librarian, "离线研究问题", "本地资料", expected));
            String mergePrompt = assertPrompt(provider.calls.get(0), provider.options.get(0).responseFormat);
            assertTrue(mergePrompt.contains("sources 使用 []，不得省略或使用 null"));
            assertTrue(mergePrompt.contains("本地资料可补充概念、背景与历史沿革"));

            provider.clear();
            provider.output = "{\"conflict\":false}";
            Method conflict = Librarian.class.getDeclaredMethod("isConflicting", String.class, String.class);
            conflict.setAccessible(true);
            assertEquals(false, conflict.invoke(librarian, "旧资料", "新资料"));
            String conflictPrompt = assertPrompt(provider.calls.get(0), provider.options.get(0).responseFormat);
            assertTrue(conflictPrompt.contains("{\"conflict\":true}") && conflictPrompt.contains("{\"conflict\":false}"));
            assertTrue(conflictPrompt.contains("范围不同或无法确定范围相同，不据此判定冲突"));
        } finally {
            Universe.Providers.remove(providerId);
            AIChat.llmFunctions.removeAll(toolGroup);
        }
    }

    private static String assertPrompt(List<Message> messages, ResponseFormat format) {
        assertEquals("json_schema", format.type);
        assertTrue(format.jsonSchema.strict);
        String prompt = messages.get(0).toString();
        assertTrue(messages.stream().noneMatch(message -> message.toString().contains(SCHEMA_MARKER)),
                "业务层不应重复拼接 Adapter 负责的 Schema 提示词");
        assertNotNull(format.jsonSchema.schema);
        assertFalse(prompt.contains("〔这里"));
        return prompt;
    }

    private static final class CapturingProvider extends LLMProvider {
        final List<List<Message>> calls = new ArrayList<>();
        final List<CompletionsRequest> options = new ArrayList<>();
        String output;
        boolean useTool;

        @Override public CompletionsResponse completions(String model, List<Message> messages, List<LLMFunction> tools,
                                                        CompletionsRequest request, BufferCallback callback) {
            calls.add(new ArrayList<>(messages));
            options.add(request);
            CompletionsResponse response = new CompletionsResponse();
            response.choices = new CompletionsResponse.Choice[]{new CompletionsResponse.Choice()};
            var choice = response.choices[0];
            choice.message.content = output;
            choice.finish_reason = "stop";
            if (useTool && calls.size() == 1 && tools != null && !tools.isEmpty()) {
                choice.message.content = "{\"answer\":\"正在核查\",\"sources\":[]}";
                choice.finish_reason = "tool_calls";
                Tool_call call = new Tool_call();
                call.id = "prompt-call";
                call.function.name = "prompt_probe";
                call.function.arguments = "{}";
                choice.message.tool_calls = new Tool_call[]{call};
            }
            return response;
        }

        void clear() {
            calls.clear();
            options.clear();
        }
    }
}

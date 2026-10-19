package com.nekoyu.Universe.AIChat;

import com.nekoyu.Universe.API.MessageChannel.MFChain;
import com.nekoyu.Universe.API.Providers.LLMProvider.LLMProvider;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.*;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.CompletionsResponse;
import com.nekoyu.Universe.Universe;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ChatAssistantTest {
    @Test void configuredObjectsReachProviderInEitherInjectionOrder() throws IOException {
        for (boolean thinking : List.of(false, true)) {
            for (boolean contextFirst : List.of(false, true)) {
                ChatContext context = new ChatContext();
                context.setSystemPromptFirst("first prompt");
                context.setSystemPromptLast("last prompt");
                context.userMsg(new Message("question"));
                CompletionsRequest request = new CompletionsRequest();
                request.enable_thinking = thinking;
                request.responseFormat = ResponseFormat.jsonObject();
                request.placeholders.put("_SessionId", "test:session");
                request.asyncToolSink = (id, timeout) -> fail("no tools should run");
                AsyncToolSink sink = request.asyncToolSink;
                ResponseFormat format = request.responseFormat;
                LLMProvider provider = new LLMProvider() {
                    @Override public CompletionsResponse completions(String model, List<Message> messages,
                            List<LLMFunction> tools, CompletionsRequest options, BufferCallback callback) {
                        assertEquals("configured-model", model);
                        assertSame(request, options);
                        assertEquals(thinking, options.enable_thinking);
                        assertSame(format, options.responseFormat);
                        assertSame(sink, options.asyncToolSink);
                        assertEquals("test:session", options.placeholders.get("_SessionId"));
                        assertEquals(List.of("first prompt", "question", "last prompt"),
                                messages.stream().map(Message::toString).toList());
                        if (callback != null) callback.onCompletion("{\"ok\":true}");
                        return response("{\"ok\":true}", "stop");
                    }
                };
                ChatAssistant assistant = new ChatAssistant(provider, "configured-model");
                if (contextFirst) {
                    assistant.setChatContext(context);
                    assistant.setCompletionsRequest(request);
                } else {
                    assistant.setCompletionsRequest(request);
                    assistant.setChatContext(context);
                }
                StringBuilder output = new StringBuilder();
                assertEquals("{\"ok\":true}", assistant.completions(output::append).choices[0].message.content);
                assertEquals("{\"ok\":true}", output.toString());
                assertEquals(2, context.getBase().size(), "the supplied context receives assistant history");
            }
        }
    }

    @Test void toolsStayDeduplicatedAndStructuredTurnHonorsInjectedLoopOptions() throws IOException {
        AtomicInteger executed = new AtomicInteger();
        AtomicInteger rounds = new AtomicInteger();
        CompletionsRequest request = new CompletionsRequest();
        request.enable_thinking = true;
        request.responseFormat = StructuredOutputs.conflict();
        LLMFunction lookup = LLMFunction.builder().name("lookup").syncCallback(args -> {
            executed.incrementAndGet();
            return new Message("lookup result");
        }).build();
        LLMFunction duplicate = LLMFunction.builder().name("lookup").syncCallback(args -> {
            fail("duplicate tool must not replace the first registration");
            return new Message();
        }).build();
        LLMFunction unused = LLMFunction.builder().name("unused").build();
        ChatAssistant assistant = new ChatAssistant(new LLMProvider() {
            @Override public CompletionsResponse completions(String model, List<Message> messages,
                    List<LLMFunction> tools, CompletionsRequest options, BufferCallback callback) {
                assertSame(request, options);
                assertTrue(options.enable_thinking);
                assertSame(request.responseFormat, options.responseFormat);
                assertEquals(List.of("lookup", "unused"), tools.stream().map(tool -> tool.name).toList());
                if (rounds.incrementAndGet() == 1) {
                    assertNull(options.toolChoice);
                    if (callback != null) callback.onCompletion("{\"intermediate\":true}");
                    CompletionsResponse response = toolResponse("lookup", "call_lookup", "{}");
                    response.choices[0].message.content = "{\"intermediate\":true}";
                    response.choices[0].message.reasoning_content = "tool reasoning";
                    return response;
                }
                assertEquals("none", options.toolChoice, "the injected two-round limit forces the final round");
                assertTrue(messages.stream().anyMatch(message -> "tool".equals(message.role)
                        && "call_lookup".equals(message.tool_call_id) && "lookup result".equals(message.toString())));
                assertTrue(messages.stream().anyMatch(message -> "tool reasoning".equals(message.reasoning_content)));
                if (callback != null) callback.onCompletion("{\"conflict\":false}");
                return response("{\"conflict\":false}", "stop");
            }
        }, "offline");
        assistant.addTool(lookup);
        assistant.addTools(List.of(duplicate, lookup, unused));
        assistant.setCompletionsRequest(request);
        ToolLoopOptions loop = new ToolLoopOptions();
        loop.MaxToolRounds = 2;
        assistant.setToolLoopOptions(loop);
        assistant.getChatContext().userMsg(new Message("question"));
        StringBuilder output = new StringBuilder();
        CompletionsResponse result = assistant.completions(output::append);
        assertEquals("{\"conflict\":false}", StructuredOutputs.content(result));
        assertEquals("{\"conflict\":false}", output.toString());
        assertEquals(1, executed.get());
        assertEquals(2, rounds.get());
        assertEquals(10, result.usage.total_tokens);
    }

    @Test void mainAndSubAgentThinkingAndPromptsSurviveRepeatedInvocations() {
        for (boolean thinking : List.of(false, true)) {
            try (DriveMessageTest.Fixture fixture = new DriveMessageTest.Fixture(false)) {
                List<List<Message>> subRequests = new ArrayList<>();
                SubAgentConfig config = new SubAgentConfig();
                config.name = "configured_sub_agent";
                config.model = "configured-sub-model";
                config.SystemPrompt = "configured sub-agent system prompt";
                config.description = "configured sub-agent description";
                config.thinking = thinking;
                LLMProvider subProvider = new LLMProvider() {
                    @Override public CompletionsResponse completions(String model, List<Message> messages,
                            List<LLMFunction> tools, CompletionsRequest options, BufferCallback callback) {
                        assertEquals(config.model, model);
                        assertEquals(thinking, options.enable_thinking);
                        int invocation = subRequests.size() + 1;
                        assertEquals(List.of(config.SystemPrompt, "sub-question-" + invocation),
                                messages.stream().map(Message::toString).toList(), "each invocation uses only its own prompt and question");
                        assertEquals(List.of("system", "user"), messages.stream().map(message -> message.role).toList());
                        subRequests.add(new ArrayList<>(messages));
                        String answer = "sub-answer-" + invocation;
                        if (callback != null) callback.onCompletion(answer);
                        return response(answer, "stop");
                    }
                };
                fixture.ai.subAgents.put(config.name, AIChat.SubAgentDefinition.from(subProvider, config));
                fixture.config.subAgents = List.of(config.name);
                fixture.config.enable_thinking = thinking;
                AtomicInteger mainRounds = new AtomicInteger();
                LLMProvider mainProvider = new LLMProvider() {
                    @Override public CompletionsResponse completions(String model, List<Message> messages,
                            List<LLMFunction> tools, CompletionsRequest options, BufferCallback callback) {
                        assertEquals(thinking, options.enable_thinking);
                        assertEquals(fixture.sessionId, options.placeholders.get("_SessionId"));
                        assertNotNull(options.placeholders.get("_LocationID"));
                        assertNotNull(options.asyncToolSink);
                        assertTrue(options.asyncToolSink.acceptsCallbacks());
                        assertEquals(1, tools.size());
                        assertEquals(config.name, tools.get(0).name);
                        assertEquals(config.description, tools.get(0).description);
                        int round = mainRounds.incrementAndGet();
                        int invocation = (round + 1) / 2;
                        if (round % 2 == 1) return toolResponse(config.name, "call_sub_" + invocation,
                                "{\"Question\":\"sub-question-" + invocation + "\"}");
                        assertTrue(messages.stream().anyMatch(message -> "tool".equals(message.role)
                                && ("call_sub_" + invocation).equals(message.tool_call_id)
                                && ("sub-answer-" + invocation).equals(message.toString())));
                        String answer = "main-answer-" + invocation;
                        if (callback != null) callback.onCompletion(answer);
                        return response(answer, "stop");
                    }
                };
                Universe.Providers.put(fixture.providerId, mainProvider);
                fixture.ai.startReplyService();
                fixture.ai.driveMessage(fixture.sessionId, new MFChain("first main question"));
                fixture.time.advance(5_000);
                fixture.ai.driveMessage(fixture.sessionId, new MFChain("second main question"));
                fixture.time.advance(5_000);
                assertEquals(4, mainRounds.get());
                assertEquals(2, subRequests.size());
                assertEquals(List.of("room:main-answer-1", "room:main-answer-2"), fixture.channel.sent);
            }
        }
    }

    private static CompletionsResponse response(String content, String finish) {
        CompletionsResponse response = new CompletionsResponse();
        response.choices = new CompletionsResponse.Choice[]{new CompletionsResponse.Choice()};
        response.choices[0].finish_reason = finish;
        response.choices[0].message.content = content;
        response.usage.prompt_tokens = 2;
        response.usage.completion_tokens = 3;
        response.usage.total_tokens = 5;
        return response;
    }

    private static CompletionsResponse toolResponse(String name, String id, String arguments) {
        CompletionsResponse response = response("", "tool_calls");
        Tool_call call = new Tool_call();
        call.id = id;
        call.type = "function";
        call.function.name = name;
        call.function.arguments = arguments;
        response.choices[0].message.tool_calls = new Tool_call[]{call};
        return response;
    }
}

import com.google.gson.*;
import com.google.common.collect.Multimap;
import com.nekoyu.Universe.AIChat.*;
import com.nekoyu.Universe.AIChat.Web.Config;
import com.nekoyu.Universe.AIChat.Web.Web;
import com.nekoyu.Universe.AIChat.Web.SauceNAO.*;
import com.nekoyu.Universe.API.MessageChannel.*;
import com.nekoyu.Universe.API.MessageChannel.MessageField.ImageField;
import com.nekoyu.Universe.API.Providers.LLMProvider.LLMProvider;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.*;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.CompletionsResponse;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs registration and the real ChatContext tool loop with an offline model/query. */
public class SauceNaoIntegrationTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Field registryField = AIChat.class.getDeclaredField("llmFunctions");
        registryField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Multimap<String, LLMFunction> registry = (Multimap<String, LLMFunction>) registryField.get(null);
        registry.removeAll("SauceNAOSearch");
        Web web = new Web(null);
        Field configField = Web.class.getDeclaredField("config"); configField.setAccessible(true);
        Method register = Web.class.getDeclaredMethod("registerSauceNAO", Proxy.class); register.setAccessible(true);
        configField.set(web, new Gson().fromJson("{\"EnableSauceNAO\":false,\"SauceNAOApiKey\":\"test\"}", Config.class));
        register.invoke(web, Proxy.NO_PROXY);
        check(registry.get("SauceNAOSearch").isEmpty(), "disabled not registered");
        if (System.getenv("SAUCENAO_API_KEY") == null || System.getenv("SAUCENAO_API_KEY").isBlank()) {
            configField.set(web, new Config()); register.invoke(web, Proxy.NO_PROXY);
            check(registry.get("SauceNAOSearch").isEmpty(), "missing key not registered");
        }
        configField.set(web, new Gson().fromJson("{\"SauceNAOApiKey\":\"test\",\"SauceNAOShortLimit\":0}", Config.class));
        register.invoke(web, Proxy.NO_PROXY);
        check(registry.get("SauceNAOSearch").isEmpty(), "invalid limit not registered");
        configField.set(web, new Gson().fromJson("{\"SauceNAOApiKey\":\"test\"}", Config.class));
        register.invoke(web, Proxy.NO_PROXY);
        LLMFunction tool = registry.get("SauceNAOSearch").iterator().next();
        check(tool.name.equals("SauceNAOSearch") && tool.description.contains("<quote:N>"), "registered descriptor");
        check(tool.callback.callSync(JsonParser.parseString("{\"URL\":12}")).toString().contains("参数错误"), "string required");
        check(tool.callback.callSync(JsonParser.parseString("{}")).toString().contains("参数错误"), "URL required");
        check(tool.callback.callSync(JsonParser.parseString("{\"URL\":\"<quote:1>\"}")).toString().contains("参数错误"), "unresolved URL rejected");

        Method resolve = ChatContext.class.getDeclaredMethod("resolveImageQuotes", JsonElement.class, Map.class);
        resolve.setAccessible(true);
        JsonElement nested = JsonParser.parseString("{\"URL\":\"<quote:1>\",\"nested\":[{\"x\":\"<quote:2>\"}],\"<quote:1>\":7}");
        JsonElement resolved = (JsonElement) resolve.invoke(null, nested, Map.of(1, SauceNaoServiceTest.A, 2, SauceNaoServiceTest.B));
        check(resolved.getAsJsonObject().get("URL").getAsString().equals(SauceNaoServiceTest.A), "first image");
        check(resolved.getAsJsonObject().getAsJsonArray("nested").get(0).getAsJsonObject().get("x").getAsString().equals(SauceNaoServiceTest.B), "nested second image");
        check(resolved.getAsJsonObject().has("<quote:1>") && nested.toString().contains("<quote:2>"), "keys and original preserved");

        AtomicInteger searches = new AtomicInteger();
        SearchService service = new SearchService(url -> {
            check(url.equals(SauceNaoServiceTest.A), "query receives resolved URL");
            searches.incrementAndGet(); return SauceNaoServiceTest.response(0, 3, 99);
        }, 4, 100);
        // Replace only the network callback; exercise the registered tool through real assistant loading/runTurn.
        tool.callback = (LLMFunction.SyncCallback) json -> new Message(service.search(json.getAsJsonObject().get("URL").getAsString()));
        for (String quote : List.of("<quote:1>", "<quote:999>", "<quote:999999999999999>")) {
            AtomicInteger rounds = new AtomicInteger();
            LLMProvider model = new LLMProvider() {
                public CompletionsResponse completions(String name, List<Message> messages, List<LLMFunction> tools,
                        CompletionsRequest request, BufferCallback buffer) {
                    check(tools.stream().anyMatch(t -> t.name.equals("SauceNAOSearch")), "assistant sees tool");
                    CompletionsResponse response = new CompletionsResponse();
                    var choice = new CompletionsResponse.Choice(); response.choices = new CompletionsResponse.Choice[]{choice};
                    if (rounds.getAndIncrement() == 0) {
                        check(messages.stream().anyMatch(m -> m.toString().contains("<quoteId:1>")), "image marker visible");
                        Tool_call call = new Tool_call(); call.id = "offline"; call.type = "function";
                        call.function.name = "SauceNAOSearch";
                        call.function.arguments = new Gson().toJson(Map.of("URL", quote));
                        choice.message.tool_calls = new Tool_call[]{call}; choice.finish_reason = "tool_calls";
                    } else {
                        check(messages.stream().anyMatch(m -> "tool".equals(m.role)
                                && m.toString().contains(quote.equals("<quote:1>") ? "未找到匹配来源" : "调用工具失败")), "result returned to model");
                        choice.message.content = "done"; choice.finish_reason = "stop";
                    }
                    return response;
                }
            };
            MessageList base = new MessageList(); MCMessage image = new MCMessage();
            image.messageFields.add(new ImageField(new URL(SauceNaoServiceTest.A))); base.add(image);
            ChatAssistant assistant = new ChatAssistant(model, "offline");
            assistant.setChatContext(new ChatContext(base));
            AIChat.addRegisteredTools(assistant, "SauceNAOSearch");
            AIChat.addRegisteredTools(assistant, "SauceNAOSearch");
            assistant.completions(null);
            check(rounds.get() == 2, "tool and final rounds");
        }
        check(searches.get() == 1, "invalid references never invoke query");
        registry.removeAll("SauceNAOSearch");
        System.out.println("SauceNaoIntegrationTest passed");
    }
}

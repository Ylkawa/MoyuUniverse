import com.google.common.collect.Multimap;
import com.google.gson.*;
import com.nekoyu.Universe.AIChat.*;
import com.nekoyu.Universe.AIChat.Skill.*;
import com.nekoyu.Universe.AIChat.Web.Config;
import com.nekoyu.Universe.AIChat.Web.Web;
import com.nekoyu.Universe.AIChat.Web.Amap.*;
import com.nekoyu.Universe.API.Providers.LLMProvider.LLMProvider;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.*;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.CompletionsResponse;

import java.lang.reflect.*;
import java.net.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Native Web registration, skill activation and real ChatContext tool/result loop, offline. */
public class MapSkillIntegrationTest {
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Field registryField = AIChat.class.getDeclaredField("llmFunctions"); registryField.setAccessible(true);
        @SuppressWarnings("unchecked") Multimap<String, LLMFunction> registry = (Multimap<String, LLMFunction>) registryField.get(null);
        List<String> toolNames = MapSkill.definition().toolNames;
        Map<String, List<LLMFunction>> previousTools = new HashMap<>();
        for (String name : toolNames) previousTools.put(name, new ArrayList<>(registry.removeAll(name)));
        Skill previousSkill = SkillRegistry.getAll().remove("map");
        try {
            Web web = new Web(null);
            Field config = Web.class.getDeclaredField("config"); config.setAccessible(true);
            Method register = Web.class.getDeclaredMethod("registerMap", Proxy.class); register.setAccessible(true);
            config.set(web, new Gson().fromJson("{\"EnableAmap\":false,\"AmapApiKey\":\"test\"}", Config.class));
            register.invoke(web, Proxy.NO_PROXY);
            check(toolNames.stream().allMatch(name -> registry.get(name).isEmpty()) && SkillRegistry.get("map") == null, "disabled skips tool and skill");
            if (System.getenv("AMAP_API_KEY") == null || System.getenv("AMAP_API_KEY").isBlank()) {
                config.set(web, new Config()); register.invoke(web, Proxy.NO_PROXY);
                check(SkillRegistry.get("map") == null, "missing key skips registration");
            }
            config.set(web, new Gson().fromJson("{\"AmapApiKey\":\"test\",\"AmapRequestsPerMinute\":0}", Config.class));
            register.invoke(web, Proxy.NO_PROXY);
            check(SkillRegistry.get("map") == null, "invalid limit skips registration");
            config.set(web, new Gson().fromJson("{\"AmapApiKey\":\"test\"}", Config.class));
            register.invoke(web, Proxy.NO_PROXY);
            Skill skill = SkillRegistry.get("map");
            check(skill != null && skill.mode == Skill.Mode.ON_DEMAND && skill.toolNames.equals(toolNames), "one map skill with six functions");
            check(registry.get("MapQuery").isEmpty() && toolNames.stream().allMatch(name -> registry.get(name).size() == 1), "six functions without MapQuery dispatcher");
            for (String name : toolNames) {
                LLMFunction function = registry.get(name).iterator().next();
                check(!function.parameters.properties.containsKey("action"), "action absent from " + name);
                check(function.parameters.required != null && !function.parameters.required.isEmpty(), "required parameters for " + name);
                check(function.callback.callSync(JsonParser.parseString("{}")).toString().contains("参数错误"), "missing arguments rejected by " + name);
            }
            LLMFunction tool = registry.get("MapWeather").iterator().next();
            check(tool.callback.callSync(JsonParser.parseString("{}")).toString().contains("参数错误"), "registered callback validates input offline");

            Topic topic = new Topic(new SessionConfig());
            SkillManager manager = new SkillManager(topic, List.of("map"), List.of());
            check(manager.hasOnDemandSkills() && manager.getActiveToolNames().isEmpty(), "discoverable without loading tools");
            check(manager.activate("map") && manager.getActiveToolNames().equals(toolNames), "activation supplies six functions");
            check(topic.getChatContext().getBase().stream().anyMatch(m -> "map".equals(m.getMetainfo("skillId"))), "skill prompt injected into context");
            check(!manager.activate("map"), "activation idempotent");
            MapService functionService = new MapService(new Client(MapServiceTest.KEY, MapServiceTest::fixture, 100, () -> 0));
            String[] actions = {"weather", "district", "geocode", "regeocode", "search", "route"};
            String[] queries = {
                    "{\"location\":\"杭州市\",\"type\":\"forecast\"}",
                    "{\"location\":\"杭州市\"}",
                    "{\"location\":\"测试地址\"}",
                    "{\"location\":\"116.310003,39.991957\"}",
                    "{\"city\":\"杭州市\",\"keywords\":\"杭州 & coffee\"}",
                    "{\"origin\":\"120.15507,30.274084\",\"destination\":\"120.16007,30.270084\",\"travelMode\":\"walking\"}"
            };
            List<LLMFunction> functions = MapSkill.tools(functionService);
            for (int i = 0; i < functions.size(); i++) {
                JsonObject query = JsonParser.parseString(queries[i]).getAsJsonObject();
                query.addProperty("action", "invalid-override");
                query.addProperty("_ToolCallId", "offline");
                query.addProperty("CustomRuntimeValue", "ignored");
                check(functions.get(i).callback.callSync(query).toString().startsWith("[高德地图 / " + actions[i] + "]"),
                        "correct operation and metadata filtering for " + functions.get(i).name);
            }
            AtomicInteger apiCalls = new AtomicInteger(), rounds = new AtomicInteger();
            MapService service = new MapService(new Client(MapServiceTest.KEY, url -> { apiCalls.incrementAndGet(); return MapServiceTest.fixture(url); }, 100, () -> 0));
            tool.callback = MapSkill.tools(service).get(0).callback;
            LLMProvider provider = new LLMProvider() {
                public CompletionsResponse completions(String model, List<Message> messages, List<LLMFunction> tools,
                        CompletionsRequest request, BufferCallback buffer) {
                    check(tools.size() == 6 && toolNames.stream().allMatch(name -> tools.stream().filter(t -> t.name.equals(name)).count() == 1), "six distinct map functions visible to model");
                    CompletionsResponse response = new CompletionsResponse();
                    var choice = new CompletionsResponse.Choice(); response.choices = new CompletionsResponse.Choice[]{choice};
                    if (rounds.getAndIncrement() == 0) {
                        Tool_call call = new Tool_call(); call.id = "offline-map"; call.type = "function";
                        call.function.name = "MapWeather";
                        call.function.arguments = "{\"location\":\"杭州市\",\"type\":\"forecast\"}";
                        choice.message.tool_calls = new Tool_call[]{call}; choice.finish_reason = "tool_calls";
                    } else {
                        check(messages.stream().anyMatch(m -> "tool".equals(m.role) && m.toString().contains("2026-10-03") && m.toString().contains("小雨")), "formatted result returns to model");
                        choice.message.content = "杭州明天小雨"; choice.finish_reason = "stop";
                    }
                    return response;
                }
            };
            ChatAssistant assistant = new ChatAssistant(provider, "offline");
            assistant.setChatContext(topic.getChatContext());
            CompletionsRequest request = new CompletionsRequest();
            request.placeholders.put("TIME", "2026-10-02 17:00:00");
            request.placeholders.put("SESSION_LOCATION_ID", "offline-session");
            request.placeholders.put("CustomRuntimeValue", "not-a-map-parameter");
            assistant.setCompletionsRequest(request);
            for (String name : manager.getActiveToolNames()) { AIChat.addRegisteredTools(assistant, name); AIChat.addRegisteredTools(assistant, name); }
            assistant.completions(null);
            check(rounds.get() == 2 && apiCalls.get() == 2, "tool loop completes with district and weather queries");
            check(manager.deactivate("map") && manager.getActiveToolNames().isEmpty(), "deactivation removes active bindings");
            check(topic.getChatContext().getBase().stream().noneMatch(m -> "map".equals(m.getMetainfo("skillId"))), "deactivation removes skill prompt");
            System.out.println("MapSkillIntegrationTest passed");
        } finally {
            for (String name : toolNames) { registry.removeAll(name); registry.putAll(name, previousTools.get(name)); }
            SkillRegistry.getAll().remove("map");
            if (previousSkill != null) SkillRegistry.register(previousSkill);
        }
    }
}

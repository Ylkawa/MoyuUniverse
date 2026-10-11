package com.nekoyu.universe.aichat.image_edit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nekoyu.Universe.AIChat.ChatAssistant;
import com.nekoyu.Universe.AIChat.MarkDecoupler;
import com.nekoyu.Universe.AIChat.SessionConfig;
import com.nekoyu.Universe.AIChat.Topic;
import com.nekoyu.Universe.AIChat.Skill.Skill;
import com.nekoyu.Universe.AIChat.Skill.SkillManager;
import com.nekoyu.Universe.AIChat.Skill.SkillRegistry;
import com.nekoyu.Universe.API.MessageChannel.MCMessage;
import com.nekoyu.Universe.API.MessageChannel.MessageField.ImageField;
import com.nekoyu.Universe.API.MessageChannel.MessageField.FileField;
import com.nekoyu.Universe.AIChat.AIChat;
import com.google.gson.Gson;
import com.google.common.collect.Multimap;
import com.nekoyu.Universe.API.MessageChannel.Account;
import com.nekoyu.Universe.API.MessageChannel.MFChain;
import com.nekoyu.Universe.API.MessageChannel.MessageChannel;
import com.nekoyu.Universe.API.MessageChannel.MessageChannelManager;
import com.nekoyu.Universe.API.MessageChannel.MessageList;
import com.nekoyu.Universe.API.MessageChannel.Session;
import com.nekoyu.Universe.API.MessageChannel.Features.SessionChat;
import com.nekoyu.Universe.API.UniverseChannel;
import com.nekoyu.Universe.API.Providers.LLMProvider.LLMProvider;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.CompletionsRequest;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ContentPiece.TextPiece;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.LLMFunction;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Message;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Tool_call;
import com.nekoyu.Universe.API.Providers.LLMProvider.RespBodies.CompletionsResponse;
import com.sun.net.httpserver.HttpServer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.io.File;
import java.lang.ref.Cleaner;
import java.lang.ref.WeakReference;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** 可直接运行，仅使用生成的图片和本机 HTTP 测试服务。 */
public final class ImageEditTest {
    private static final String SOURCE = "https://example.invalid/source.png";

    public static void main(String[] args) throws Exception {
        geometryAndValidation();
        httpAndSkillLoop();
        System.out.println("ImageEditTest passed");
    }

    private static void geometryAndValidation() throws Exception {
        BufferedImage input = fixture();
        AtomicReference<BufferedImage> output = new AtomicReference<>();
        AtomicInteger loads = new AtomicInteger();
        var service = new ImageEditService(url -> { loads.incrementAndGet(); return input; }, image -> {
            output.set(image);
            return new ImageEditService.PublishedImage("https://example.invalid/result.png", "test-result",
                    new ImageField(new URL("https://example.invalid/result.png")));
        });
        var tools = ImageEditSkill.tools(service);
        check(tools.get(0).parameters.properties.containsKey("width")
                && !tools.get(0).parameters.properties.containsKey("weight"), "width schema");
        JsonObject crop = cropArgs(SOURCE, 25, 0, 50, 100);
        crop.addProperty("_ToolCallId", "runtime");
        crop.addProperty("CustomRuntimeValue", "ignored");
        check(tools.get(0).callback.callSync(crop).toString().contains("处理成功"), "crop succeeds with injected metadata");
        check(output.get().getWidth() == 2 && output.get().getHeight() == 2, "crop pixel dimensions");
        check(output.get().getRGB(0, 0) == input.getRGB(1, 0)
                && output.get().getRGB(1, 1) == input.getRGB(2, 1), "crop correct pixels");
        cropArgsCheck(service, output, 99.9, 99.9, 0.1, 0.1, 1, 1);
        cropArgsCheck(service, output, 33.3, 0, 33.3, 100, 2, 2);
        for (int angle : new int[]{0, 90, -90, 180, 270, 360, 450}) {
            check(service.edit("rotate", rotateArgs(SOURCE, angle)).toString().contains("处理成功"), "rotation " + angle);
            BufferedImage result = output.get();
            int normalized = Math.floorMod(angle, 360);
            for (int x = 0; x < input.getWidth(); x++) for (int y = 0; y < input.getHeight(); y++) {
                int targetX = switch (normalized) {
                    case 90 -> input.getHeight() - 1 - y;
                    case 180 -> input.getWidth() - 1 - x;
                    case 270 -> y;
                    default -> x;
                };
                int targetY = switch (normalized) {
                    case 90 -> x;
                    case 180 -> input.getHeight() - 1 - y;
                    case 270 -> input.getWidth() - 1 - x;
                    default -> y;
                };
                check(result.getRGB(targetX, targetY) == input.getRGB(x, y), "rotation direction/pixels " + angle);
            }
        }
        service.edit("rotate", rotateArgs(SOURCE, 45));
        check(output.get().getWidth() == 5 && output.get().getHeight() == 5, "expanded diagonal canvas");
        check((output.get().getRGB(0, 0) >>> 24) == 0, "transparent new canvas");
        service.edit("rotate", rotateArgs(SOURCE, 22.5));
        check(output.get().getWidth() == 5 && output.get().getHeight() == 4, "decimal rotation");
        int beforeInvalid = loads.get();
        for (String invalid : new String[]{"null", "[]", "{}", "{\"source_image\":null}",
                "{\"source_image\":\"file:///secret.png\",\"angle\":90}",
                "{\"source_image\":\"<quote:1>\",\"angle\":90}",
                "{\"source_image\":\"https://example.invalid/a\",\"angle\":\"90\"}",
                "{\"source_image\":\"https://example.invalid/a\",\"angle\":1e999}"})
            check(service.edit("rotate", JsonParser.parseString(invalid)).toString().contains("参数错误"), "reject " + invalid);
        for (JsonObject invalid : List.of(cropArgs(SOURCE, -1, 0, 50, 100), cropArgs(SOURCE, 100, 0, 1, 100),
                cropArgs(SOURCE, 60, 0, 50, 100), cropArgs(SOURCE, 0, 0, 0, 100), cropArgs(SOURCE, 0, 99, 100, 2)))
            check(service.edit("crop", invalid).toString().contains("参数错误"), "reject crop bounds");
        check(loads.get() == beforeInvalid, "invalid arguments rejected before download");
        var failed = new ImageEditService(url -> { throw new IOException("fixture download failure"); }, image -> {
            throw new AssertionError("failed download must not publish");
        });
        check(failed.edit("rotate", rotateArgs(SOURCE, 90)).toString().contains("fixture download failure"), "download error returned");
        var oversized = new ImageEditService(url -> new BufferedImage(8193, 1, BufferedImage.TYPE_INT_ARGB), image -> {
            throw new AssertionError("oversized input must not publish");
        });
        check(oversized.edit("rotate", rotateArgs(SOURCE, 0)).toString().contains("尺寸超出限制"), "decoded size limit");
        var publishFailure = new ImageEditService(url -> input, image -> { throw new IOException("fixture publish failure"); });
        check(publishFailure.edit("rotate", rotateArgs(SOURCE, 90)).toString().contains("fixture publish failure"), "publish error returned");
        // 小体积 PNG 也可能声明超大尺寸，应在解码像素前检查图片头并拒绝处理。
        ByteArrayOutputStream largeHeader = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8193, 1, BufferedImage.TYPE_INT_ARGB), "png", largeHeader);
        try { ImageEditService.decode(largeHeader.toByteArray()); throw new AssertionError("oversized header accepted"); }
        catch (IOException expected) { check(expected.getMessage().contains("尺寸超出限制"), "header size limit"); }
    }

    private static void cropArgsCheck(ImageEditService service, AtomicReference<BufferedImage> output,
                                       double x, double y, double w, double h, int expectedW, int expectedH) {
        check(service.edit("crop", cropArgs(SOURCE, x, y, w, h)).toString().contains("处理成功"), "decimal crop");
        check(output.get().getWidth() == expectedW && output.get().getHeight() == expectedH, "decimal crop rounds outward");
    }

    private static void httpAndSkillLoop() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String address = "http://127.0.0.1:" + server.getAddress().getPort();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(fixture(), "png", png);
        server.createContext("/source", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, png.size());
                exchange.getResponseBody().write(png.toByteArray());
            }
        });
        server.createContext("/bad", exchange -> {
            try (exchange) { exchange.sendResponseHeaders(200, 4); exchange.getResponseBody().write(new byte[4]); }
        });
        server.createContext("/oversized", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, ImageEditService.MAX_BYTES + 1L);
                exchange.getResponseBody().write(0);
                exchange.getResponseBody().flush();
            }
        });
        Field mountsField = UniverseChannel.class.getDeclaredField("fileMounting");
        mountsField.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, File> mounts = (Map<String, File>) mountsField.get(null);
        // 通过本机 HTTP 测试服务验证实际的原生文件挂载注册表。
        server.createContext("/Universe/", exchange -> {
            try (exchange) {
                File file = mounts.get(exchange.getRequestURI().getPath().substring("/Universe/".length()));
                if (file == null || !file.isFile()) { exchange.sendResponseHeaders(404, -1); return; }
                exchange.sendResponseHeaders(200, file.length());
                Files.copy(file.toPath(), exchange.getResponseBody());
            }
        });
        String oldAddress = UniverseChannel.getOutboundHttpAddress();
        Skill oldSkill = SkillRegistry.getAll().remove("image_edit");
        Map<String, LLMFunction> tools = new HashMap<>();
        Map<String, MarkDecoupler> marks = new HashMap<>();
        ImageEdit plugin = new ImageEdit(null) {
            @Override public void registerFunction(String name, LLMFunction tool) { tools.put(name, tool); }
            @Override public void registerMarkDecoupler(String name, MarkDecoupler mark) { marks.put(name, mark); }
        };
        Path root = Files.createTempDirectory(Path.of("target"), "image-store-test-");
        var store = new ImageOutputStore(root);
        Topic topic = new Topic(new SessionConfig());
        try {
            UniverseChannel.setOutboundHttpAddress(address);
            server.start();
            plugin.onEnable();
            nativeSendPipeline(tools, marks.get("image_edit"), address);
            Skill skill = SkillRegistry.get("image_edit");
            check(skill != null && skill.mode == Skill.Mode.ON_DEMAND && tools.keySet().containsAll(skill.toolNames), "native plugin registration");
            SkillManager manager = new SkillManager(topic, List.of("image_edit"), List.of());
            check(manager.hasOnDemandSkills() && manager.getActiveToolNames().isEmpty(), "on-demand discovery");
            check(manager.activate("image_edit") && manager.getActiveToolNames().equals(skill.toolNames), "skill activation binds both tools");
            MCMessage attachment = new MCMessage();
            attachment.putMetainfo("role", "user");
            attachment.messageFields.add(new ImageField(new URL(address + "/source")));
            topic.getChatContext().getBase().add(attachment);
            AtomicInteger rounds = new AtomicInteger();
            AtomicReference<String> finalMark = new AtomicReference<>();
            LLMProvider provider = new LLMProvider() {
                @Override public CompletionsResponse completions(String model, List<Message> messages, List<LLMFunction> visible,
                        CompletionsRequest request, BufferCallback callback) {
                    check(visible.size() == 2, "two active image tools");
                    var response = new CompletionsResponse();
                    var choice = new CompletionsResponse.Choice();
                    response.choices = new CompletionsResponse.Choice[]{choice};
                    int round = rounds.getAndIncrement();
                    if (round == 0) {
                        String text = messages.stream().flatMap(m -> m.content.stream())
                                .filter(p -> p instanceof TextPiece).map(p -> ((TextPiece) p).getText())
                                .filter(t -> t.startsWith("<quoteId:")).findFirst().orElseThrow();
                        String quote = text.replace("quoteId:", "quote:");
                        call(choice, "ImageEdit_Crop", cropArgs(quote, 25, 0, 50, 100), "crop");
                    } else {
                        String result = messages.stream().filter(m -> "tool".equals(m.role))
                                .reduce((a, b) -> b).orElseThrow().toString();
                        check(result.contains("处理成功"), "successful tool result returned to model");
                        if (round == 1) {
                            var url = Pattern.compile("结果 URL：([^\\n]+)").matcher(result);
                            check(url.find(), "published result URL");
                            call(choice, "ImageEdit_Rotate", rotateArgs(url.group(1), 90), "rotate");
                        } else {
                            var mark = Pattern.compile("<image_edit:([^>]+)>").matcher(result);
                            check(mark.find(), "send marker present");
                            finalMark.set(mark.group(1));
                            choice.message.content = mark.group();
                            choice.finish_reason = "stop";
                        }
                    }
                    return response;
                }
            };
            ChatAssistant assistant = new ChatAssistant(provider, "offline");
            assistant.setChatContext(topic.getChatContext());
            for (String name : manager.getActiveToolNames()) assistant.addTool(tools.get(name));
            assistant.completions(null);
            check(rounds.get() == 3, "quote crop then result URL rotation tool loop");
            var imageMessage = marks.get("image_edit").process(finalMark.get());
            check(imageMessage.getFirst() instanceof ImageField, "marker becomes actual image message");
            BufferedImage fetched = ImageEditService.download(((ImageField) imageMessage.getFirst()).getUrl().toString());
            check(fetched.getWidth() == 2 && fetched.getHeight() == 2, "result served as valid PNG");
            expectDownloadFailure(address + "/bad", "图片格式");
            expectDownloadFailure(address + "/missing", "HTTP 404");
            expectDownloadFailure(address + "/oversized", "20MiB");
            expectDownloadFailure(address + "/Universe/missing.png", "HTTP 404");
            var published = store.publish(fixture());
            check(store.imageMessage(published.token()).getFirst() == published.image(), "marker reuses its owned ImageField");
            check(published.url().startsWith(address + "/Universe/"), "native file mount used");
            ImageField copy = new ImageField(new URL(published.url()));
            File generated = mounts.get(new URL(published.url()).getPath().substring("/Universe/".length()));
            ImageField localCopy = new ImageField(generated);
            check(localCopy.getUrl().equals(copy.getUrl()), "same local file shares one native cache entry");
            copy.repost();
            check(copy.getUrl().toString().equals(published.url()), "managed URL is retained without re-downloading");
            clean(published.image());
            check(generated.isFile(), "one remaining ImageField keeps shared file alive");
            clean(copy);
            check(generated.isFile(), "local file owner also keeps shared cache alive");
            clean(localCopy);
            check(!generated.exists() && !mounts.containsValue(generated), "last owner deletes file and native mount");
            UniverseChannel.releaseRepost(new URL(published.url())); // 重复清理不会产生副作用。
            lifecycleOwnership(store, mounts, address, topic);
            check(store.imageMessage("../../source").toString().contains("不存在"), "unknown token rejected");
            check(manager.deactivate("image_edit") && manager.getActiveToolNames().isEmpty(), "skill deactivation");
            plugin.onDisable();
            check(ImageEditService.download(((ImageField) imageMessage.getFirst()).getUrl().toString()).getWidth() == 2,
                    "plugin stop does not delete images still owned by the conversation");
        } finally {
            plugin.onDisable();
            for (MCMessage message : topic.getChatContext().getBase()) for (FileField file : message.retainedFiles) clean(file);
            server.stop(0);
            UniverseChannel.setOutboundHttpAddress(oldAddress);
            SkillRegistry.getAll().remove("image_edit");
            if (oldSkill != null) SkillRegistry.register(oldSkill);
            Files.deleteIfExists(root);
        }
    }

    /** 验证实际的 replyTurn、流式回调、标记解析和消息路由，不向外部发送消息。 */
    private static void nativeSendPipeline(Map<String, LLMFunction> tools, MarkDecoupler marker, String address) throws Exception {
        Field toolsField = AIChat.class.getDeclaredField("llmFunctions");
        toolsField.setAccessible(true);
        @SuppressWarnings("unchecked") Multimap<String, LLMFunction> registry = (Multimap<String, LLMFunction>) toolsField.get(null);
        Map<String, List<LLMFunction>> previousTools = new HashMap<>();
        for (String name : tools.keySet()) previousTools.put(name, new ArrayList<>(registry.removeAll(name)));
        Field marksField = AIChat.class.getDeclaredField("markDecouplers");
        marksField.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, MarkDecoupler> markRegistry = (Map<String, MarkDecoupler>) marksField.get(null);
        MarkDecoupler previousMarker = markRegistry.get("image_edit");
        Field historyField = MessageChannelManager.class.getDeclaredField("messageHistory");
        historyField.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, MessageList> history = (Map<String, MessageList>) historyField.get(null);
        CapturingChannel channel = new CapturingChannel();
        String sessionId = channel.ID + ":test";
        MessageChannel previousChannel = MessageChannelManager.MessageChannels.put(channel.ID, channel);
        MessageList previousHistory = history.remove(sessionId);
        AIChat agent = new AIChat();
        setField(agent, "globalCfg", new com.nekoyu.Universe.AIChat.Config());
        var replyTurn = AIChat.class.getDeclaredMethod("replyTurn", Topic.class);
        replyTurn.setAccessible(true);
        try {
            tools.forEach(AIChat::registerFunction);
            AIChat.registerMarkDecoupler("image_edit", marker);
            for (boolean characterChunks : new boolean[]{false, true}) {
                channel.sent.clear();
                Topic topic = new Topic(new SessionConfig());
                SkillManager manager = new SkillManager(topic, List.of("image_edit"), List.of());
                manager.activate("image_edit");
                setField(topic, "skillManager", manager);
                MCMessage trigger = new MCMessage();
                trigger.sessionId = sessionId;
                trigger.sender.setLocationId("fixture:user");
                trigger.sender.setPlatform("fixture");
                trigger.receiver = channel.loginAccount;
                setField(topic, "lastTrigger", trigger);
                AtomicInteger rounds = new AtomicInteger();
                LLMProvider provider = new LLMProvider() {
                    @Override public CompletionsResponse completions(String model, List<Message> messages, List<LLMFunction> functions,
                            CompletionsRequest request, BufferCallback callback) {
                        check(functions.size() == 2, "registered skill tools reach actual replyTurn");
                        var response = new CompletionsResponse();
                        var choice = new CompletionsResponse.Choice();
                        response.choices = new CompletionsResponse.Choice[]{choice};
                        if (rounds.getAndIncrement() == 0) {
                            call(choice, "ImageEdit_Rotate", rotateArgs(address + "/source", 90), "native-send");
                        } else {
                            String result = messages.stream().filter(m -> "tool".equals(m.role)).findFirst().orElseThrow().toString();
                            var match = Pattern.compile("<image_edit:[^>]+>").matcher(result);
                            check(match.find(), "tool generated a real send marker");
                            String mark = match.group();
                            String answer = "处理好了。\n\n" + mark + "\n\n这是旋转后的图片。";
                            if (characterChunks) {
                                for (char part : answer.toCharArray()) callback.onCompletion(String.valueOf(part));
                            } else {
                                callback.onCompletion("处理好了。\n\n");
                                callback.onCompletion(mark.substring(0, 8));
                                callback.onCompletion(mark.substring(8, mark.length() - 1));
                                callback.onCompletion(">\n\n这是旋转后的图片。");
                            }
                            choice.message.content = answer;
                            choice.finish_reason = "stop";
                        }
                        return response;
                    }
                };
                setField(topic, "provider", provider);
                replyTurn.invoke(agent, topic);
                List<ImageField> sentImages = channel.sent.stream().flatMap(MFChain::stream)
                        .filter(f -> f instanceof ImageField).map(f -> (ImageField) f).toList();
                check(rounds.get() == 2 && sentImages.size() == 1, "actual streaming reply sends exactly one image");
                check(channel.sent.stream().noneMatch(m -> m.toString().contains("<image_edit:")), "image marker is decoded before sending");
                BufferedImage downloaded = ImageEditService.download(sentImages.get(0).getUrl().toString());
                check(downloaded.getWidth() == 2 && downloaded.getHeight() == 4, "sent image URL serves rotated PNG");
                MCMessage toolResult = topic.getChatContext().getBase().stream()
                        .filter(m -> "tool".equals(m.getMetainfo("role"))).findFirst().orElseThrow();
                check(toolResult.retainedFiles.get(0) == sentImages.get(0), "persisted conversation retains the sent image owner");
                check(history.get(sessionId).stream().anyMatch(m -> m.messageFields.contains(sentImages.get(0))), "message history retains actual sent image");
                history.remove(sessionId);
                clean(sentImages.get(0));
            }
        } finally {
            agent.stop();
            for (String name : tools.keySet()) { registry.removeAll(name); registry.putAll(name, previousTools.get(name)); }
            if (previousMarker == null) markRegistry.remove("image_edit"); else markRegistry.put("image_edit", previousMarker);
            if (previousChannel == null) MessageChannelManager.MessageChannels.remove(channel.ID);
            else MessageChannelManager.MessageChannels.put(channel.ID, previousChannel);
            history.remove(sessionId);
            if (previousHistory != null) history.put(sessionId, previousHistory);
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class CapturingChannel extends MessageChannel implements SessionChat {
        final List<MFChain> sent = new ArrayList<>();
        private final Session session = new Session();
        CapturingChannel() {
            super("ImageEditSendFixture");
            session.setName("offline test");
            loginAccount = new Account();
            loginAccount.setName("fixture bot");
            loginAccount.setLocationId("fixture:bot");
        }
        @Override public void load() {}
        @Override public void stop() {}
        @Override public Session getSession(String id) { return session; }
        @Override public int sendMessage(String id, MFChain message) {
            check("test".equals(id), "global session ID is routed to local channel ID");
            MFChain captured = new MFChain();
            captured.addAll(message);
            sent.add(captured);
            return sent.size();
        }
    }

    private static void lifecycleOwnership(ImageOutputStore store, Map<String, File> mounts,
                                           String address, Topic topic) throws Exception {
        List<MCMessage> toolResults = topic.getChatContext().getBase().stream()
                .filter(m -> "tool".equals(m.getMetainfo("role"))).toList();
        check(toolResults.size() == 2 && toolResults.stream().allMatch(m -> m.retainedFiles.size() == 1),
                "canonical tool results own images even with text-only API content");
        var copyMessage = AIChat.class.getDeclaredMethod("copyTurnMessage", MCMessage.class);
        copyMessage.setAccessible(true);
        MCMessage copied = (MCMessage) copyMessage.invoke(null, toolResults.get(0));
        check(copied.retainedFiles.get(0) == toolResults.get(0).retainedFiles.get(0), "turn persistence preserves file ownership");
        check(!new Gson().toJson(copied).contains("retainedFiles"), "retained file owners are not serialized");

        var published = store.publish(fixture());
        ImageField owner = published.image();
        File local = mounts.get(new URL(published.url()).getPath().substring("/Universe/".length()));
        var resultsField = ImageOutputStore.class.getDeclaredField("results");
        resultsField.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, WeakReference<ImageField>> index =
                (Map<String, WeakReference<ImageField>>) resultsField.get(store);
        check(index.get(published.token()).get() == owner, "marker registry holds only a weak reference");
        index.get(published.token()).clear();
        index.get(published.token()).enqueue();
        check(store.imageMessage(published.token()).toString().contains("已释放") && !index.containsKey(published.token()),
                "collected marker index is removed");
        check(local.isFile(), "weak marker removal does not control file lifetime");
        owner.setUrl(new URL(address + "/source"));
        check(!local.exists(), "changing owned URL releases its old file");
        clean(owner);

        // 现有远程文件转存也应保留别名引用，并在释放时移除来源缓存键。
        Files.createDirectories(Path.of("cache", "reposts"));
        ImageField remote = new ImageField(new URL(address + "/source"));
        remote.repost();
        URL cachedUrl = remote.getUrl();
        File cached = mounts.get(cachedUrl.getPath().substring("/Universe/".length()));
        ImageField alias = new ImageField(cachedUrl);
        clean(remote);
        check(cached.isFile(), "remote repost alias keeps cached file alive");
        clean(alias);
        check(!cached.exists(), "remote cached file deleted after final reference");
        ImageField again = new ImageField(new URL(address + "/source"));
        again.repost();
        check(!again.getUrl().equals(cachedUrl), "released source cache key does not reuse a deleted file");
        clean(again);
    }

    private static void clean(FileField file) throws Exception {
        Field field = FileField.class.getDeclaredField("cleanable");
        field.setAccessible(true);
        ((Cleaner.Cleanable) field.get(file)).clean(); // 显式调用实际的 Cleaner 清理逻辑，避免依赖 GC 时机。
    }

    private static void call(CompletionsResponse.Choice choice, String name, JsonObject args, String id) {
        Tool_call call = new Tool_call();
        call.id = id;
        call.type = "function";
        call.function.name = name;
        call.function.arguments = args.toString();
        choice.message.tool_calls = new Tool_call[]{call};
        choice.finish_reason = "tool_calls";
    }

    private static void expectDownloadFailure(String source, String reason) throws IOException {
        try { ImageEditService.download(source); throw new AssertionError("download should fail"); }
        catch (IOException expected) { check(expected.getMessage().contains(reason), "controlled download failure " + reason + ": " + expected.getMessage()); }
    }

    private static BufferedImage fixture() {
        BufferedImage image = new BufferedImage(4, 2, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 4; x++) for (int y = 0; y < 2; y++)
            image.setRGB(x, y, (x == 0 && y == 0 ? 0x80000000 : 0xff000000) | ((x + 1) * 40 << 16) | ((y + 1) * 50 << 8));
        return image;
    }

    private static JsonObject cropArgs(String source, double x, double y, double width, double height) {
        JsonObject fields = new JsonObject(), position = new JsonObject();
        fields.addProperty("source_image", source);
        position.addProperty("x", x); position.addProperty("y", y);
        fields.add("top_left_position", position);
        fields.addProperty("width", width); fields.addProperty("height", height);
        return fields;
    }

    private static JsonObject rotateArgs(String source, double angle) {
        JsonObject fields = new JsonObject();
        fields.addProperty("source_image", source); fields.addProperty("angle", angle);
        return fields;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

package com.nekoyu.Universe.AIChat;

import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Message;
import java.util.*;
import java.util.regex.Pattern;

/** 只保留实际输入或工具返回的来源片段，不把整个研究对话复制给入库模型。 */
final class ResearchEvidence {
    // 中文句读和括号是正文边界，不能只在末尾清理，否则会吞入日期、正文甚至下一条链接。
    private static final Pattern URL = Pattern.compile(
            "https?://[^\\s<>\"“”‘’`，。；、！？：（）【】《》「」『』]+", Pattern.CASE_INSENSITIVE);
    private final Map<String, String> evidence = new LinkedHashMap<>();
    private final Set<String> observedTools = new HashSet<>();

    ResearchEvidence(String input) { observe("输入提供的材料，未核对原文：\n" + input); }

    static Set<String> urls(String text) {
        Set<String> urls = new LinkedHashSet<>();
        var matcher = URL.matcher(text);
        while (matcher.find()) urls.add(matcher.group().replaceAll("[)\\]}>，。；、,;.!?]+$", ""));
        return urls;
    }

    void observe(String text) {
        for (String url : urls(text)) {
            int offset = text.indexOf(url);
            evidence.put(url, text.substring(Math.max(0, offset - 500), Math.min(text.length(), offset + 3500)));
        }
    }

    void observeTools(List<Message> messages) {
        Map<String, String> args = new HashMap<>();
        for (Message message : messages) if (message.tool_calls != null)
            for (var call : message.tool_calls) if (call.function != null)
                args.put(call.id, Objects.toString(call.function.arguments, ""));
        for (Message message : messages) {
            if (!"tool".equals(message.role) || !observedTools.add(message.tool_call_id)) continue;
            String text = "工具返回内容，证据范围以正文为准：\n" + message;
            observe(text);
            // 页面读取工具的返回正文不一定重复网址，仍保留实际调用参数中的链接。
            for (String url : urls(args.getOrDefault(message.tool_call_id, "")))
                evidence.put(url, text.substring(0, Math.min(4000, text.length())));
        }
    }

    Map<String, String> forReport(String report) {
        Map<String, String> selected = new LinkedHashMap<>();
        for (String url : urls(report)) if (evidence.containsKey(url)) selected.put(url, evidence.get(url));
        return Collections.unmodifiableMap(selected);
    }
}

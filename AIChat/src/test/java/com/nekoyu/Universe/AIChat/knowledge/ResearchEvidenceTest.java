package com.nekoyu.Universe.AIChat.knowledge;

import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Message;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.nekoyu.Universe.AIChat.knowledge.LibrarianResearchTest.*;
import static org.junit.jupiter.api.Assertions.*;

class ResearchEvidenceTest {
    private static final List<String> URLS = List.of(
            "https://www.bilibili.com/video/BV1wC8K6AEv5/",
            "https://www.bilibili.com/video/BV1pArtBJE9d/",
            "https://www.bilibili.com/video/BV1UPy3BsE9r/",
            "https://www.ldshop.gg/tw/blog/zenless-zone-zero/ye-shunguang-skill-kit.html",
            "https://www.youxiabc.com/p/53268.html");
    private static final String REPORT = """
            混淆来源线索：如“鸣潮清宵对比绝区零叶瞬光”（%s）、“神游对比叶瞬光”（2026 年 1 月 11 日，%s）、“官方明示叶瞬光职业属性”（%s）。这类标题很可能是“把角色记错”的原因。
            第三方/社区说法（仅摘要，未读原文）：培养建议（%s，2025-12-15；%s，2026-06-17，标题称“绝区零 3.0”）。这些属于来源方声称。
            """.formatted(URLS.toArray());

    @Test void extractsSeparateUrlsFromChineseReportPunctuation() {
        assertEquals(new LinkedHashSet<>(URLS), ResearchEvidence.urls(REPORT));
    }

    @Test void reportSelectionMatchesToolEvidenceAndStillExcludesUnsupportedUrls() {
        ResearchEvidence evidence = observedEvidence();
        Map<String, String> selected = evidence.forReport(REPORT + "\n无工具证据：https://invented.invalid/source");

        assertEquals(new LinkedHashSet<>(URLS), selected.keySet());
        assertTrue(selected.values().stream().allMatch(text -> text.contains("实际搜索摘要")));
        assertFalse(selected.containsKey("https://example.invalid/not-cited"));
        assertFalse(selected.containsKey("https://invented.invalid/source"));
    }

    @Test void preservesCaseEncodingAndQuerySeparators() {
        List<String> urls = List.of(
                "https://wiki.biligame.com/zzz/%E5%8F%B6%E7%9E%AC%E5%85%89",
                "https://example.invalid/CaseSensitive?q=a%EF%BC%8Cb&items=one,two;three&next=x%3By#Section");
        String text = "【" + urls.get(0) + "】；`" + urls.get(1) + "`，随后说明。";

        assertEquals(new LinkedHashSet<>(urls), ResearchEvidence.urls(text));
    }

    @Test void ingestionAcceptsAllFiveObservedReportUrlsButRejectsInventedUrl() {
        List<KnowledgeSources.Source> sources = new ArrayList<>();
        URLS.forEach(url -> sources.add(new KnowledgeSources.Source(url, "搜索标题", "只有搜索摘要，未核对原文")));
        sources.add(new KnowledgeSources.Source("https://invented.invalid/source", "假来源", "无工具证据"));
        var model = provider((messages, request) -> response(new KnowledgeSources(sources).toJson(), "stop"));
        List<KnowledgeSources.Source> indexed = new ArrayList<>();

        new KnowledgeIngestion(model, "offline-ingestion", indexed::add)
                .ingest("研究角色归属", REPORT, observedEvidence().forReport(REPORT));

        assertEquals(URLS, indexed.stream().map(KnowledgeSources.Source::url).toList());
    }

    private static ResearchEvidence observedEvidence() {
        ResearchEvidence evidence = new ResearchEvidence("研究角色归属");
        Message tool = new Message("实际搜索摘要：\n" + String.join("\n", URLS)
                + "\nhttps://example.invalid/not-cited");
        tool.role = "tool";
        tool.tool_call_id = "search-result";
        evidence.observeTools(List.of(tool));
        return evidence;
    }
}

package com.nekoyu.Universe.AIChat.knowledge;

import com.google.gson.JsonObject;
import java.util.Map;
import java.util.UUID;

/** 一次完整研究的任务、报告与来源线索。 */
public final class Researches {
    private final UUID id;
    private final String topic;
    private final String question;
    private final String report;
    private final Map<String, String> evidence;
    private final long completedAt;
    private final long createdAt;
    private final JsonObject lastRequest;

    public Researches(String topic, String question, String report, Map<String, String> evidence, long completedAt) {
        this(UUID.randomUUID(), topic, question, report, evidence, completedAt, completedAt, null);
    }

    public Researches(UUID id, String topic, String question, String report, Map<String, String> evidence,
                      long createdAt, long completedAt, JsonObject lastRequest) {
        this.id = id;
        this.topic = topic;
        this.question = question;
        this.report = report;
        this.evidence = Map.copyOf(evidence);
        this.completedAt = completedAt;
        this.createdAt = createdAt;
        this.lastRequest = lastRequest == null ? null : lastRequest.deepCopy();
    }

    public UUID id() { return id; }
    public String topic() { return topic; }
    public String question() { return question; }
    public String report() { return report; }
    public Map<String, String> evidence() { return evidence; }
    public long completedAt() { return completedAt; }
    public long createdAt() { return createdAt; }
    public JsonObject lastRequest() { return lastRequest == null ? null : lastRequest.deepCopy(); }
}

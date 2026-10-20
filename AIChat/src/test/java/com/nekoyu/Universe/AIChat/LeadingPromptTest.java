package com.nekoyu.Universe.AIChat;

import com.nekoyu.Universe.AIChat.knowledge.StructuredPromptTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LeadingPromptTest {
    @Test void leadingSendsItsCompleteContract() {
        try (DriveMessageTest.Fixture fixture = new DriveMessageTest.Fixture(false)) {
            fixture.ai.startReplyService();
            fixture.ai.handleSessionMessage(fixture.config, fixture.provider, fixture.user("ordinary question"));
            fixture.time.advance(5_000);
            String prompt = StructuredPromptTest.assertPrompt(fixture.annotator.calls.get(0), StructuredOutputs.leading());
            assertTrue(prompt.contains("原始消息 ID，允许负数"));
            assertTrue(prompt.contains("location_id 必须出现，非特定用户查询时使用 null"));
            assertTrue(prompt.contains("最多 500 字") && prompt.contains("最多 200 字"));
            assertTrue(prompt.contains("\"should_reply\":false,\"addressed_message_ids\":[]"));
        }
    }

}

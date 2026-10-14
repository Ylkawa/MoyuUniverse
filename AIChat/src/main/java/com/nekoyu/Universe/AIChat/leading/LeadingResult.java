package com.nekoyu.Universe.AIChat.leading;

import java.util.Set;

/** 本批消息的回复决策、对话归属及已完成的检索注解。 */
public record LeadingResult(boolean shouldReply, Set<Long> addressedMessageIds, String annotation) {
    public LeadingResult {
        addressedMessageIds = Set.copyOf(addressedMessageIds);
    }
}

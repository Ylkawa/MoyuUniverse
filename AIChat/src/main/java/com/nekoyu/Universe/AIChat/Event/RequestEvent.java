package com.nekoyu.Universe.AIChat.Event;

import com.nekoyu.Universe.AIChat.Skill.SkillManager;
import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.Context;

import java.util.HashMap;
import java.util.Map;

public class RequestEvent {
    public Map<String, String> placeholders = new HashMap<>();
    public Context messageList;
    public String sessionId;
    public String locationId;
    public SkillManager skillManager;
}

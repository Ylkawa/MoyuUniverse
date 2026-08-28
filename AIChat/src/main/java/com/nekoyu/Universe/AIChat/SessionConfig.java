package com.nekoyu.Universe.AIChat;

import java.util.List;

public class SessionConfig {
    String SessionId;
    String PromptFirst = "";
    String PromptLast = "";
    String Provider;
    String Trigger;
    String Model;
    String[] Tools;
    String Keyword;
    boolean enable_thinking = false;
    List<String> subAgents;
    boolean nativeImage;
    int maxTokens = 20000;
    String[] skills;
    String[] availableSkills;
}

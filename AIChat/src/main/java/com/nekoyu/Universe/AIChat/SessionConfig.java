package com.nekoyu.Universe.AIChat;

public class SessionConfig {
    String SessionId;
    String PromptFirst;
    String PromptLast;
    String Provider;
    String Trigger;
    String Model;
    String[] Tools;
    String Keyword;
    boolean enable_thinking = false;
    boolean nativeImage;
    int maxTokens = 100000;
}

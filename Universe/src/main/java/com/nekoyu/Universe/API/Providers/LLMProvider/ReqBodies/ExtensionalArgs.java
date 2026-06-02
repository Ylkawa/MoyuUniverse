package com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies;

import com.nekoyu.Universe.API.MessageChannel.Account;

import java.util.HashMap;
import java.util.Map;

public class ExtensionalArgs {
    public Map<String, String> placeholders = new HashMap<>();
    public boolean enable_thinking = false;
    public String systemPromptFirst = null;
    public String systemPromptLast = null;
    public Account assistant = null; // 通过指定Assistant的Account自动在MessageList中区分Role
}

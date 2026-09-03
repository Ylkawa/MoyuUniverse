package com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies;

import java.util.HashMap;
import java.util.Map;

public class CompletionsRequest {
    public Map<String, String> placeholders = new HashMap<>();
    public boolean enable_thinking = false;
}
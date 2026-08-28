package com.nekoyu.Universe.AIChat.Skill;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Skill {
    public enum Mode {
        ALWAYS,
        ON_DEMAND
    }

    public String id;
    public String name;
    public String description;
    public Mode mode = Mode.ON_DEMAND;
    public List<String> toolNames = new ArrayList<>();
    public String systemPrompt = "";
    public Map<String, String> placeholders = new HashMap<>();
}

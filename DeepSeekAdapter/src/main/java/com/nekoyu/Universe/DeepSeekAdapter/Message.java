package com.nekoyu.Universe.DeepSeekAdapter;

public class Message {
    public String role;
    public String content;
    public String tool_call_id;
    public Tool_call[] tool_calls;
}
package com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies;

public class Message {
    public String role;
    public String tool_call_id;
    public Tool_call[] tool_calls;
}
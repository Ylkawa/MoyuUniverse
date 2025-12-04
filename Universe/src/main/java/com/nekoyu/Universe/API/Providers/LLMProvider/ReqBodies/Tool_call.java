package com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies;

public class Tool_call {
    public int index;
    public String id;
    public String type;
    public Function function;

    public Tool_call() {
        function = new Function();
    }

    public static class Function {
        public String name;
        public String arguments;
    }
}
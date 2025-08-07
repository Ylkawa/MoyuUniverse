package com.nekoyu.Universe.DeepSeekAdapter;

public class Tool_call {
    public int index;
    public String id;
    public String type;
    public Function function;

    public static class Function {
        public String name;
        public String arguments;
    }
}
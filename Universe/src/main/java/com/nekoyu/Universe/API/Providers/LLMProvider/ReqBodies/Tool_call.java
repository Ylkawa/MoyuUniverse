package com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies;

public class Tool_call {
    public int index;
    public String id;
    public String type;
    public Function function;
    /** Gemini 加密思维签名等厂商扩展字段；null 时不序列化，回传时必须原样保留 */
    public ExtraContent extra_content;

    public Tool_call() {
        function = new Function();
    }

    public static class Function {
        public String name;
        public String arguments;
    }
}
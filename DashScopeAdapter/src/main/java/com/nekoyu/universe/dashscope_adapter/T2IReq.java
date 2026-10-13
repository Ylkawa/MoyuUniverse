package com.nekoyu.universe.dashscope_adapter;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** 保留原文生图请求结构；原有调用链尚未实现，当前 Channel 会明确报错。 */
public final class T2IReq {
    public String model;
    public Input input = new Input();
    public Parameters parameters = new Parameters();
    public static final class Input {
        public List<Message> messages = new ArrayList<>();
        public static final class Message {
            public String role;
            public List<Piece> content = new ArrayList<>();
            public abstract static class Piece {}
            public static final class Text extends Piece { public String text; public Text(String text) { this.text = text; } }
            public static final class Image extends Piece { public URL image; public Image(URL image) { this.image = image; } }
        }
    }
    public static final class Parameters {
        public int n = 1;
        public String negative_prompt;
        public boolean prompt_extend = true;
        public boolean watermark = false;
        public String size = "2048*2048";
    }
}

package com.nekoyu.Universe.DeepSeekAdapter.SpecialDesignedReqRespBodies;

import java.util.ArrayList;
import java.util.List;

public class qwen3_asr_flash_req {
    public String model;
    public Input input;
    public Parameters parameters;

    public qwen3_asr_flash_req() {
        input = new Input();
        parameters = new Parameters();
    }

    public static class Input {
        public List<Message> messages;

        public Input() {
            messages = new ArrayList<>();
        }

        public static class Message {
            public List<Ctt> content;
            public String role;

            public Message() {
                content = new ArrayList<>();
            }

            public static class Ctt {
                public String audio;
                public String text;
            }
        }
    }

    public static class Parameters {
        public Asr_options asr_options;

        public Parameters() {
            asr_options = new Asr_options();
        }

        public static class Asr_options {
            public boolean enable_itn;
        }
    }
}
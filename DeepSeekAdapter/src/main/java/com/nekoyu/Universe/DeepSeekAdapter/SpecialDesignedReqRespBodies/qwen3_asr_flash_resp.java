package com.nekoyu.Universe.DeepSeekAdapter.SpecialDesignedReqRespBodies;

public class qwen3_asr_flash_resp {
    public Output output;
    public Usage usage;
    public String request_id;

    public static class Output {
        public Choice[] choices;

        public static class Choice {
            public String finish_reason;
            public Message message;
            public String role;

            public static class Message {
                public Annotations[] annotations;
                public Content[] content;

                public static class Annotations {
                    public String language;
                    public String type;
                    public String emotion;
                }

                public static class Content {
                    public String text;
                }
            }
        }
    }

    public static class Usage {
        public Text_tokens input_tokens_details;
        public Text_tokens output_tokens_details;
        public int seconds;

        public static class Text_tokens {
            public int text_tokens;
        }
    }
}

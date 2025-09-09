package com.nekoyu.Universe.API.MessageChannel.MessageField;

public abstract class MsgField {
    public String type;

    /**
     * 以文本形式呈现本段的内容
     * 为大语言模型应用设计的功能
     * @return 本段内容描述
     */
    public abstract String getAsString();
}

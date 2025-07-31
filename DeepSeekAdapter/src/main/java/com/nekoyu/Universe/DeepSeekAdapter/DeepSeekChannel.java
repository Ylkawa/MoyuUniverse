package com.nekoyu.Universe.DeepSeekAdapter;

public class DeepSeekChannel {
    public String id;
    private String base_url;
    private String api_key;

    public DeepSeekChannel(String id, String base_url, String api_key) {
        this.id = id;
        this.base_url = base_url;
        this.api_key = api_key;
    }

    public Assistant getAssistant(String model) {
        return new Assistant(this, model);
    }

    public AssistantResponse request(MessageList messageList) {
        var ar = new AssistantRequest();
        ar.messages = messageList.getMessageList().toArray(new Message[0]);
        return null;
    }
}

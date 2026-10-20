package com.nekoyu.Universe.AIChat;

/** 跨包研究测试清理已注册的临时工具。 */
public final class RegisteredToolsTestSupport {
    private RegisteredToolsTestSupport() {}

    public static void unregisterTools(String group) {
        AIChat.llmFunctions.removeAll(group);
    }
}

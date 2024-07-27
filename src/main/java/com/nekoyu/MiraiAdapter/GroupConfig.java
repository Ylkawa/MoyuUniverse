package com.nekoyu.MiraiAdapter;

public class GroupConfig {
    String name;
    boolean confessTalk;
    boolean allowMemberInvite;
    boolean autoApprove;
    boolean anonymousChat;
    boolean muteAll;

    public String getName() {
        return name;
    }

    public boolean isConfessTalk() {
        return confessTalk;
    }

    public boolean isAllowMemberInvite() {
        return allowMemberInvite;
    }

    public boolean isAutoApprove() {
        return autoApprove;
    }

    public boolean isAnonymousChat() {
        return anonymousChat;
    }

    public boolean isMuteAll() {
        return muteAll;
    }
}

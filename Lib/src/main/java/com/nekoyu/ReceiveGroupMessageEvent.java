package com.nekoyu;

public class ReceiveGroupMessageEvent {
    String GroupName;
    String GroupID;
    String QQID;
    String QQName;
    String message;
    boolean isBeAted;

    public String getGroupName() {
        return GroupName;
    }

    public void setGroupName(String groupName) {
        GroupName = groupName;
    }

    public String getGroupID() {
        return GroupID;
    }

    public void setGroupID(String groupID) {
        GroupID = groupID;
    }

    public String getQQID() {
        return QQID;
    }

    public void setQQID(String QQID) {
        this.QQID = QQID;
    }

    public String getQQName() {
        return QQName;
    }

    public void setQQName(String QQName) {
        this.QQName = QQName;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public boolean isBeAted() {
        return isBeAted;
    }

    public void setBeAted(boolean beAted) {
        isBeAted = beAted;
    }
}

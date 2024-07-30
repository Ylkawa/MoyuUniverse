package com.nekoyu;

public class ReceiveGroupMessageEvent {
    String GroupName;
    long GroupID;
    long QQID;
    String QQName;
    String message;

    public String getGroupName() {
        return GroupName;
    }

    public void setGroupName(String groupName) {
        GroupName = groupName;
    }

    public long getGroupID() {
        return GroupID;
    }

    public void setGroupID(long groupID) {
        GroupID = groupID;
    }

    public long getQQID() {
        return QQID;
    }

    public void setQQID(long QQID) {
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
}

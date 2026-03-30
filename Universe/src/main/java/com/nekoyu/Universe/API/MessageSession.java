package com.nekoyu.Universe.API;

import com.nekoyu.Universe.API.MessageChannel.MessageField.MsgField;

import java.util.LinkedList;

public interface MessageSession {
    void sendMessage(LinkedList<MsgField> message);
}

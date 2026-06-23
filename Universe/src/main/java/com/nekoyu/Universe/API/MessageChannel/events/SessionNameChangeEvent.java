package com.nekoyu.Universe.API.MessageChannel.events;

import com.nekoyu.Universe.API.MessageChannel.Account;
import com.nekoyu.Universe.API.MessageChannel.Session;

public class SessionNameChangeEvent extends MCEvent {
    public Session session;
    public Account operator;
    public String previousName;
    public String newName;
}

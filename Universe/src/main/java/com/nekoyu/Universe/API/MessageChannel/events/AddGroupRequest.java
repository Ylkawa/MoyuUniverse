package com.nekoyu.Universe.API.MessageChannel.events;

import com.nekoyu.Universe.API.MessageChannel.Account;
import com.nekoyu.Universe.API.MessageChannel.Group;

public class AddGroupRequest extends MCEvent {
    public Account requestor;
    public Group target;
    public String commit;
}

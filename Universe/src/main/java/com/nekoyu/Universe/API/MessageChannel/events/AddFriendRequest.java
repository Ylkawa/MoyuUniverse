package com.nekoyu.Universe.API.MessageChannel.events;

import com.nekoyu.Universe.API.MessageChannel.Account;

public class AddFriendRequest extends MCEvent {
    public Account requestor;
    public Account target;
    public String commit;
}

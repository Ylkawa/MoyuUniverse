package com.nekoyu.Universe.API.MessageChannel.events;

import com.nekoyu.Universe.API.MessageChannel.Account;
import com.nekoyu.Universe.API.MessageChannel.Features.SessionManagement;

public class AddFriendRequest extends MCEvent {
    public Account requestor;
    public Account target;
    public String commit;

    public void agree() {
        ((SessionManagement) messageChannel).approvalAddFriend(this, true);
    }

    public void reject() {
        ((SessionManagement) messageChannel).approvalAddFriend(this, false);
    }
}

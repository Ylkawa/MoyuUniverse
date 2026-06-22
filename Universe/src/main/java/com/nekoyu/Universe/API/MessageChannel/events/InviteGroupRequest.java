package com.nekoyu.Universe.API.MessageChannel.events;

import com.nekoyu.Universe.API.MessageChannel.Account;
import com.nekoyu.Universe.API.MessageChannel.Features.SessionManagement;
import com.nekoyu.Universe.API.MessageChannel.Group;

public class InviteGroupRequest extends MCEvent {
    public Account requestor;
    public Group target;
    public String commit;

    public void agree() {
        ((SessionManagement) messageChannel).approvalInviteGroup(this, true);
    }

    public void reject() {
        ((SessionManagement) messageChannel).approvalInviteGroup(this, false);
    }
}

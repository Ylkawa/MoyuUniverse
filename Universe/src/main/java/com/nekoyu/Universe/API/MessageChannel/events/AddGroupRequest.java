package com.nekoyu.Universe.API.MessageChannel.events;

import com.nekoyu.Universe.API.MessageChannel.Account;
import com.nekoyu.Universe.API.MessageChannel.Features.SessionManagement;
import com.nekoyu.Universe.API.MessageChannel.Group;

public class AddGroupRequest extends MCEvent {
    public Account requestor;
    public Group target;
    public String commit;

    public void agree() {
        ((SessionManagement) messageChannel).approvalAddGroup(this, true, null);
    }

    public void reject(String reason) {
        ((SessionManagement) messageChannel).approvalAddGroup(this, false, reason);
    }
}

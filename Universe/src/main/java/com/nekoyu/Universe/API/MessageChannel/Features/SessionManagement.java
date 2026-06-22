package com.nekoyu.Universe.API.MessageChannel.Features;

import com.nekoyu.Universe.API.MessageChannel.events.AddFriendRequest;
import com.nekoyu.Universe.API.MessageChannel.events.AddGroupRequest;
import com.nekoyu.Universe.API.MessageChannel.events.InviteGroupRequest;

public interface SessionManagement {
    void approvalAddGroup(AddGroupRequest request, boolean agree, String reason);
    void approvalAddFriend(AddFriendRequest request, boolean agree);
    void approvalInviteGroup(InviteGroupRequest request, boolean agree);
}

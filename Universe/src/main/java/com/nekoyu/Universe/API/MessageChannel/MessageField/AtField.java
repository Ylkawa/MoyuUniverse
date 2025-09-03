package com.nekoyu.Universe.API.MessageChannel.MessageField;

import com.nekoyu.Universe.API.MessageChannel.Account;

public class AtField extends MsgField {
    Account target;

    public AtField(Account target) {
        this.target = target;
    }
}

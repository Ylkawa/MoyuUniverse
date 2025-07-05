package com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.Request;

import com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.OBRequest;

public class SendGroupMessage extends OBRequest {
    public String user_id;
    public String message;
    public boolean auto_escape = false;

    public SendGroupMessage() {
        action = "send_group_msg";

        params.put("user_id",  user_id);
        params.put("message", message);
        params.put("auto_escape", auto_escape);
    }
}

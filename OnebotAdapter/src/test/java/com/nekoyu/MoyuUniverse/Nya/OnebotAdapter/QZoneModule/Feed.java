package com.nekoyu.MoyuUniverse.Nya.OnebotAdapter.QZoneModule;

import com.nekoyu.Universe.API.MessageChannel.Account;

import java.util.List;

public class Feed {
    Account poster; // 动态的发布者，可以通过
    String key; // 动态的唯一识别 key
    Feed forward; // 如果动态是转发过来的，这里的 forward 指向被转发的原动态，反之则为 null
    List<Comment> comments;

    public static class Comment {
        Account sender;
    }
}

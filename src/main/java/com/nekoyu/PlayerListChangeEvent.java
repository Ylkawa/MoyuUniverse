package com.nekoyu;

import java.util.Collection;

public class PlayerListChangeEvent {
    String ChangeMethod;
    String PlayerName;
    Collection collection;

    public PlayerListChangeEvent(String ChangeMethod, String PlayerName, Collection collection){
        this.ChangeMethod = ChangeMethod;
        this.PlayerName = PlayerName;
        this.collection = collection;
    }
}
package com.nekoyu;

import java.util.ArrayList;

public class PlayerListChangeEvent {
    String ChangeMethod;
    String PlayerName;
    ArrayList<String> PlayerList;

    public PlayerListChangeEvent(String ChangeMethod, String PlayerName, ArrayList<String> PlayerList){
        this.ChangeMethod = ChangeMethod;
        this.PlayerName = PlayerName;
        this.PlayerList = PlayerList;
    }
}
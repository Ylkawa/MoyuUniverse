package com.nekoyu.Universe.DeepSeekAdapter;

import com.nekoyu.Universe.DeepSeekAdapter.ContentPiece.ContentPiece;

import java.util.LinkedList;

public class ArrayMessage extends Message {
    public LinkedList<ContentPiece> content = new LinkedList<>();
}

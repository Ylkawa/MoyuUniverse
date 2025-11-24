package com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies;

import com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ContentPiece.ContentPiece;

import java.util.LinkedList;

public class ArrayMessage extends Message {
    public LinkedList<ContentPiece> content = new LinkedList<>();
}

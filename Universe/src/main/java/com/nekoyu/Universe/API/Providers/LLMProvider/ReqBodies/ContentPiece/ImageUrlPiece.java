package com.nekoyu.Universe.API.Providers.LLMProvider.ReqBodies.ContentPiece;

import java.util.HashMap;
import java.util.Map;

public class ImageUrlPiece extends ContentPiece {
    Map<String, String> image_url;

    public ImageUrlPiece(String url) {
        super.type = "image_url";
        image_url = new HashMap<>();
        image_url.put("url", url);
    }
}

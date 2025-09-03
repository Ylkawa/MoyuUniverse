package com.nekoyu.Universe.API.MessageChannel.MessageField;

import com.nekoyu.Universe.Universe;

public class ImageField extends FileField {
    private String description;

    public ImageField() {
        description = "";
        super.type = "image";
    }

    public String getAsString() {
        if (!description.isEmpty()) return description;
        if (Universe.pictureSolver != null) {
            synchronized (description) {
                if (!description.isEmpty()) return description;
                description = Universe.pictureSolver.getDescription(file);
                return description;
            }
        } else return "[图片]";
    }
}

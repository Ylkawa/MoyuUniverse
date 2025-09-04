package com.nekoyu.Universe.API.MessageChannel.MessageField;

import com.nekoyu.Universe.Universe;

import java.io.IOException;

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
                try {
                    description = "[图片, " + Universe.pictureSolver.getDescription(file) + "]";
                    return description;
                } catch (IOException e) {
                    description = "[图片]";
                    return "[图片]";
                }
            }
        } else return "[图片]";
    }
}

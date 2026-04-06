package com.nekoyu.Universe.API.MessageChannel;

import com.nekoyu.Universe.API.MessageChannel.MessageField.ImageField;

import javax.annotation.Nullable;
import java.awt.*;

public class Session {
    String id;
    String platform;
    String name;
    @Nullable
    ImageField avatar;
    transient Color color;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ImageField getAvatar() {
        return avatar;
    }

    public void setAvatar(ImageField avatar) {
        this.avatar = avatar;
    }

    public String getLocationId() {
        return platform + ":user/" + id;
    }

    public Color getColor() {
        if (color == null) {
            if (avatar != null) {
                color = avatar.getMainColor();
            } else color = Color.WHITE;
        }
        return color;
    }
}

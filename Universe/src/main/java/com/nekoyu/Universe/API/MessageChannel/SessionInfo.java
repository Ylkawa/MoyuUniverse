package com.nekoyu.Universe.API.MessageChannel;

import com.nekoyu.Universe.API.MessageChannel.MessageField.ImageField;

import javax.annotation.Nullable;

public class SessionInfo {
    String id;
    String platform;
    String name;
    @Nullable
    ImageField avatar;

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
}

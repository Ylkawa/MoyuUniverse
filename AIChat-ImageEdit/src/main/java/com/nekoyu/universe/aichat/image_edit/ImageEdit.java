package com.nekoyu.universe.aichat.image_edit;

import com.nekoyu.Universe.AIChat.AIChat;
import com.nekoyu.Universe.AIChat.AIChatPlugin;
import java.nio.file.Path;

public class ImageEdit extends AIChatPlugin {
    public ImageEdit(AIChat aiChat) {
        super(aiChat);
    }

    @Override
    public void onEnable() {
        ImageOutputStore outputs = new ImageOutputStore(Path.of("cache", "image-edit"));
        ImageEditService service = new ImageEditService(outputs::publish);
        for (var function : ImageEditSkill.tools(service)) {
            registerFunction(function.name, function);
        }
        registerSkill(ImageEditSkill.definition());
        registerMarkDecoupler("image_edit", outputs::imageMessage);
    }

    @Override
    public void onDisable() {
        // 生成文件由 FileField 的 Cleaner 管理，HTTP 服务和关闭时的缓存清理由 Universe 负责。
    }
}

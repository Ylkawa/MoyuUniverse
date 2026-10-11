package com.nekoyu.universe.aichat.image_edit;

import com.nekoyu.Universe.API.MessageChannel.MFChain;
import com.nekoyu.Universe.API.MessageChannel.MessageField.ImageField;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 仅负责标记查找，文件生命周期和 HTTP 挂载由 FileField、UniverseChannel 管理。 */
final class ImageOutputStore {
    private static final class ImageReference extends WeakReference<ImageField> {
        final String token;
        ImageReference(String token, ImageField image, ReferenceQueue<ImageField> queue) {
            super(image, queue);
            this.token = token;
        }
    }

    private final Path directory;
    private final ReferenceQueue<ImageField> collected = new ReferenceQueue<>();
    private final Map<String, ImageReference> results = new ConcurrentHashMap<>();

    ImageOutputStore(Path directory) {
        this.directory = directory;
    }

    ImageEditService.PublishedImage publish(BufferedImage image) throws IOException {
        removeCollected();
        Files.createDirectories(directory);
        String token = UUID.randomUUID().toString();
        Path file = directory.resolve(token + ".png");
        try {
            if (!ImageIO.write(image, "png", file.toFile())) throw new IOException("PNG 编码不可用");
            if (Files.size(file) > ImageEditService.MAX_BYTES) throw new IOException("输出图片超过20MiB限制");
            ImageField owner = new ImageField(file.toFile());
            results.put(token, new ImageReference(token, owner, collected));
            return new ImageEditService.PublishedImage(owner.getUrl().toString(), token, owner);
        } catch (IOException | RuntimeException error) {
            Files.deleteIfExists(file);
            throw error;
        }
    }

    MFChain imageMessage(String token) {
        removeCollected();
        ImageReference reference = results.get(token);
        ImageField image = reference == null ? null : reference.get();
        if (image == null) return new MFChain("[图片编辑结果不存在或已释放，请重新处理]");
        // 发送时复用同一对象，避免生成第二份缓存或改变文件生命周期。
        return new MFChain(image);
    }

    private void removeCollected() {
        ImageReference reference;
        while ((reference = (ImageReference) collected.poll()) != null) results.remove(reference.token, reference);
    }
}

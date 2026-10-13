package com.nekoyu.Universe.AIChat.graph;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 在同目录写临时文件，替换失败时保留原文件，不退回非原子覆盖。 */
final class AtomicFiles {
    static void write(Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(path.toAbsolutePath().getParent(), ".graph-", ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}

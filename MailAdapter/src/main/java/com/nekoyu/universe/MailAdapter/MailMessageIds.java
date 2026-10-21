package com.nekoyu.universe.MailAdapter;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;
import java.util.Properties;

/** Durable, per-channel ID space shared by received and sent messages. */
final class MailMessageIds {
    private final Path path;

    MailMessageIds(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    long incoming(String channel, String folder, long validity, long uid) throws IOException {
        if (validity <= 0 || validity > 0xffff_ffffL || uid <= 0 || uid > 0xffff_ffffL)
            throw new IllegalArgumentException("Invalid IMAP UIDVALIDITY/UID");
        String key = "imap." + encode(channel) + "." + encode(folder) + "." + validity + "." + uid;
        return allocate(path, key, Long.MAX_VALUE);
    }

    int outgoing() throws IOException {
        return (int) allocate(path, null, Integer.MAX_VALUE);
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    // Serialize in-process instances as well as locking across processes. Always reload under the lock.
    private static synchronized long allocate(Path path, String key, long limit) throws IOException {
        Files.createDirectories(path.getParent());
        Path lockPath = path.resolveSibling(path.getFileName() + ".lock");
        try (FileChannel lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var ignored = lockChannel.lock()) {
            Properties state = new Properties();
            long last = 0;
            if (Files.exists(path)) {
                try (var in = Files.newInputStream(path)) { state.load(in); }
                try {
                    last = Long.parseLong(state.getProperty("lastId"));
                    if (last < 0) throw new NumberFormatException("Negative lastId");
                    for (String name : state.stringPropertyNames()) {
                        if (name.equals("lastId")) continue;
                        long id = Long.parseLong(state.getProperty(name));
                        if (!name.startsWith("imap.") || id <= 0 || id > last)
                            throw new NumberFormatException("Invalid mapping");
                    }
                } catch (IllegalArgumentException e) {
                    throw new IOException("Invalid mail message ID store: " + path, e);
                }
            }
            if (key != null && state.containsKey(key)) return Long.parseLong(state.getProperty(key));
            if (last >= limit) throw new IOException("Mail message ID range exhausted: " + path);
            long next = last + 1;
            state.setProperty("lastId", Long.toString(next));
            if (key != null) state.setProperty(key, Long.toString(next));
            Path temp = Files.createTempFile(path.getParent(), "mail-ids-", ".tmp");
            try {
                try (var out = Files.newOutputStream(temp)) { state.store(out, "Mail message IDs; preserve with UID checkpoint"); }
                try (FileChannel data = FileChannel.open(temp, StandardOpenOption.WRITE)) { data.force(true); }
                try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally { Files.deleteIfExists(temp); }
            return next;
        }
    }
}

package com.nekoyu.universe.MailAdapter;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.concurrent.*;

import static org.junit.Assert.*;

public class MailMessageIdsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void persistsIdentityAcrossRetriesAndRestartAndSeparatesEveryComponent() throws Exception {
        Path path = temp.getRoot().toPath().resolve("ids");
        MailMessageIds ids = new MailMessageIds(path);
        long first = ids.incoming("mail_a", "INBOX", 123, 42);
        assertEquals(first, ids.incoming("mail_a", "INBOX", 123, 42));
        MailMessageIds restarted = new MailMessageIds(path);
        assertEquals(first, restarted.incoming("mail_a", "INBOX", 123, 42));
        var distinct = new HashSet<Long>();
        distinct.add(first);
        distinct.add(restarted.incoming("mail_b", "INBOX", 123, 42));
        distinct.add(restarted.incoming("mail_a", "Archive", 123, 42));
        distinct.add(restarted.incoming("mail_a", "INBOX", 124, 42));
        distinct.add(restarted.incoming("mail_a", "INBOX", 123, 43));
        distinct.add((long) restarted.outgoing());
        distinct.add((long) new MailMessageIds(path).outgoing());
        assertEquals(7, distinct.size());
    }

    @Test public void concurrentReceivesAndSendsShareOneSequence() throws Exception {
        Path path = temp.getRoot().toPath().resolve("ids");
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            var tasks = new java.util.ArrayList<Callable<Long>>();
            for (int i = 1; i <= 20; i++) {
                long uid = i;
                tasks.add(() -> new MailMessageIds(path).incoming("mail_a", "INBOX", 123, uid));
                tasks.add(() -> (long) new MailMessageIds(path).outgoing());
            }
            var distinct = new HashSet<Long>();
            for (Future<Long> result : workers.invokeAll(tasks)) distinct.add(result.get());
            assertEquals(40, distinct.size());
            assertEquals(41, new MailMessageIds(path).outgoing());
        } finally { workers.shutdownNow(); }
    }

    @Test public void refusesCorruptOrUnwritableStoreInsteadOfPublishingAnUnstableId() throws Exception {
        Path path = temp.getRoot().toPath().resolve("ids");
        Files.writeString(path, "lastId=broken\n");
        assertThrows(IOException.class, () -> new MailMessageIds(path).outgoing());
        assertEquals("lastId=broken\n", Files.readString(path));
        Path blockedParent = temp.getRoot().toPath().resolve("file");
        Files.writeString(blockedParent, "not a directory");
        assertThrows(IOException.class, () -> new MailMessageIds(blockedParent.resolve("ids"))
                .incoming("mail_a", "INBOX", 123, 42));
    }

    @Test public void outgoingIntBoundaryDoesNotWrapOrPreventLongIncomingIds() throws Exception {
        Path path = temp.getRoot().toPath().resolve("ids");
        Files.writeString(path, "lastId=" + (Integer.MAX_VALUE - 1L) + "\n");
        MailMessageIds ids = new MailMessageIds(path);
        assertEquals(Integer.MAX_VALUE, ids.outgoing());
        assertThrows(IOException.class, ids::outgoing);
        assertEquals(Integer.MAX_VALUE + 1L, ids.incoming("mail_a", "INBOX", 123, 42));
        assertThrows(IOException.class, ids::outgoing);
        Files.writeString(path, "lastId=" + Long.MAX_VALUE + "\n");
        assertThrows(IOException.class, () -> ids.incoming("mail_a", "INBOX", 123, 43));
    }
}

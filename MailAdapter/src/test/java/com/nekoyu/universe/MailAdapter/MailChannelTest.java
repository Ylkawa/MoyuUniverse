package com.nekoyu.universe.MailAdapter;

import com.nekoyu.Universe.API.MessageChannel.*;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import org.junit.Test;
import java.nio.file.Files;
import java.util.Properties;
import static org.junit.Assert.*;

public class MailChannelTest {
    @Test public void sendsThroughSmtpTransport() throws Exception {
        try (java.net.ServerSocket server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5000);
            java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
            java.util.concurrent.Future<String> received = worker.submit(() -> {
                try (java.net.Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    var in = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                    var out = new java.io.PrintWriter(socket.getOutputStream(), true);
                    out.print("220 localhost ready\r\n"); out.flush();
                    boolean data = false;
                    StringBuilder content = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) {
                        if (data && !line.equals(".")) { content.append(line).append('\n'); continue; }
                        if (data) { data = false; out.print("250 accepted\r\n"); }
                        else if (line.equals("DATA")) { data = true; out.print("354 send data\r\n"); }
                        else if (line.equals("QUIT")) { out.print("221 bye\r\n"); out.flush(); break; }
                        else { content.append(line).append('\n'); out.print("250 OK\r\n"); }
                        out.flush();
                    }
                    return content.toString();
                }
            });
            Config c = config(); c.smtp.enabled = true; c.smtp.host = server.getInetAddress().getHostAddress();
            c.smtp.port = server.getLocalPort(); c.smtp.auth = false; c.smtp.startTls = false;
            MailChannel channel = new MailChannel(c, Files.createTempDirectory("mail-test").resolve("uid"));
            try {
                channel.load();
                MimeMessage incoming = new MimeMessage(jakarta.mail.Session.getInstance(new Properties()));
                incoming.setFrom("sender@example.com"); incoming.setText("incoming"); incoming.saveChanges();
                MCMessage first = channel.convert(incoming, "INBOX", 123, 42);
                int sent = channel.sendMessage("sender@example.com", new MFChain("hello smtp"));
                assertTrue(sent > first.id);
                assertEquals(first.id, channel.convert(incoming, "INBOX", 123, 42).id);
                String wire = received.get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(wire.contains("RCPT TO:<sender@example.com>"));
                assertTrue(wire.contains("hello smtp"));
            } finally { channel.stop(); worker.shutdownNow(); }
        }
    }
    private Config config() {
        Config c = new Config();
        c.username = "bot@example.com";
        c.imap.enabled = false;
        return c;
    }

    @Test public void lifecycleAndSessionIdentity() throws Exception {
        MailChannel channel = new MailChannel(config(), Files.createTempDirectory("mail-test").resolve("uid"));
        assertEquals("mail_bot_example_com", channel.ID);
        assertEquals("Mail:sender@example.com", channel.getSession("sender@example.com").getLocationId());
        assertSame(channel.getSession("sender@example.com"), channel.getSession("sender@example.com"));
        try {
            channel.load();
            channel.load();
            assertSame(channel, MessageChannelManager.getChannel(channel.ID));
            try { channel.sendMessage("sender@example.com", new MFChain("hello")); fail(); }
            catch (IllegalStateException expected) { }
        } finally { channel.stop(); }
        assertNull(MessageChannelManager.getChannel(channel.ID));
    }

    @Test public void convertsAlternativeAndAttachmentWithoutDuplicatingHtml() throws Exception {
        MailChannel channel = new MailChannel(config(), Files.createTempDirectory("mail-test").resolve("uid"));
        MimeMessage mail = new MimeMessage(jakarta.mail.Session.getInstance(new Properties()));
        mail.setFrom(new InternetAddress("sender@example.com", "Sender"));
        mail.setSubject("Subject", "UTF-8");
        MimeMultipart alternative = new MimeMultipart("alternative");
        MimeBodyPart plain = new MimeBodyPart(); plain.setText("plain body", "UTF-8");
        MimeBodyPart html = new MimeBodyPart(); html.setContent("<b>html body</b>", "text/html; charset=UTF-8");
        alternative.addBodyPart(plain); alternative.addBodyPart(html);
        MimeBodyPart body = new MimeBodyPart(); body.setContent(alternative);
        MimeBodyPart attachment = new MimeBodyPart(); attachment.setText("attachment secret");
        attachment.setFileName("note.txt"); attachment.setDisposition(Part.ATTACHMENT);
        MimeMultipart mixed = new MimeMultipart(); mixed.addBodyPart(body); mixed.addBodyPart(attachment);
        mail.setContent(mixed); mail.saveChanges();
        MCMessage result = channel.convert(mail, "INBOX", 123, 42);
        assertTrue(result.id > 0);
        assertEquals(42L, result.getMetainfo("imapUid"));
        assertEquals(123L, result.getMetainfo("uidValidity"));
        assertEquals("INBOX", result.getMetainfo("imapFolder"));
        assertSame(channel.loginAccount, result.receiver);
        assertEquals("Sender", result.sender.getName());
        assertTrue(result.solveAll().contains("Subject\n\nplain body"));
        assertTrue(result.solveAll().contains("note.txt"));
        assertFalse(result.solveAll().contains("html body"));
        assertFalse(result.solveAll().contains("attachment secret"));
    }

    @Test public void rejectsAmbiguousAddressAndTlsConfiguration() throws Exception {
        MailChannel channel = new MailChannel(config(), Files.createTempDirectory("mail-test").resolve("uid"));
        try { channel.getSession("a@example.com,b@example.com"); fail(); }
        catch (IllegalArgumentException expected) { }
        Config c = config(); c.imap.enabled = true; c.imap.host = "localhost"; c.imap.startTls = true;
        try { new MailChannel(c, Files.createTempDirectory("mail-test").resolve("uid")); fail(); }
        catch (IllegalArgumentException expected) { }
    }
}

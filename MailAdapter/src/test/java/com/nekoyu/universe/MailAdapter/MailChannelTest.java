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

    @Test public void convertsHtmlToMarkdownPreservingLinksAndDataInsideMailLayout() throws Exception {
        MailChannel channel = new MailChannel(config(), Files.createTempDirectory("mail-test").resolve("uid"));
        MimeMessage mail = new MimeMessage(jakarta.mail.Session.getInstance(new Properties()));
        mail.setFrom("sender@example.com");
        mail.setSubject("升级通知", "UTF-8");
        String html = """
                <html><head><style>.banner { color: red; }</style><script>trackingScript()</script></head>
                <body><span style="color:red; DISPLAY: none !important;">hidden preheader</span>
                <p style="visibility:hidden">hidden tracking</p><div hidden>hidden attribute</div>
                <table role="presentation"><tr><td><h2>影响时间</h2>
                <p>计划于<code>2026-10-27</code>升级，<strong>以实际变更为准</strong>。</p>
                <p><a href="https://example.com/model?id=1&amp;region=cn">模型详情</a></p>
                <table><tr><th>模型</th><th>计费</th></tr><tr><td>1017</td><td>价格不变</td></tr></table>
                <ul><li>提前测试</li><li>核对条件</li></ul>
                <img width="1" height="1" src="https://example.invalid/tracker" alt="tracking pixel">
                <img alt="模型结构" src="https://example.com/diagram.png">
                <img src="https://example.com/chart-without-alt.png">
                </td></tr></table></body></html>
                """;
        mail.setContent(html, "text/html; charset=UTF-8");
        mail.saveChanges();
        var wire = new java.io.ByteArrayOutputStream();
        mail.writeTo(wire);
        MimeMessage decoded = new MimeMessage(jakarta.mail.Session.getInstance(new Properties()),
                new java.io.ByteArrayInputStream(wire.toByteArray()));
        String markdown = channel.convert(decoded, "INBOX", 123, 43).solveAll();
        assertTrue(markdown.startsWith("升级通知\n\n## 影响时间"));
        assertTrue(markdown.contains("`2026-10-27`"));
        assertTrue(markdown.contains("**以实际变更为准**"));
        assertTrue(markdown.contains("[模型详情](<https://example.com/model?id=1&region=cn>)"));
        assertTrue(markdown.contains("| 模型 | 计费 |\n| --- | --- |\n| 1017 | 价格不变 |"));
        assertTrue(markdown.contains("- 提前测试\n- 核对条件"));
        assertTrue(markdown.contains("![模型结构](<https://example.com/diagram.png>)"));
        assertTrue(markdown.contains("chart-without-alt.png"));
        for (String noise : new String[]{"[HTML]", "<html", "<table", "color: red", "trackingScript",
                "hidden preheader", "hidden tracking", "hidden attribute", "example.invalid/tracker"})
            assertFalse("HTML noise must not enter the message: " + noise, markdown.contains(noise));
        assertEquals("Only the data table remains", 3, markdown.lines().filter(line -> line.startsWith("|")).count());
        assertTrue(markdown.length() < html.length());
    }

    @Test public void decodesQuotedPrintableHtmlAlternativeBeforeMarkdownConversion() throws Exception {
        MailChannel channel = new MailChannel(config(), Files.createTempDirectory("mail-test").resolve("uid"));
        String raw = """
                From: sender@example.com
                Subject: HTML notice
                MIME-Version: 1.0
                Content-Type: multipart/alternative; boundary=demo

                --demo
                Content-Type: text/html; charset=UTF-8
                Content-Transfer-Encoding: quoted-printable

                <h2>=E5=8D=87=E7=BA=A7</h2><p><a href=3D"https://example.com/mo=
                del">=E8=AF=A6=E6=83=85</a></p>
                --demo--
                """.replace("\n", "\r\n");
        MimeMessage mail = new MimeMessage(jakarta.mail.Session.getInstance(new Properties()),
                new java.io.ByteArrayInputStream(raw.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        String markdown = channel.convert(mail, "INBOX", 123, 44).solveAll();
        assertEquals("HTML notice\n\n## 升级\n\n[详情](<https://example.com/model>)", markdown);
        assertFalse(markdown.contains("=E5"));
    }

    @Test public void doesNotApplyWebCharacterLimitToHtmlMail() throws Exception {
        MailChannel channel = new MailChannel(config(), Files.createTempDirectory("mail-test").resolve("uid"));
        MimeMessage mail = new MimeMessage(jakarta.mail.Session.getInstance(new Properties()));
        mail.setFrom("sender@example.com");
        mail.setContent("<style>" + ".noise{color:red}".repeat(1000) + "</style><p>"
                + "正文内容".repeat(2000) + "</p><p>结尾适用条件</p>", "text/html; charset=UTF-8");
        mail.saveChanges();
        String markdown = channel.convert(mail, "INBOX", 123, 45).solveAll();
        assertTrue(markdown.length() > 5000);
        assertTrue(markdown.endsWith("结尾适用条件"));
        assertFalse(markdown.contains(".noise"));
    }
}

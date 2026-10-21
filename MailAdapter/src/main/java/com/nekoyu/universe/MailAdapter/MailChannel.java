package com.nekoyu.universe.MailAdapter;

import com.nekoyu.Universe.API.MessageChannel.*;
import com.nekoyu.Universe.API.MessageChannel.Features.SessionChat;
import com.nekoyu.Universe.Utils.HtmlMarkdown;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import jakarta.mail.search.ReceivedDateTerm;
import jakarta.mail.search.ComparisonTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

/** One mailbox is one channel; local session IDs are correspondent email addresses. */
public class MailChannel extends MessageChannel implements SessionChat {
    private static final Logger logger = LoggerFactory.getLogger(MailChannel.class);
    private final Config config;
    private final Path checkpoint;
    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final MailMessageIds messageIds;
    private ScheduledExecutorService executor;
    private volatile boolean running;
    private long uidValidity = -1;
    private long lastUid;

    public MailChannel(Config config, Path checkpoint) {
        this(config.id == null || config.id.isBlank()
                ? "mail_" + config.username.replaceAll("[^a-zA-Z0-9]", "_") : config.id, config, checkpoint);
    }

    public MailChannel(String id, Config config, Path checkpoint) {
        super(id);
        if (!id.matches("[a-z][a-zA-Z0-9_]*")) throw new IllegalArgumentException("Invalid mail channel ID: " + id);
        this.config = Objects.requireNonNull(config);
        this.checkpoint = Objects.requireNonNull(checkpoint);
        this.messageIds = new MailMessageIds(checkpoint.resolveSibling(checkpoint.getFileName() + ".message-ids"));
        platform = "Mail";
        type = "mail";
        accountId = address(config.username);
        nickname = config.smtp.fromName.isBlank() ? accountId : config.smtp.fromName;
        loginAccount = account(accountId, nickname);
        validate();
    }

    private void validate() {
        if (config.imap.enabled) {
            if (config.imap.host.isBlank() || config.imap.pollIntervalSeconds <= 0
                    || config.imap.initialLookbackDays < 0) throw new IllegalArgumentException("Invalid IMAP configuration");
            properties("imap", config.imap.host, config.imap.port, config.imap.ssl, config.imap.startTls,
                    config.imap.connectionTimeoutMillis, config.imap.readTimeoutMillis, config.imap.writeTimeoutMillis);
        }
        if (config.smtp.enabled) {
            if (config.smtp.host.isBlank()) throw new IllegalArgumentException("SMTP host is required");
            properties("smtp", config.smtp.host, config.smtp.port, config.smtp.ssl, config.smtp.startTls,
                    config.smtp.connectionTimeoutMillis, config.smtp.readTimeoutMillis, config.smtp.writeTimeoutMillis);
        }
    }

    @Override
    public synchronized void load() {
        if (running) return;
        MessageChannel existing = MessageChannelManager.getChannel(ID);
        if (existing != null && existing != this) throw new IllegalStateException("Duplicate channel: " + ID);
        try {
            if (Files.exists(checkpoint)) {
                Properties state = new Properties();
                try (var in = Files.newInputStream(checkpoint)) { state.load(in); }
                uidValidity = Long.parseLong(state.getProperty("validity"));
                lastUid = Long.parseLong(state.getProperty("uid"));
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Cannot read mail checkpoint: " + checkpoint, e);
        }
        running = true;
        MessageChannelManager.registerChannel(ID, this);
        if (config.imap.enabled) {
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, ID + "-imap");
                thread.setDaemon(true);
                return thread;
            });
            executor.scheduleWithFixedDelay(this::poll, 0, config.imap.pollIntervalSeconds, TimeUnit.SECONDS);
        }
    }

    private void poll() {
        Config.Imap c = config.imap;
        try (Store store = jakarta.mail.Session.getInstance(properties("imap", c.host, c.port, c.ssl, c.startTls,
                c.connectionTimeoutMillis, c.readTimeoutMillis, c.writeTimeoutMillis)).getStore("imap")) {
            store.connect(c.host, c.port, config.username, config.password);
            Folder folder = store.getFolder(c.folder);
            try {
                folder.open(c.markAsRead ? Folder.READ_WRITE : Folder.READ_ONLY);
                if (!(folder instanceof UIDFolder uids)) throw new MessagingException("Folder does not support UIDs");
                long validity = uids.getUIDValidity();
                if (uidValidity != validity) { uidValidity = validity; lastUid = 0; }
                Message[] messages = lastUid > 0 ? uids.getMessagesByUID(lastUid + 1, UIDFolder.LASTUID)
                        : c.initialLookbackDays == 0 ? folder.getMessages()
                        : folder.search(new ReceivedDateTerm(ComparisonTerm.GE,
                        Date.from(Instant.now().minus(c.initialLookbackDays, ChronoUnit.DAYS))));
                Arrays.sort(messages, Comparator.comparingInt(Message::getMessageNumber));
                for (Message mail : messages) {
                    if (!running || Thread.currentThread().isInterrupted()) break;
                    if (mail == null) continue;
                    long uid = uids.getUID(mail);
                    if (uid <= lastUid) continue;
                    MCMessage message = convert(mail, folder.getFullName(), validity, uid);
                    broadcastMessage(message.sender.getId(), message);
                    if (c.markAsRead) mail.setFlag(Flags.Flag.SEEN, true);
                    saveCheckpoint(validity, uid);
                    lastUid = uid;
                }
            } finally {
                if (folder.isOpen()) folder.close(false);
            }
        } catch (Exception e) {
            if (running) logger.error("{} receiving mail failed; retrying on next poll", ID, e);
        }
    }

    private void saveCheckpoint(long validity, long uid) throws IOException {
        Path target = checkpoint.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), "mail-uid-", ".tmp");
        try {
            Files.writeString(temp, "validity=" + validity + "\nuid=" + uid + "\n");
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    MCMessage convert(Message mail, String folder, long validity, long uid) throws MessagingException, IOException {
        if (mail.getFrom() == null || mail.getFrom().length == 0
                || !(mail.getFrom()[0] instanceof InternetAddress from)) throw new MessagingException("Missing sender");
        MCMessage message = new MCMessage();
        message.sender = account(from.getAddress(), from.getPersonal());
        message.receiver = loginAccount;
        message.session = message.sender;
        Date date = mail.getReceivedDate();
        if (date == null) date = mail.getSentDate();
        message.time = date == null ? Instant.now().getEpochSecond() : date.toInstant().getEpochSecond();
        String subject = mail.getSubject();
        message.messageFields = new MFChain((subject == null ? "" : subject + "\n\n") + text(mail));
        message.putMetainfo("subject", subject);
        message.putMetainfo("messageId", mail.getHeader("Message-ID"));
        message.putMetainfo("imapFolder", folder);
        message.putMetainfo("uidValidity", validity);
        message.putMetainfo("imapUid", uid);
        // Persist identity before publishing the message, so a retry reuses this ID.
        message.id = messageIds.incoming(ID, folder, validity, uid);
        return message;
    }

    /** Attachments are described, never executed or downloaded from external URLs. */
    private static String text(Part part) throws MessagingException, IOException {
        if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) || part.getFileName() != null)
            return "[附件: " + Objects.toString(part.getFileName(), "unnamed") + "]";
        if (part.isMimeType("text/plain")) return Objects.toString(part.getContent(), "");
        if (part.isMimeType("text/html")) return HtmlMarkdown.convertEmail(Objects.toString(part.getContent(), ""));
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            if (part.isMimeType("multipart/alternative")) {
                for (int i = 0; i < multipart.getCount(); i++)
                    if (multipart.getBodyPart(i).isMimeType("text/plain")) return text(multipart.getBodyPart(i));
                return multipart.getCount() == 0 ? "" : text(multipart.getBodyPart(multipart.getCount() - 1));
            }
            StringJoiner result = new StringJoiner("\n");
            for (int i = 0; i < multipart.getCount(); i++) result.add(text(multipart.getBodyPart(i)));
            return result.toString();
        }
        return "[" + part.getContentType() + "]";
    }

    @Override
    public int sendMessage(String sessionId, MFChain message) {
        if (!running || !config.smtp.enabled) throw new IllegalStateException("SMTP channel is not enabled/running");
        Config.Smtp c = config.smtp;
        Properties props = properties("smtp", c.host, c.port, c.ssl, c.startTls,
                c.connectionTimeoutMillis, c.readTimeoutMillis, c.writeTimeoutMillis);
        props.setProperty("mail.smtp.auth", Boolean.toString(c.auth));
        jakarta.mail.Session session = jakarta.mail.Session.getInstance(props);
        try {
            MimeMessage mail = new MimeMessage(session);
            mail.setFrom(new InternetAddress(c.from.isBlank() ? accountId : address(c.from), c.fromName, "UTF-8"));
            mail.setRecipient(Message.RecipientType.TO, new InternetAddress(address(sessionId)));
            if (!c.replyTo.isBlank()) mail.setReplyTo(new Address[]{new InternetAddress(address(c.replyTo))});
            mail.setSubject("Message from " + nickname, "UTF-8");
            mail.setText(message.solveAll(false), "UTF-8");
            mail.setSentDate(new Date());
            mail.saveChanges();
            // Reserve durably before sending. Failed sends may leave gaps, never reused IDs.
            int messageId = messageIds.outgoing();
            try (Transport transport = session.getTransport("smtp")) {
                transport.connect(c.host, c.port, c.auth ? (c.username.isBlank() ? config.username : c.username) : null,
                        c.auth ? (c.password.isBlank() ? config.password : c.password) : null);
                transport.sendMessage(mail, mail.getAllRecipients());
            }
            return messageId;
        } catch (MessagingException | IOException e) {
            throw new IllegalStateException("Failed to send mail through " + ID, e);
        }
    }

    @Override public Account getSession(String sessionId) { return account(sessionId, null); }

    private Account account(String email, String name) {
        String id = address(email);
        Account account = accounts.computeIfAbsent(id, key -> {
            Account created = new Account();
            created.setId(key);
            created.setName(key);
            created.setPlatform(platform);
            created.setLocationId(platform + ":" + key);
            return created;
        });
        if (name != null && !name.isBlank()) account.setName(name);
        return account;
    }

    private static String address(String value) {
        try {
            InternetAddress parsed = new InternetAddress(value, true);
            parsed.validate();
            String email = parsed.getAddress();
            if (!email.equals(value) || email.contains(":")) throw new AddressException("Expected bare email address");
            return email;
        } catch (MessagingException | NullPointerException e) { throw new IllegalArgumentException("Invalid email address", e); }
    }

    private static Properties properties(String protocol, String host, int port, boolean ssl, boolean startTls,
                                         int connectTimeout, int readTimeout, int writeTimeout) {
        if (ssl && startTls || port < 1 || port > 65535 || connectTimeout <= 0 || readTimeout <= 0 || writeTimeout <= 0)
            throw new IllegalArgumentException("Invalid mail TLS/port/timeout configuration");
        Properties props = new Properties();
        String prefix = "mail." + protocol + ".";
        props.setProperty(prefix + "host", host);
        props.setProperty(prefix + "port", Integer.toString(port));
        props.setProperty(prefix + "ssl.enable", Boolean.toString(ssl));
        props.setProperty(prefix + "ssl.checkserveridentity", "true");
        props.setProperty(prefix + "starttls.enable", Boolean.toString(startTls));
        props.setProperty(prefix + "starttls.required", Boolean.toString(startTls));
        props.setProperty(prefix + "connectiontimeout", Integer.toString(connectTimeout));
        props.setProperty(prefix + "timeout", Integer.toString(readTimeout));
        props.setProperty(prefix + "writetimeout", Integer.toString(writeTimeout));
        if (protocol.equals("imap")) props.setProperty("mail.imap.peek", "true");
        return props;
    }

    @Override public synchronized void stop() {
        running = false;
        if (executor != null) { executor.shutdownNow(); executor = null; }
        MessageChannelManager.MessageChannels.remove(ID, this);
    }
}

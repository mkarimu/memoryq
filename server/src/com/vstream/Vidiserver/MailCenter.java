package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;

/**
 * The super-admin's mail centre (Admin -> Mail): every email to @memoryq.com (Postfix on the VPS hands it to this server
 * on 127.0.0.1:2525), every email Memoryq sends by itself, the admin's own replies, and the Marketplace conversations.
 * Folders: inbox (customers), server (root, postmaster, bounces), system (what Memoryq sent automatically), sent, spam, trash.
 * Read it, reply, forward (with the attachments), write new mail with attachments, delete, and the spam blocker
 * (block a sender or a whole domain; their mail goes straight to Spam).
 */
public final class MailCenter {

    static final String[] FOLDERS = {"inbox", "server", "system", "sent", "spam", "trash"};
    static final int MAX_MAIL = 25 * 1024 * 1024;

    static final class Item {
        long id;
        String folder = "inbox";
        String from = "";
        String fromName = "";
        String to = "";
        String subject = "";
        long date;
        long received;
        boolean read;
        int attachments;
        long size;
        String messageId = "";
        String inReplyTo = "";
        String snippet = "";
        String before = ""; // the folder it was in before Trash / Spam
    }

    private final Path dir;
    private final SessionStore sessions;
    private final LongSupplier clock;
    private final Map<Long, Item> items = new LinkedHashMap<Long, Item>();
    private final Set<String> blocked = new LinkedHashSet<String>();
    private long nextId = 1;
    private volatile Mailer mailer;
    private volatile String support = System.getProperty("vstream.supportAddress", "support@memoryq.com");
    private volatile MarketStore market;
    private volatile NotificationStore notes;
    private volatile ServerSocket smtp;
    private volatile long lastReceived;
    private final ExecutorService pool = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "mail-in");
        t.setDaemon(true);
        return t;
    });

    public MailCenter(Path dir, SessionStore sessions, LongSupplier clock) {
        this.dir = dir;
        this.sessions = sessions;
        this.clock = clock;
    }

    public MailCenter mailer(Mailer m) {
        this.mailer = m;
        return this;
    }

    public MailCenter market(MarketStore m, NotificationStore n) {
        this.market = m;
        this.notes = n;
        return this;
    }

    // ---- the files: mail/index.txt, mail/blocked.txt, mail/<id>.eml ----

    private static String c(String s, int max) {
        String t = s == null ? "" : s.replaceAll("[\\t\\r\\n]+", " ").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    public synchronized void init() throws IOException {
        Files.createDirectories(dir);
        Path idx = dir.resolve("index.txt");
        if (Files.isRegularFile(idx)) {
            for (String line : Files.readAllLines(idx, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t", -1);
                if (f.length < 15) {
                    continue;
                }
                try {
                    Item it = new Item();
                    it.id = Long.parseLong(f[0]);
                    it.folder = f[1];
                    it.from = f[2];
                    it.fromName = f[3];
                    it.to = f[4];
                    it.subject = f[5];
                    it.date = Long.parseLong(f[6]);
                    it.received = Long.parseLong(f[7]);
                    it.read = "1".equals(f[8]);
                    it.attachments = Integer.parseInt(f[9]);
                    it.size = Long.parseLong(f[10]);
                    it.messageId = f[11];
                    it.inReplyTo = f[12];
                    it.snippet = f[13];
                    it.before = f[14];
                    items.put(it.id, it);
                    nextId = Math.max(nextId, it.id + 1);
                } catch (RuntimeException e) {
                    // a damaged line
                }
            }
        }
        Path bl = dir.resolve("blocked.txt");
        if (Files.isRegularFile(bl)) {
            for (String line : Files.readAllLines(bl, StandardCharsets.UTF_8)) {
                if (!line.trim().isEmpty()) {
                    blocked.add(line.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
    }

    private synchronized void save() {
        StringBuilder sb = new StringBuilder();
        for (Item it : items.values()) {
            sb.append(it.id).append('\t').append(it.folder).append('\t').append(c(it.from, 200)).append('\t').append(c(it.fromName, 120)).append('\t')
                    .append(c(it.to, 300)).append('\t').append(c(it.subject, 300)).append('\t').append(it.date).append('\t').append(it.received)
                    .append('\t').append(it.read ? 1 : 0).append('\t').append(it.attachments).append('\t').append(it.size).append('\t')
                    .append(c(it.messageId, 300)).append('\t').append(c(it.inReplyTo, 300)).append('\t').append(c(it.snippet, 200)).append('\t')
                    .append(it.before).append('\n');
        }
        StringBuilder b = new StringBuilder();
        for (String x : blocked) {
            b.append(x).append('\n');
        }
        try {
            write(dir.resolve("index.txt"), sb.toString().getBytes(StandardCharsets.UTF_8));
            write(dir.resolve("blocked.txt"), b.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.out.println("[mail] could not save: " + e.getMessage());
        }
    }

    private static void write(Path p, byte[] b) throws IOException {
        Path part = p.resolveSibling(p.getFileName() + ".part");
        Files.write(part, b);
        Files.move(part, p, StandardCopyOption.REPLACE_EXISTING);
    }

    private Path eml(long id) {
        return dir.resolve(id + ".eml");
    }

    // ---- filing a message ----

    static final String[] SPAMMY = {"viagra", "cialis", "casino", "lottery", "you have won", "winner", "bitcoin", "crypto", "investment opportunity",
        "loan offer", "dear friend", "beneficiary", "inheritance", "100% free", "act now", "click here", "seo services", "rank your website",
        "nigerian prince", "wire transfer", "gift card", "adult", "xxx", "work from home", "earn money"};

    /** 0 = clean; 5 or more = spam. */
    static int spamScore(MimeMail m) {
        int score = 0;
        String flag = m.header("x-spam-flag").toLowerCase(Locale.ROOT);
        if (flag.startsWith("yes")) {
            score += 5;
        }
        String subj = m.header("subject");
        String all = (subj + " " + m.text).toLowerCase(Locale.ROOT);
        for (String w : SPAMMY) {
            if (all.contains(w)) {
                score += 2;
            }
        }
        if (subj.length() > 8 && subj.equals(subj.toUpperCase(Locale.ROOT)) && subj.matches(".*[A-Z].*")) {
            score += 1;
        }
        int links = (m.html + " " + m.text).split("https?://", -1).length - 1;
        if (links > 15) {
            score += 2;
        }
        if (m.text.trim().isEmpty() && m.attachments.isEmpty()) {
            score += 1;
        }
        String from = MimeMail.address(m.header("from")), reply = MimeMail.address(m.header("reply-to"));
        if (!reply.isEmpty() && !from.isEmpty() && !reply.substring(reply.indexOf('@')).equals(from.substring(from.indexOf('@')))) {
            score += 1;
        }
        return score;
    }

    synchronized boolean isBlocked(String address) {
        String a = address.toLowerCase(Locale.ROOT);
        return blocked.contains(a) || (a.contains("@") && blocked.contains(a.substring(a.indexOf('@'))));
    }

    /** Keeps a message that arrived (or that Memoryq sent); returns its id. */
    long file(byte[] raw, String folderHint, List<String> rcpts) throws IOException {
        MimeMail m = MimeMail.parse(raw);
        Item it = new Item();
        it.from = MimeMail.address(m.header("from"));
        it.fromName = c(MimeMail.displayName(m.header("from")), 120);
        it.to = c(m.header("to"), 300);
        it.subject = c(m.header("subject").isEmpty() ? "(no subject)" : m.header("subject"), 300);
        it.received = clock.getAsLong();
        it.date = parseDate(m.header("date"), it.received);
        it.attachments = m.attachments.size();
        it.size = raw.length;
        it.messageId = c(m.header("message-id"), 300);
        it.inReplyTo = c(m.header("in-reply-to"), 300);
        it.snippet = c(m.text.replaceAll("\\s+", " "), 200);
        String folder = folderHint;
        if (folder == null) {
            List<String> real = new ArrayList<String>();
            if (rcpts != null) {
                for (String r : rcpts) {
                    if (!r.toLowerCase(Locale.ROOT).endsWith("@mailcentre.internal")) { // the copy Postfix makes for the mail centre
                        real.add(r);
                    }
                }
            }
            String to = (real.isEmpty() ? it.to : String.join(" ", real)).toLowerCase(Locale.ROOT);
            if (isBlocked(it.from) || spamScore(m) >= 5) {
                folder = "spam";
            } else if (it.from.startsWith("mailer-daemon@") || it.from.startsWith("postmaster@") || it.from.startsWith("root@")
                    || to.matches(".*\\b(server|root|postmaster|abuse|hostmaster|webmaster|mailer-daemon)@.*")) {
                folder = "server";
            } else {
                folder = "inbox";
            }
        }
        it.folder = folder;
        it.read = folder.equals("sent") || folder.equals("system");
        synchronized (this) {
            it.id = nextId++;
            Files.write(eml(it.id), raw);
            items.put(it.id, it);
            if (!folder.equals("sent") && !folder.equals("system")) {
                lastReceived = it.received;
            }
            trim();
        }
        save();
        return it.id;
    }

    /** Keeps the newest 5000 automatic mails and empties Trash after 30 days. */
    private void trim() {
        long now = clock.getAsLong();
        List<Item> sys = new ArrayList<Item>();
        for (Item i : items.values()) {
            if (i.folder.equals("system")) {
                sys.add(i);
            }
        }
        for (int k = 0; k < sys.size() - 5000; k++) {
            forget(sys.get(k).id);
        }
        for (Item i : new ArrayList<Item>(items.values())) {
            if (i.folder.equals("trash") && now - i.received > 30L * 86400000L) {
                forget(i.id);
            }
        }
    }

    private void forget(long id) {
        items.remove(id);
        try {
            Files.deleteIfExists(eml(id));
        } catch (IOException e) {
            // already gone
        }
    }

    static long parseDate(String d, long fallback) {
        String s = d == null ? "" : d.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
        for (String f : new String[]{"EEE, d MMM yyyy HH:mm:ss Z", "d MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm Z"}) {
            try {
                return new java.text.SimpleDateFormat(f, Locale.US).parse(s).getTime();
            } catch (Exception e) {
                // the next format
            }
        }
        return fallback;
    }

    /** A copy of what Memoryq sent by itself (Mailer.sentLog). */
    public void logSystem(String to, String subject, String text) {
        Mailer m = mailer;
        String from = m == null ? "no-reply@memoryq.com" : m.from();
        String[] built = MimeMail.build("Memoryq", from, Collections.singletonList(to), null, null, subject, text, null, null, null);
        try {
            file(built[0].getBytes(StandardCharsets.UTF_8), "system", null);
        } catch (IOException e) {
            // not kept
        }
    }

    // ---- receiving: Postfix hands mail for @memoryq.com to 127.0.0.1:2525 ----

    public void startSmtp(int port) {
        try {
            ServerSocket ss = new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"));
            smtp = ss;
            Thread t = new Thread(() -> {
                while (!ss.isClosed()) {
                    try {
                        Socket s = ss.accept();
                        pool.submit(() -> session(s));
                    } catch (IOException e) {
                        // closed
                    }
                }
            }, "mail-smtp");
            t.setDaemon(true);
            t.start();
            System.out.println("[mail] receiving email on 127.0.0.1:" + port + " (Postfix hands it over)");
        } catch (IOException e) {
            System.out.println("[mail] could not listen on port " + port + ": " + e.getMessage());
        }
    }

    public int smtpPort() {
        ServerSocket s = smtp;
        return s == null ? 0 : s.getLocalPort();
    }

    public void stopSmtp() {
        try {
            if (smtp != null) {
                smtp.close();
            }
        } catch (IOException e) {
            // closed
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                break;
            }
            if (b.size() < 4096) {
                b.write(c);
            }
        }
        if (c < 0 && b.size() == 0) {
            return null;
        }
        String s = new String(b.toByteArray(), StandardCharsets.ISO_8859_1);
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    private static void reply(OutputStream out, String line) throws IOException {
        out.write((line + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private void session(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(120000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();
            reply(out, "220 memoryq mail centre ready");
            String mailFrom = null;
            List<String> rcpts = new ArrayList<String>();
            String line;
            while ((line = readLine(in)) != null) {
                String up = line.toUpperCase(Locale.ROOT);
                if (up.startsWith("EHLO")) {
                    out.write(("250-memoryq\r\n250-SIZE " + MAX_MAIL + "\r\n250-8BITMIME\r\n250 SMTPUTF8\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                } else if (up.startsWith("HELO")) {
                    reply(out, "250 memoryq");
                } else if (up.startsWith("MAIL FROM:")) {
                    mailFrom = MimeMail.address(line.substring(10));
                    rcpts.clear();
                    reply(out, "250 OK");
                } else if (up.startsWith("RCPT TO:")) {
                    if (mailFrom == null) {
                        reply(out, "503 MAIL first");
                    } else if (rcpts.size() >= 100) {
                        reply(out, "452 too many recipients");
                    } else {
                        rcpts.add(MimeMail.address(line.substring(8)));
                        reply(out, "250 OK");
                    }
                } else if (up.equals("DATA")) {
                    if (rcpts.isEmpty()) {
                        reply(out, "503 RCPT first");
                        continue;
                    }
                    reply(out, "354 send the message, end with a line with only .");
                    ByteArrayOutputStream msg = new ByteArrayOutputStream();
                    boolean tooBig = false;
                    String l;
                    while ((l = readLine(in)) != null && !l.equals(".")) {
                        if (l.startsWith("..")) {
                            l = l.substring(1);
                        }
                        if (msg.size() < MAX_MAIL) {
                            msg.write(l.getBytes(StandardCharsets.ISO_8859_1));
                            msg.write('\r');
                            msg.write('\n');
                        } else {
                            tooBig = true;
                        }
                    }
                    if (tooBig) {
                        reply(out, "552 the message is too big (25 MB at most)");
                    } else {
                        byte[] raw = msg.toByteArray();
                        String trace = "X-Memoryq-Envelope: from=<" + (mailFrom == null ? "" : mailFrom) + "> to=<" + String.join(",", rcpts) + ">\r\n";
                        byte[] all = new byte[trace.length() + raw.length];
                        System.arraycopy(trace.getBytes(StandardCharsets.ISO_8859_1), 0, all, 0, trace.length());
                        System.arraycopy(raw, 0, all, trace.length(), raw.length);
                        file(all, null, new ArrayList<String>(rcpts));
                        reply(out, "250 OK kept");
                    }
                    mailFrom = null;
                    rcpts.clear();
                } else if (up.startsWith("RSET")) {
                    mailFrom = null;
                    rcpts.clear();
                    reply(out, "250 OK");
                } else if (up.startsWith("NOOP")) {
                    reply(out, "250 OK");
                } else if (up.startsWith("QUIT")) {
                    reply(out, "221 bye");
                    return;
                } else {
                    reply(out, "502 not here");
                }
            }
        } catch (IOException e) {
            // the other side went away
        }
    }

    // ---- HTTP: Admin -> Mail ----

    public void register(HttpServer server) {
        server.createContext("/api/admin/mail/", this::handle);
    }

    static final class Bad extends Exception {
        final int status;

        Bad(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private static String q(String s) {
        return ProfileApi.quote(s == null ? "" : s);
    }

    private static String p(String form, String k) {
        String v = MediaAuthHttpServer.queryParam(form, k);
        return v == null ? "" : v.trim();
    }

    private boolean admin(HttpExchange ex, String query) {
        if (SuperAgent.isAdmin(ex, sessions)) {
            return true;
        }
        SessionStore.Session s = sessions.get(p(query, "token")); // links (attachments) cannot send the sign-in header
        return s != null && s.role == UserStore.Role.ADMIN;
    }

    private List<Long> ids(String form) {
        List<Long> out = new ArrayList<Long>();
        for (String x : p(form, "ids").split(",")) {
            if (x.trim().matches("\\d{1,12}")) {
                out.add(Long.parseLong(x.trim()));
            }
        }
        return out;
    }

    private synchronized String itemJson(Item i) {
        return "{\"id\":" + i.id + ",\"folder\":" + q(i.folder) + ",\"from\":" + q(i.from) + ",\"fromName\":" + q(i.fromName) + ",\"to\":" + q(i.to)
                + ",\"subject\":" + q(i.subject) + ",\"date\":" + i.date + ",\"read\":" + i.read + ",\"attachments\":" + i.attachments
                + ",\"size\":" + i.size + ",\"snippet\":" + q(i.snippet) + ",\"blocked\":" + isBlocked(i.from) + "}";
    }

    private synchronized String counts() {
        StringBuilder sb = new StringBuilder("{");
        for (int k = 0; k < FOLDERS.length; k++) {
            int all = 0, unread = 0;
            for (Item i : items.values()) {
                if (i.folder.equals(FOLDERS[k])) {
                    all++;
                    if (!i.read) {
                        unread++;
                    }
                }
            }
            sb.append(k > 0 ? "," : "").append(q(FOLDERS[k])).append(":[").append(unread).append(',').append(all).append(']');
        }
        return sb.append('}').toString();
    }

    private void handle(HttpExchange ex) throws IOException {
        int status = 200;
        String out;
        try {
            String op = ex.getRequestURI().getPath().substring("/api/admin/mail/".length());
            String query = ex.getRequestURI().getRawQuery();
            if (!admin(ex, query)) {
                throw new Bad(403, "super-admin only");
            }
            boolean post = "POST".equalsIgnoreCase(ex.getRequestMethod());
            if (!post) {
                switch (op) {
                    case "list":
                        out = list(query);
                        break;
                    case "message":
                        out = message(Long.parseLong(p(query, "id")));
                        break;
                    case "attachment":
                    case "raw":
                        download(ex, op, query);
                        return;
                    case "blocked": {
                        StringBuilder sb = new StringBuilder("{\"blocked\":[");
                        synchronized (this) {
                            int k = 0;
                            for (String b : blocked) {
                                sb.append(k++ > 0 ? "," : "").append(q(b));
                            }
                        }
                        out = sb.append("]}").toString();
                        break;
                    }
                    case "status":
                        out = "{\"receiving\":" + (smtpPort() > 0) + ",\"port\":" + smtpPort() + ",\"lastReceived\":" + lastReceived + ",\"support\":" + q(support)
                                + ",\"counts\":" + counts() + "}";
                        break;
                    case "market":
                        out = marketList();
                        break;
                    case "market/thread":
                        out = marketThread(Long.parseLong(p(query, "listing")), p(query, "buyer"));
                        break;
                    default:
                        throw new Bad(404, "not found");
                }
            } else {
                String form = readBody(ex, op.equals("send") ? 40 * 1024 * 1024 : 65536);
                switch (op) {
                    case "send":
                        out = send(form);
                        break;
                    case "move":
                    case "delete":
                    case "read":
                    case "spam":
                    case "notspam":
                    case "restore":
                        out = act(op, ids(form), p(form, "folder"), p(form, "read"));
                        break;
                    case "empty": {
                        String f = p(form, "folder");
                        if (!f.equals("trash") && !f.equals("spam")) {
                            throw new Bad(400, "only Trash and Spam can be emptied");
                        }
                        synchronized (this) {
                            for (Item i : new ArrayList<Item>(items.values())) {
                                if (i.folder.equals(f)) {
                                    forget(i.id);
                                }
                            }
                        }
                        save();
                        out = "{\"counts\":" + counts() + "}";
                        break;
                    }
                    case "block":
                    case "unblock": {
                        String e = p(form, "entry").toLowerCase(Locale.ROOT);
                        if (!e.matches("@?[a-z0-9._%+'-]*@?[a-z0-9.-]+\\.[a-z]{2,}")) {
                            throw new Bad(400, "an address (someone@example.com) or a whole domain (@example.com)");
                        }
                        synchronized (this) {
                            if (op.equals("block")) {
                                blocked.add(e);
                            } else {
                                blocked.remove(e);
                            }
                        }
                        save();
                        out = "{\"ok\":true}";
                        break;
                    }
                    case "market/reply":
                        out = marketReply(Long.parseLong(p(form, "listing")), p(form, "buyer"), MediaAuthHttpServer.queryParam(form, "text"));
                        break;
                    default:
                        throw new Bad(404, "not found");
                }
            }
        } catch (Bad e) {
            status = e.status;
            out = "{\"error\":" + q(e.getMessage()) + "}";
        } catch (NumberFormatException e) {
            status = 400;
            out = "{\"error\":\"which message?\"}";
        } catch (Exception e) {
            status = 500;
            out = "{\"error\":" + q("server error: " + e.getMessage()) + "}";
        }
        byte[] b = out.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private static String readBody(HttpExchange ex, int max) throws IOException, Bad {
        InputStream in = ex.getRequestBody();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) {
            b.write(buf, 0, n);
            if (b.size() > max) {
                throw new Bad(413, "too big (the attachments together may be 25 MB)");
            }
        }
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    private synchronized String list(String query) {
        String folder = p(query, "folder");
        String needle = p(query, "q").toLowerCase(Locale.ROOT);
        int page = 0;
        try {
            page = Math.max(0, Integer.parseInt(p(query, "page")));
        } catch (NumberFormatException e) {
            page = 0;
        }
        List<Item> l = new ArrayList<Item>();
        for (Item i : items.values()) {
            if ((folder.isEmpty() || i.folder.equals(folder)) && (needle.isEmpty()
                    || (i.from + " " + i.fromName + " " + i.to + " " + i.subject + " " + i.snippet).toLowerCase(Locale.ROOT).contains(needle))) {
                l.add(i);
            }
        }
        l.sort((a, b) -> Long.compare(b.received, a.received));
        int from = Math.min(l.size(), page * 50);
        StringBuilder sb = new StringBuilder("{\"counts\":").append(counts()).append(",\"total\":").append(l.size()).append(",\"page\":").append(page)
                .append(",\"pages\":").append((l.size() + 49) / 50).append(",\"items\":[");
        List<Item> slice = l.subList(from, Math.min(l.size(), from + 50));
        for (int k = 0; k < slice.size(); k++) {
            sb.append(k > 0 ? "," : "").append(itemJson(slice.get(k)));
        }
        return sb.append("]}").toString();
    }

    private String message(long id) throws Exception {
        Item it;
        synchronized (this) {
            it = items.get(id);
        }
        if (it == null) {
            throw new Bad(404, "that email is gone");
        }
        MimeMail m = MimeMail.parse(Files.readAllBytes(eml(id)));
        synchronized (this) {
            if (!it.read) {
                it.read = true;
            }
        }
        save();
        StringBuilder sb = new StringBuilder(itemJson(it));
        sb.setLength(sb.length() - 1);
        sb.append(",\"cc\":").append(q(m.header("cc"))).append(",\"replyTo\":").append(q(MimeMail.address(m.header("reply-to"))))
                .append(",\"messageId\":").append(q(m.header("message-id"))).append(",\"references\":").append(q(m.header("references")))
                .append(",\"envelope\":").append(q(m.header("x-memoryq-envelope"))).append(",\"spamScore\":").append(spamScore(m))
                .append(",\"text\":").append(q(m.text)).append(",\"html\":").append(q(m.html)).append(",\"files\":[");
        for (int k = 0; k < m.attachments.size(); k++) {
            MimeMail.Attachment a = m.attachments.get(k);
            sb.append(k > 0 ? "," : "").append("{\"n\":").append(k).append(",\"name\":").append(q(a.name)).append(",\"type\":").append(q(a.type))
                    .append(",\"size\":").append(a.data.length).append(",\"cid\":").append(q(a.cid)).append('}');
        }
        return sb.append("]}").toString();
    }

    private void download(HttpExchange ex, String op, String query) throws Exception {
        long id = Long.parseLong(p(query, "id"));
        Item it;
        synchronized (this) {
            it = items.get(id);
        }
        if (it == null) {
            throw new Bad(404, "that email is gone");
        }
        byte[] raw = Files.readAllBytes(eml(id));
        byte[] body;
        String name, type;
        if (op.equals("raw")) {
            body = raw;
            name = "email-" + id + ".eml";
            type = "message/rfc822";
        } else {
            MimeMail m = MimeMail.parse(raw);
            int n = Integer.parseInt(p(query, "n"));
            if (n < 0 || n >= m.attachments.size()) {
                throw new Bad(404, "no such attachment");
            }
            MimeMail.Attachment a = m.attachments.get(n);
            body = a.data;
            name = a.name;
            type = a.type;
        }
        boolean inline = "1".equals(p(query, "inline")) && type.startsWith("image/") && !type.contains("svg");
        ex.getResponseHeaders().set("Content-Type", type.replaceAll("[\\r\\n]", ""));
        ex.getResponseHeaders().set("Content-Disposition", (inline ? "inline" : "attachment") + "; filename=\"" + name.replaceAll("[^A-Za-z0-9._ -]", "_") + "\"");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("Content-Security-Policy", "sandbox");
        ex.getResponseHeaders().set("Cache-Control", "private, max-age=600");
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    /** Moves, reads, deletes (Trash first, then for good), Spam (and blocks the sender), Not spam (and unblocks). */
    private String act(String op, List<Long> ids, String folder, String read) throws Bad {
        if (ids.isEmpty()) {
            throw new Bad(400, "which messages?");
        }
        synchronized (this) {
            for (long id : ids) {
                Item i = items.get(id);
                if (i == null) {
                    continue;
                }
                switch (op) {
                    case "move":
                        if (!Arrays.asList(FOLDERS).contains(folder)) {
                            throw new Bad(400, "no such folder");
                        }
                        i.folder = folder;
                        break;
                    case "read":
                        i.read = !"0".equals(read);
                        break;
                    case "delete":
                        if (i.folder.equals("trash")) {
                            forget(id);
                        } else {
                            i.before = i.folder;
                            i.folder = "trash";
                        }
                        break;
                    case "restore":
                        i.folder = i.before.isEmpty() || i.before.equals("trash") ? "inbox" : i.before;
                        break;
                    case "spam":
                        i.before = i.folder;
                        i.folder = "spam";
                        if (!i.from.isEmpty() && !i.from.endsWith("@memoryq.com")) {
                            blocked.add(i.from);
                        }
                        break;
                    case "notspam":
                        i.folder = "inbox";
                        blocked.remove(i.from);
                        if (i.from.contains("@")) {
                            blocked.remove(i.from.substring(i.from.indexOf('@')));
                        }
                        break;
                    default:
                        break;
                }
            }
        }
        save();
        return "{\"counts\":" + counts() + "}";
    }

    /** New mail, a reply or a forward - with attachments (files: [{name,type,b64}], and the original's when forwarding). */
    private String send(String form) throws Exception {
        Mailer m = mailer;
        if (m == null) {
            throw new Bad(503, "sending email is not set up");
        }
        List<String> to = addresses(p(form, "to")), cc = addresses(p(form, "cc"));
        if (to.isEmpty()) {
            throw new Bad(400, "who is it to? (an email address)");
        }
        String subject = c(p(form, "subject"), 300);
        String text = MediaAuthHttpServer.queryParam(form, "text");
        text = text == null ? "" : text;
        List<MimeMail.Attachment> files = new ArrayList<MimeMail.Attachment>();
        String inReplyTo = "", refs = "";
        String orig = p(form, "replyTo").isEmpty() ? p(form, "forward") : p(form, "replyTo");
        if (!orig.isEmpty()) {
            Item it;
            synchronized (this) {
                it = items.get(Long.parseLong(orig));
            }
            if (it != null) {
                MimeMail om = MimeMail.parse(Files.readAllBytes(eml(it.id)));
                if (!p(form, "replyTo").isEmpty()) {
                    inReplyTo = om.header("message-id");
                    refs = om.header("references");
                } else if (!"0".equals(p(form, "keepFiles"))) {
                    files.addAll(om.attachments);
                }
            }
        }
        String fj = MediaAuthHttpServer.queryParam(form, "files");
        if (fj != null && !fj.trim().isEmpty()) {
            Object arr = MiniJson.parse(fj);
            if (arr instanceof List) {
                for (Object o : (List<?>) arr) {
                    MimeMail.Attachment a = new MimeMail.Attachment();
                    a.name = c(MiniJson.str(o, "name"), 120);
                    a.type = MiniJson.str(o, "type").isEmpty() ? "application/octet-stream" : c(MiniJson.str(o, "type"), 100);
                    a.data = java.util.Base64.getDecoder().decode(MiniJson.str(o, "b64"));
                    files.add(a);
                }
            }
        }
        long total = 0;
        for (MimeMail.Attachment a : files) {
            total += a.data.length;
        }
        if (total > MAX_MAIL) {
            throw new Bad(413, "the attachments together may be 25 MB at most");
        }
        String[] built = MimeMail.build("Memoryq Support", support, to, cc, support, subject, text, files, inReplyTo, refs);
        List<String> all = new ArrayList<String>(to);
        all.addAll(cc);
        String err = m.sendRaw(support, all, built[0]);
        if (err != null) {
            throw new Bad(502, err);
        }
        long id = file(built[0].getBytes(StandardCharsets.UTF_8), "sent", null);
        if (!p(form, "replyTo").isEmpty()) {
            synchronized (this) {
                Item it = items.get(Long.parseLong(p(form, "replyTo")));
                if (it != null) {
                    it.read = true;
                }
            }
            save();
        }
        return "{\"sent\":" + id + ",\"counts\":" + counts() + "}";
    }

    static List<String> addresses(String s) {
        List<String> out = new ArrayList<String>();
        for (String part : s.split("[,;\\s]+")) {
            String a = MimeMail.address(part);
            if (!a.isEmpty() && Mailer.validAddress(a) && !out.contains(a)) {
                out.add(a);
            }
        }
        return out;
    }

    // ---- the Marketplace conversations ----

    private String marketList() throws Bad {
        MarketStore ms = market;
        if (ms == null) {
            throw new Bad(503, "the marketplace is not running");
        }
        Map<String, List<MarketStore.Message>> conv = new LinkedHashMap<String, List<MarketStore.Message>>();
        for (MarketStore.Message msg : ms.allMessages()) {
            conv.computeIfAbsent(msg.listing + "|" + msg.buyer.toLowerCase(Locale.ROOT), k -> new ArrayList<MarketStore.Message>()).add(msg);
        }
        List<String[]> rows = new ArrayList<String[]>();
        long now = clock.getAsLong();
        for (List<MarketStore.Message> t : conv.values()) {
            MarketStore.Message last = t.get(t.size() - 1);
            MarketStore.Listing l = ms.get(last.listing);
            String seller = l == null ? "" : l.seller;
            boolean waiting = !last.from.equalsIgnoreCase(seller) && !last.from.equalsIgnoreCase("memoryq");
            long hours = waiting ? (now - last.at) / 3600000L : 0;
            rows.add(new String[]{String.valueOf(last.listing), l == null ? "(listing removed)" : l.title, seller, last.buyer, last.from, last.text,
                String.valueOf(last.at), String.valueOf(t.size()), String.valueOf(waiting ? hours : -1)});
        }
        rows.sort((a, b) -> {
            long wa = Long.parseLong(a[8]), wb = Long.parseLong(b[8]);
            if ((wa >= 24) != (wb >= 24)) {
                return wa >= 24 ? -1 : 1;
            }
            return Long.compare(Long.parseLong(b[6]), Long.parseLong(a[6]));
        });
        StringBuilder sb = new StringBuilder("{\"conversations\":[");
        for (int k = 0; k < rows.size(); k++) {
            String[] r = rows.get(k);
            sb.append(k > 0 ? "," : "").append("{\"listing\":").append(r[0]).append(",\"title\":").append(q(r[1])).append(",\"seller\":").append(q(r[2]))
                    .append(",\"buyer\":").append(q(r[3])).append(",\"lastFrom\":").append(q(r[4])).append(",\"last\":").append(q(c(r[5], 160)))
                    .append(",\"at\":").append(r[6]).append(",\"count\":").append(r[7]).append(",\"waitingHours\":").append(r[8]).append('}');
        }
        return sb.append("]}").toString();
    }

    private String marketThread(long listing, String buyer) throws Bad {
        MarketStore ms = market;
        if (ms == null) {
            throw new Bad(503, "the marketplace is not running");
        }
        MarketStore.Listing l = ms.get(listing);
        StringBuilder sb = new StringBuilder("{\"listing\":").append(listing).append(",\"title\":").append(q(l == null ? "" : l.title))
                .append(",\"seller\":").append(q(l == null ? "" : l.seller)).append(",\"buyer\":").append(q(buyer)).append(",\"messages\":[");
        List<MarketStore.Message> t = ms.thread(listing, buyer);
        for (int k = 0; k < t.size(); k++) {
            MarketStore.Message msg = t.get(k);
            sb.append(k > 0 ? "," : "").append("{\"from\":").append(q(msg.from)).append(",\"text\":").append(q(msg.text)).append(",\"at\":").append(msg.at).append('}');
        }
        return sb.append("]}").toString();
    }

    /** The super-admin answers in the conversation (as "memoryq"); the buyer and the seller are told. */
    private String marketReply(long listing, String buyer, String text) throws Bad {
        MarketStore ms = market;
        if (ms == null) {
            throw new Bad(503, "the marketplace is not running");
        }
        String t = text == null ? "" : text.trim();
        if (t.isEmpty() || t.length() > 2000) {
            throw new Bad(400, "write the answer (2000 letters at most)");
        }
        MarketStore.Listing l = ms.get(listing);
        if (l == null || ms.thread(listing, buyer).isEmpty()) {
            throw new Bad(404, "no such conversation");
        }
        ms.addMessage(listing, buyer, "memoryq", t, clock.getAsLong());
        NotificationStore n = notes;
        if (n != null) {
            String line = "Memoryq support about \"" + (l.title.length() > 40 ? l.title.substring(0, 40) + "..." : l.title) + "\": " + (t.length() > 80 ? t.substring(0, 80) + "..." : t);
            n.add(buyer, "market", "Memoryq", 0, listing, line, clock.getAsLong());
            n.add(l.seller, "market", "Memoryq", 0, listing, line, clock.getAsLong());
        }
        return marketThread(listing, buyer);
    }
}
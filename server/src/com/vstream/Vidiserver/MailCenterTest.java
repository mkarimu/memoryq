package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Admin -> Mail: receiving over SMTP, folders, the spam blocker, reading, attachments, replying, forwarding, the Marketplace. */
public final class MailCenterTest {

    private static int failures = 0;
    private static int port;
    private static String admin;

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace();
            System.out.println("FAILED: " + t);
            System.exit(1);
        }
    }

    /** A pretend Postfix: keeps what the mail centre sends out. */
    static final List<String> outbox = Collections.synchronizedList(new ArrayList<String>());

    static int fakeSmtp() throws Exception {
        ServerSocket ss = new ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> {
            while (true) {
                try (Socket s = ss.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
                    OutputStream out = s.getOutputStream();
                    out.write("220 hi\r\n".getBytes());
                    String l;
                    StringBuilder data = null;
                    StringBuilder env = new StringBuilder();
                    while ((l = in.readLine()) != null) {
                        if (data != null) {
                            if (l.equals(".")) {
                                outbox.add(env + "\n" + data);
                                data = null;
                                out.write("250 ok\r\n".getBytes());
                            } else {
                                data.append(l).append("\r\n");
                            }
                            continue;
                        }
                        if (l.startsWith("DATA")) {
                            data = new StringBuilder();
                            out.write("354 go\r\n".getBytes());
                        } else if (l.startsWith("QUIT")) {
                            out.write("221 bye\r\n".getBytes());
                            break;
                        } else {
                            if (l.startsWith("RCPT") || l.startsWith("MAIL")) {
                                env.append(l).append(' ');
                            }
                            out.write("250 ok\r\n".getBytes());
                        }
                    }
                } catch (Exception e) {
                    // next
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return ss.getLocalPort();
    }

    static String smtpSend(int p, String from, String to, String msg) throws Exception {
        try (Socket s = new Socket("127.0.0.1", p)) {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
            OutputStream out = s.getOutputStream();
            StringBuilder log = new StringBuilder(in.readLine());
            String[] cmds = {"EHLO test", "MAIL FROM:<" + from + ">", "RCPT TO:<" + to + ">", "DATA"};
            for (String c : cmds) {
                out.write((c + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                String r;
                do {
                    r = in.readLine();
                    log.append('|').append(r);
                } while (r.length() > 3 && r.charAt(3) == '-');
            }
            StringBuilder body = new StringBuilder();
            for (String line : msg.split("\n", -1)) {
                body.append(line.startsWith(".") ? "." : "").append(line).append("\r\n");
            }
            body.append(".\r\nQUIT\r\n");
            out.write(body.toString().getBytes(StandardCharsets.ISO_8859_1));
            log.append('|').append(in.readLine()).append('|').append(in.readLine());
            return log.toString();
        }
    }

    static String http(String method, String path, String form) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
        c.setRequestProperty("Connection", "close"); // a fresh connection each time (Windows resets reused ones)
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        if (admin != null) {
            c.setRequestProperty("Authorization", "Bearer " + admin);
        }
        if (form != null) {
            c.setDoOutput(true);
            c.getOutputStream().write(form.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while (in != null && (n = in.read(buf)) > 0) {
            b.write(buf, 0, n);
        }
        return code + " " + new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    static String e(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    private static void run() throws Exception {
        Path dir = Files.createTempDirectory("memoryq-mail");
        long[] now = {1792700000000L};
        SessionStore sessions = new SessionStore();
        admin = sessions.create("boss", UserStore.Role.ADMIN);
        String member = sessions.create("amina", UserStore.Role.CHATTER);
        int out = fakeSmtp();
        Mailer mailer = new Mailer("127.0.0.1", out, "no-reply@memoryq.com", "Memoryq", "support@memoryq.com", false);
        MarketStore market = new MarketStore(dir.resolve("market.txt"), dir.resolve("photos"));
        market.init();
        NotificationStore notes = new NotificationStore(dir.resolve("notes.txt"));
        notes.init();
        MailCenter mc = new MailCenter(dir.resolve("mail"), sessions, () -> now[0]).mailer(mailer).market(market, notes);
        mc.init();
        Mailer.sentLog = m -> mc.logSystem(m[0], m[1], m[2]);
        mc.startSmtp(0 == 0 ? freePort() : 0);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mc.register(server);
        server.start();
        port = server.getAddress().getPort();

        System.out.println("-- Receiving --");
        String plain = "From: Juma Ali <juma@example.org>\nTo: support@memoryq.com\nSubject: =?UTF-8?B?SGFiYXJpIOKAkyBteSBzdGF0aW9u?=\n"
                + "Date: Mon, 19 Oct 2026 10:00:00 +0300\nMessage-ID: <abc@example.org>\n\nHello Memoryq,\n.my station will not play.\nJuma\n";
        String log = smtpSend(mc.smtpPort(), "juma@example.org", "support@memoryq.com", plain);
        checkTrue("Postfix's hand-over is accepted (" + log + ")", log.contains("250 OK kept"));
        String multi = "From: shop@example.net\nTo: support@memoryq.com\nSubject: Invoice\nMIME-Version: 1.0\n"
                + "Content-Type: multipart/mixed; boundary=\"XX\"\n\n--XX\nContent-Type: text/html; charset=utf-8\n\n<p>See the <b>invoice</b><img src=\"https://track.example/x.gif\"></p>\n"
                + "--XX\nContent-Type: application/pdf; name=\"invoice.pdf\"\nContent-Disposition: attachment; filename=\"invoice.pdf\"\nContent-Transfer-Encoding: base64\n\n"
                + java.util.Base64.getEncoder().encodeToString("%PDF-1.4 hello".getBytes()) + "\n--XX--\n";
        smtpSend(mc.smtpPort(), "shop@example.net", "support@memoryq.com", multi);
        smtpSend(mc.smtpPort(), "MAILER-DAEMON@memoryq.com", "root@memoryq.com", "From: MAILER-DAEMON@memoryq.com\nTo: root@memoryq.com\nSubject: Undelivered Mail\n\nbounce\n");
        smtpSend(mc.smtpPort(), "win@lotto.example", "support@memoryq.com", "From: win@lotto.example\nTo: support@memoryq.com\nSubject: YOU HAVE WON THE LOTTERY\n\nDear friend, you have won. Click here for your bitcoin casino gift card.\n");
        String list = http("GET", "/api/admin/mail/list?folder=inbox", null);
        checkTrue("two customer emails in the Inbox", list.startsWith("200") && list.contains("\"total\":2") && list.contains("Habari \u2013 my station"));
        checkTrue("the bounce is in Server", http("GET", "/api/admin/mail/list?folder=server", null).contains("Undelivered Mail"));
        checkTrue("the lottery email is in Spam", http("GET", "/api/admin/mail/list?folder=spam", null).contains("LOTTERY"));
        checkTrue("a member who is not the super-admin is refused", httpAs(member, "/api/admin/mail/list?folder=inbox").startsWith("403"));

        System.out.println("-- Reading --");
        long jumaId = idOf(list, "juma@example.org"), shopId = idOf(list, "shop@example.net");
        String m1 = http("GET", "/api/admin/mail/message?id=" + jumaId, null);
        checkTrue("the text, dot line unstuffed", m1.contains(".my station will not play.") && m1.contains("\"fromName\":\"Juma Ali\""));
        checkTrue("now read", http("GET", "/api/admin/mail/list?folder=inbox", null).contains("\"id\":" + jumaId + ",\"folder\":\"inbox\",\"from\":\"juma@example.org\",\"fromName\":\"Juma Ali\",\"to\":\"support@memoryq.com\",\"subject\":\"Habari \u2013 my station\",\"date\":1792393200000,\"read\":true"));
        String m2 = http("GET", "/api/admin/mail/message?id=" + shopId, null);
        checkTrue("HTML and the attachment are listed", m2.contains("\\u003cb\\u003einvoice") && m2.contains("\"name\":\"invoice.pdf\""));
        String att = http("GET", "/api/admin/mail/attachment?id=" + shopId + "&n=0", null);
        checkTrue("the attachment downloads", att.equals("200 %PDF-1.4 hello"));
        admin = null;
        checkTrue("an attachment link works with the token in it (and not without)",
                http("GET", "/api/admin/mail/attachment?id=" + shopId + "&n=0&token=" + sessions.create("boss", UserStore.Role.ADMIN), null).startsWith("200")
                && http("GET", "/api/admin/mail/attachment?id=" + shopId + "&n=0", null).startsWith("403"));
        admin = sessions.create("boss", UserStore.Role.ADMIN);

        System.out.println("-- Replying and forwarding --");
        String r = http("POST", "/api/admin/mail/send", "to=" + e("juma@example.org") + "&subject=" + e("Re: Habari") + "&replyTo=" + jumaId
                + "&text=" + e("Hi Juma,\n.Fixed now.\nMemoryq"));
        checkTrue("the reply is sent (" + r + ")", r.startsWith("200") && outbox.size() == 1 && outbox.get(0).contains("RCPT TO:<juma@example.org>")
                && outbox.get(0).contains("In-Reply-To: <abc@example.org>") && new String(java.util.Base64.getMimeDecoder().decode(outbox.get(0).substring(outbox.get(0).lastIndexOf("\r\n\r\n") + 4).trim()), StandardCharsets.UTF_8).contains("\n.Fixed now."));
        checkTrue("and kept in Sent", http("GET", "/api/admin/mail/list?folder=sent", null).contains("Re: Habari"));
        r = http("POST", "/api/admin/mail/send", "to=" + e("accounts@example.com") + "&subject=" + e("Fwd: Invoice") + "&forward=" + shopId
                + "&text=" + e("Please pay this.") + "&files=" + e("[{\"name\":\"note.txt\",\"type\":\"text/plain\",\"b64\":\"" + java.util.Base64.getEncoder().encodeToString("extra".getBytes()) + "\"}]"));
        checkTrue("the forward carries the original's attachment and a new one", r.startsWith("200") && outbox.size() == 2
                && outbox.get(1).contains("invoice.pdf") && outbox.get(1).contains("note.txt"));
        checkTrue("no address: refused", http("POST", "/api/admin/mail/send", "to=nobody&text=x").startsWith("400"));

        System.out.println("-- System mail --");
        mailer.send("amina@example.com", "Your Memoryq code", "Your code is 123456");
        checkTrue("what Memoryq sends is copied to System", http("GET", "/api/admin/mail/list?folder=system", null).contains("Your Memoryq code"));

        System.out.println("-- Spam blocker and deleting --");
        http("POST", "/api/admin/mail/spam", "ids=" + shopId);
        checkTrue("Spam blocks the sender", http("GET", "/api/admin/mail/blocked", null).contains("shop@example.net"));
        smtpSend(mc.smtpPort(), "shop@example.net", "support@memoryq.com", "From: shop@example.net\nTo: support@memoryq.com\nSubject: Again\n\nhi\n");
        checkTrue("their next email goes straight to Spam", http("GET", "/api/admin/mail/list?folder=spam", null).contains("Again")
                && !http("GET", "/api/admin/mail/list?folder=inbox", null).contains("Again"));
        http("POST", "/api/admin/mail/notspam", "ids=" + shopId);
        checkTrue("Not spam unblocks and puts it back", !http("GET", "/api/admin/mail/blocked", null).contains("shop@example.net")
                && http("GET", "/api/admin/mail/list?folder=inbox", null).contains("Invoice"));
        http("POST", "/api/admin/mail/block", "entry=" + e("@lotto.example"));
        checkTrue("a whole domain can be blocked", mc.isBlocked("anyone@lotto.example"));
        http("POST", "/api/admin/mail/delete", "ids=" + jumaId);
        checkTrue("Delete moves to Trash", http("GET", "/api/admin/mail/list?folder=trash", null).contains("Habari"));
        http("POST", "/api/admin/mail/restore", "ids=" + jumaId);
        checkTrue("Restore puts it back", http("GET", "/api/admin/mail/list?folder=inbox", null).contains("Habari"));
        http("POST", "/api/admin/mail/delete", "ids=" + jumaId);
        http("POST", "/api/admin/mail/delete", "ids=" + jumaId);
        checkTrue("Delete in Trash deletes for good", !Files.exists(dir.resolve("mail").resolve(jumaId + ".eml"))
                && http("GET", "/api/admin/mail/message?id=" + jumaId, null).startsWith("404"));
        MailCenter again = new MailCenter(dir.resolve("mail"), sessions, () -> now[0]);
        again.init();
        checkTrue("kept across a restart (blocked domain too)", again.isBlocked("x@lotto.example"));

        System.out.println("-- Marketplace --");
        MarketStore.Listing l = new MarketStore.Listing();
        l.seller = "seller1";
        l.title = "Used guitar";
        l = market.save(l, now[0]);
        market.addMessage(l.id, "buyer1", "buyer1", "Is it still for sale?", now[0]);
        now[0] += 30L * 3600000L;
        String mk = http("GET", "/api/admin/mail/market", null);
        checkTrue("the conversation shows, waiting 30 hours for the seller", mk.contains("\"title\":\"Used guitar\"") && mk.contains("\"waitingHours\":30"));
        String rep = http("POST", "/api/admin/mail/market/reply", "listing=" + l.id + "&buyer=buyer1&text=" + e("Yes - the seller will call you."));
        checkTrue("the super-admin answers in the conversation", rep.startsWith("200") && market.thread(l.id, "buyer1").size() == 2
                && market.thread(l.id, "buyer1").get(1).from.equals("memoryq"));
        checkTrue("the buyer and the seller are told", !notes.list("buyer1", 10).isEmpty() && !notes.list("seller1", 10).isEmpty());
        checkTrue("no longer waiting", http("GET", "/api/admin/mail/market", null).contains("\"waitingHours\":-1"));

        mc.stopSmtp();
        server.stop(0);
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    static String httpAs(String token, String path) throws Exception {
        String keep = admin;
        admin = token;
        try {
            return http("GET", path, null);
        } finally {
            admin = keep;
        }
    }

    static long idOf(String list, String from) {
        int k = list.indexOf("\"from\":\"" + from + "\"");
        int i = list.lastIndexOf("\"id\":", k);
        return Long.parseLong(list.substring(i + 5, list.indexOf(',', i)));
    }

    private static void checkTrue(String name, boolean ok) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + name);
        if (!ok) {
            failures++;
        }
    }
}
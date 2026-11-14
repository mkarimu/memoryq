package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Memoryq Radio Server licences on the web: memoryq.com/licence (buy, pay, the install command, a new key after a
 * server move), the API the installed servers call (activate, check in), Safaricom's M-Pesa callback, and
 * Admin -> Licences.
 *
 *   GET  /install.sh                         the installer (curl -fsSL https://memoryq.com/install.sh | sudo bash -s -- MQ1....)
 *   GET  /api/licence/mine                   POST /api/licence/new | pay | confirm | key | cancel     GET /api/licence/invoice?id=
 *   POST /api/licence/activate               (the installer)      POST /api/licence/checkin   (Authorization: Bearer MQS...)
 *   POST /api/licence/mpesa/callback         (Safaricom)
 *   GET  /api/admin/licences[/events]        POST /api/admin/licences/edit | create | markpaid | settings     (super-admin)
 */
public final class LicenseApi {

    public static final String SERVER_VERSION = "1.0.0";

    private final LicenseStore store;
    private final LicensePay pay;
    private final SessionStore sessions;
    private final LongSupplier clock;
    private volatile String publicBase = "https://memoryq.com";
    private volatile Mailer mailer;
    private volatile Function<String, String> emailOf;
    private volatile String adminEmail = "support@memoryq.com";

    public LicenseApi(LicenseStore store, LicensePay pay, SessionStore sessions, LongSupplier clock) {
        this.store = store;
        this.pay = pay;
        this.sessions = sessions;
        this.clock = clock;
    }

    public LicenseApi publicBase(String base) {
        this.publicBase = base.replaceAll("/+$", "");
        return this;
    }

    public LicenseApi mail(Mailer m, Function<String, String> emailOf, String adminEmail) {
        this.mailer = m;
        this.emailOf = emailOf;
        this.adminEmail = adminEmail;
        return this;
    }

    public void register(HttpServer server) {
        server.createContext("/install.sh", this::installer);
        server.createContext("/api/licence/", this::handle);
        server.createContext("/api/admin/licences", this::admin);
    }

    /** Every hour: renewal reminders, overdue / expired, silent servers. */
    public void start() {
        java.util.concurrent.ScheduledExecutorService ses = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "licences");
            t.setDaemon(true);
            return t;
        });
        ses.scheduleWithFixedDelay(() -> {
            try {
                for (String[] m : store.tick()) {
                    mailTo(m[0], m[1], m[2]);
                }
            } catch (RuntimeException e) {
                System.out.println("[licences] check failed: " + e);
            }
        }, 2, 60, java.util.concurrent.TimeUnit.MINUTES);
    }

    private void mailTo(String user, String subject, String text) {
        Mailer m = mailer;
        Function<String, String> f = emailOf;
        String to = f == null ? null : f.apply(user);
        if (m != null && to != null) {
            m.send(to, subject, "Hello " + user + ",\n\n" + text + "\nMemoryq\n");
        }
    }

    // ---- helpers ----

    static final class Bad extends Exception {
        private static final long serialVersionUID = 1L;
        final int status;

        Bad(int status, String m) {
            super(m);
            this.status = status;
        }
    }

    private static String q(String s) {
        return VideoApi.jq(s == null ? "" : s);
    }

    private static String p(String form, String k) {
        String v = MediaAuthHttpServer.queryParam(form, k);
        return v == null ? "" : v.trim();
    }

    private static String body(HttpExchange ex, int max) throws IOException, Bad {
        InputStream in = ex.getRequestBody();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            b.write(buf, 0, n);
            if (b.size() > max) {
                throw new Bad(413, "too big");
            }
        }
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange ex, int status, String type, String text) throws IOException {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    /** The caller's address (the real one when it came through Caddy on this server). */
    static String ip(HttpExchange ex) {
        String direct = ex.getRemoteAddress() == null ? "" : ex.getRemoteAddress().getAddress().getHostAddress();
        String f = ex.getRequestHeaders().getFirst("X-Forwarded-For");
        if (f != null && !f.trim().isEmpty() && ("127.0.0.1".equals(direct) || "0:0:0:0:0:0:0:1".equals(direct))) {
            return f.split(",")[0].trim();
        }
        return direct;
    }

    private SessionStore.Session me(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : "";
        return token.isEmpty() ? null : sessions.get(token);
    }

    String installCommand(String token) {
        return "curl -fsSL " + publicBase + "/install.sh | sudo bash -s -- " + token;
    }

    String licenceJson(LicenseStore.Licence x, boolean admin) {
        long now = clock.getAsLong();
        StringBuilder sb = new StringBuilder("{\"id\":").append(x.id).append(",\"owner\":").append(q(x.owner)).append(",\"kind\":").append(q(x.kind))
                .append(",\"label\":").append(q(x.label())).append(",\"channels\":").append(x.channels).append(",\"period\":").append(q(x.period))
                .append(",\"price\":").append(x.priceCents).append(",\"priceText\":").append(q(LicenseStore.money(x.priceCents, store.prices().currency)))
                .append(",\"customDays\":").append(x.customDays).append(",\"status\":").append(q(x.status)).append(",\"state\":").append(q(x.state(now)))
                .append(",\"created\":").append(x.created).append(",\"paidUntil\":").append(x.paidUntil).append(",\"firstPort\":").append(x.basePort)
                .append(",\"lastPort\":").append(x.lastPort()).append(",\"keyExpires\":").append(x.keyExpires).append(",\"keyUsed\":").append(x.keyUsed)
                .append(",\"hasKey\":").append(!x.keyId.isEmpty()).append(",\"bound\":").append(!x.serverHash.isEmpty()).append(",\"ip\":").append(q(x.ip))
                .append(",\"host\":").append(q(x.host)).append(",\"os\":").append(q(x.os)).append(",\"version\":").append(q(x.version))
                .append(",\"latest\":").append(q(SERVER_VERSION)).append(",\"activated\":").append(x.activated).append(",\"lastSeen\":").append(x.lastSeen)
                .append(",\"online\":").append(x.lastSeen > 0 && now - x.lastSeen < 15 * 60000L && !x.serverHash.isEmpty())
                .append(",\"stats\":").append(q(x.stats)).append(",\"note\":").append(q(x.note)).append(",\"score\":").append(store.score(x.id));
        if (RadioStoreSafe.isReseller(x)) {
            sb.append(",\"perChannel\":").append(store.perChannel(x.channels));
        }
        return sb.append('}').toString();
    }

    String invoiceJson(LicenseStore.Invoice i) {
        return "{\"id\":" + i.id + ",\"licence\":" + i.licence + ",\"owner\":" + q(i.owner) + ",\"cents\":" + i.cents + ",\"amount\":"
                + q(LicenseStore.money(i.cents, i.currency)) + ",\"kes\":" + pay.kes(i.cents) + ",\"what\":" + q(i.what) + ",\"days\":" + i.days
                + ",\"channels\":" + i.channels + ",\"method\":" + q(i.method) + ",\"status\":" + q(i.status) + ",\"ref\":" + q(i.ref)
                + ",\"created\":" + i.created + ",\"paid\":" + i.paid + ",\"note\":" + q(i.note) + "}";
    }

    String eventJson(LicenseStore.Event e) {
        return "{\"at\":" + e.at + ",\"licence\":" + e.licence + ",\"user\":" + q(e.user) + ",\"kind\":" + q(e.kind) + ",\"detail\":" + q(e.detail)
                + ",\"ip\":" + q(e.ip) + ",\"points\":" + e.points + "}";
    }

    String pricesJson() {
        LicenseStore.Prices p = store.prices();
        StringBuilder steps = new StringBuilder();
        for (int n = 10; n <= Math.min(p.maxChannels, 200); n += 10) {
            steps.append(n > 10 ? "," : "").append("{\"channels\":").append(n).append(",\"perChannel\":").append(store.perChannel(n))
                    .append(",\"month\":").append(store.price(LicenseStore.RESELLER, n, "monthly")).append(",\"year\":")
                    .append(store.price(LicenseStore.RESELLER, n, "annual")).append('}');
        }
        return "{\"currency\":" + q(p.currency) + ",\"singleMonth\":" + p.singleMonth + ",\"singleYear\":" + store.price(LicenseStore.SINGLE, 1, "annual")
                + ",\"channelMonth\":" + p.channelMonth + ",\"stepOff\":" + p.stepOff + ",\"stepSize\":" + p.stepSize + ",\"floor\":" + p.floor
                + ",\"annualMonths\":" + p.annualMonths + ",\"maxChannels\":" + p.maxChannels + ",\"keyTtlHours\":" + p.keyTtlHours + ",\"basePort\":" + p.basePort
                + ",\"steps\":[" + steps + "],\"methods\":{\"stripe\":" + pay.stripeReady() + ",\"paypal\":" + pay.paypalReady() + ",\"mpesa\":" + pay.mpesaReady()
                + ",\"manual\":true},\"kesPerUsd\":" + q(store.secret("mpesa.rate").isEmpty() ? "129" : store.secret("mpesa.rate")) + "}";
    }

    private String mine(String user) {
        StringBuilder sb = new StringBuilder("{\"prices\":").append(pricesJson()).append(",\"licences\":[");
        List<LicenseStore.Licence> ls = store.licences(user);
        for (int k = 0; k < ls.size(); k++) {
            sb.append(k > 0 ? "," : "").append(licenceJson(ls.get(k), false));
        }
        sb.append("],\"invoices\":[");
        List<LicenseStore.Invoice> is = store.invoices(user);
        int n = 0;
        for (int k = is.size() - 1; k >= 0 && n < 50; k--) {
            sb.append(n++ > 0 ? "," : "").append(invoiceJson(is.get(k)));
        }
        sb.append("],\"activity\":[");
        List<LicenseStore.Event> es = store.events(0, user, 60);
        for (int k = 0; k < es.size(); k++) {
            sb.append(k > 0 ? "," : "").append(eventJson(es.get(k)));
        }
        return sb.append("]}").toString();
    }

    private LicenseStore.Licence own(String user, String id) throws Bad {
        LicenseStore.Licence x;
        try {
            x = store.licence(Long.parseLong(id));
        } catch (NumberFormatException e) {
            x = null;
        }
        if (x == null || !x.owner.equalsIgnoreCase(user)) {
            throw new Bad(404, "no such licence");
        }
        return x;
    }

    private String what(LicenseStore.Invoice i, LicenseStore.Licence x) {
        return "Memoryq Radio Server - " + ("upgrade".equals(i.what) ? "upgrade to " + i.channels + " channels" : x.label() + ", "
                + ("annual".equals(x.period) ? "1 year" : "custom".equals(x.period) ? x.customDays + " days" : "1 month")) + " (licence #" + x.id + ")";
    }

    private void paidMail(LicenseStore.Licence x, LicenseStore.Invoice i) {
        mailTo(x.owner, "Payment received - Memoryq licence #" + x.id, "Thank you - we received " + LicenseStore.money(i.cents, i.currency) + " ("
                + i.method + ") for your " + x.label() + " licence.\nIt is paid until " + LicenseStore.date(x.paidUntil) + ".\n\n"
                + (x.keyId.isEmpty() ? "Your install command is ready at https://memoryq.com/licence\n" : ""));
        Mailer m = mailer;
        if (m != null && adminEmail != null && !adminEmail.isEmpty()) {
            m.send(adminEmail, "Licence payment: #" + x.id + " " + x.owner, x.owner + " paid " + LicenseStore.money(i.cents, i.currency) + " by " + i.method
                    + " (" + i.what + ") for " + x.label() + ". Paid until " + LicenseStore.date(x.paidUntil) + ".\n");
        }
        store.flushIfDirty();
    }

    // ---- the installer ----

    private void installer(HttpExchange ex) throws IOException {
        if (!ex.getRequestURI().getPath().equals("/install.sh")) {
            send(ex, 404, "text/plain; charset=utf-8", "not found");
            return;
        }
        send(ex, 200, "text/x-shellscript; charset=utf-8", InstallScript.JS.replace("@@BASE@@", publicBase).replace("@@VERSION@@", SERVER_VERSION));
    }

    // ---- members and servers ----

    private void handle(HttpExchange ex) throws IOException {
        int status = 200;
        String out;
        try {
            String op = ex.getRequestURI().getPath().substring("/api/licence/".length());
            String query = ex.getRequestURI().getRawQuery();
            boolean post = "POST".equalsIgnoreCase(ex.getRequestMethod());
            switch (op) {
                case "prices":
                    out = pricesJson();
                    break;
                case "check": { // the installer asks before changing anything on the server
                    if (!post) {
                        throw new Bad(405, "use POST");
                    }
                    LicenseStore.Answer a = store.peek(p(body(ex, 8192), "key"));
                    if (a.licence == null) {
                        throw new Bad(a.status, a.error);
                    }
                    LicenseStore.Licence x = a.licence;
                    out = "{\"ok\":true,\"licence\":" + x.id + ",\"kind\":" + q(x.kind) + ",\"channels\":" + x.channels + ",\"firstPort\":" + x.basePort
                            + ",\"lastPort\":" + x.lastPort() + ",\"paidUntil\":" + x.paidUntil + ",\"owner\":" + q(x.owner) + ",\"ip\":" + q(ip(ex))
                            + ",\"keyExpires\":" + x.keyExpires + "}";
                    break;
                }
                case "activate": {
                    if (!post) {
                        throw new Bad(405, "use POST");
                    }
                    String f = body(ex, 8192);
                    LicenseStore.Answer a = store.activate(p(f, "key"), ip(ex), p(f, "host"), p(f, "os"), p(f, "version"));
                    store.flushIfDirty();
                    if (a.status != 200) {
                        throw new Bad(a.status, a.error);
                    }
                    LicenseStore.Licence x = a.licence;
                    out = "{\"ok\":true,\"secret\":" + q(a.serverSecret) + ",\"licence\":" + x.id + ",\"kind\":" + q(x.kind) + ",\"channels\":" + x.channels
                            + ",\"firstPort\":" + x.basePort + ",\"lastPort\":" + x.lastPort() + ",\"paidUntil\":" + x.paidUntil + ",\"owner\":" + q(x.owner)
                            + ",\"ip\":" + q(x.ip) + ",\"latest\":" + q(SERVER_VERSION) + "}";
                    break;
                }
                case "checkin": {
                    if (!post) {
                        throw new Bad(405, "use POST");
                    }
                    String f = body(ex, 8192);
                    String auth = ex.getRequestHeaders().getFirst("Authorization");
                    String secret = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : "";
                    LicenseStore.Answer a = store.checkin(secret, ip(ex), p(f, "version"), p(f, "stats"));
                    store.flushIfDirty();
                    if (a.licence == null) {
                        throw new Bad(a.status, a.error);
                    }
                    LicenseStore.Licence x = a.licence;
                    long now = clock.getAsLong();
                    StringBuilder msg = new StringBuilder();
                    if (!SERVER_VERSION.equals(x.version)) {
                        msg.append("A new Memoryq Radio Server version (").append(SERVER_VERSION).append(") is ready: run  sudo mqctl update. ");
                    }
                    if (x.paidUntil - now < 7 * LicenseStore.DAY) {
                        msg.append("Your licence is paid until ").append(LicenseStore.date(x.paidUntil)).append(" - renew at memoryq.com/licence. ");
                    }
                    out = "{\"ok\":" + (a.status == 200) + ",\"state\":" + q(x.state(now)) + ",\"error\":" + q(a.error) + ",\"channels\":" + x.channels
                            + ",\"firstPort\":" + x.basePort + ",\"lastPort\":" + x.lastPort() + ",\"paidUntil\":" + x.paidUntil + ",\"latest\":"
                            + q(SERVER_VERSION) + ",\"message\":" + q(msg.toString().trim()) + "}";
                    status = a.status;
                    break;
                }
                case "mpesa/callback": {
                    String f = body(ex, 65536);
                    String[] cb = LicensePay.mpesaCallback(f);
                    if (cb != null) {
                        mpesaCallback(cb);
                    }
                    out = "{\"ResultCode\":0,\"ResultDesc\":\"Accepted\"}";
                    break;
                }
                default: {
                    SessionStore.Session s = me(ex);
                    if (s == null) {
                        throw new Bad(401, "sign in with your Memoryq account");
                    }
                    if (s.role == UserStore.Role.GUEST) {
                        throw new Bad(403, "the trial login cannot buy licences");
                    }
                    out = member(op, s.username, post, post ? body(ex, 16384) : query, ip(ex));
                }
            }
        } catch (Bad e) {
            status = e.status;
            out = "{\"error\":" + q(e.getMessage()) + "}";
        } catch (IllegalArgumentException e) {
            status = 400;
            out = "{\"error\":" + q(e.getMessage()) + "}";
        } catch (LicensePay.PayException e) {
            status = 502;
            out = "{\"error\":" + q(e.getMessage()) + "}";
        } catch (Exception e) {
            status = 500;
            out = "{\"error\":" + q("server error: " + e.getMessage()) + "}";
        }
        send(ex, status, "application/json; charset=utf-8", out);
    }

    private void mpesaCallback(String[] cb) {
        for (LicenseStore.Invoice i : store.invoices(null)) {
            if ("mpesa".equals(i.method) && "open".equals(i.status) && !i.ref.isEmpty() && i.ref.equals(cb[0])) {
                if (!"0".equals(cb[1])) {
                    store.failed(i.id, "M-Pesa result " + cb[1]);
                    store.flushIfDirty();
                    return;
                }
                try { // the callback is not signed: Safaricom is asked again before the licence is marked paid
                    if ("paid".equals(pay.mpesaStatus(i, clock.getAsLong()))) {
                        LicenseStore.Licence x = store.markPaid(i.id, "mpesa", cb[2].isEmpty() ? i.ref : cb[2], i.owner);
                        if (x != null) {
                            paidMail(x, i);
                        }
                    }
                } catch (LicensePay.PayException e) {
                    System.out.println("[licences] M-Pesa check failed: " + e.getMessage());
                }
                return;
            }
        }
    }

    private String member(String op, String user, boolean post, String form, String ip) throws Bad, LicensePay.PayException {
        switch (op) {
            case "mine":
                return mine(user);
            case "new": {
                if (!post) {
                    throw new Bad(405, "use POST");
                }
                int ch = 1;
                try {
                    ch = Integer.parseInt(p(form, "channels").isEmpty() ? "1" : p(form, "channels"));
                } catch (NumberFormatException e) {
                    throw new Bad(400, "how many channels?");
                }
                String period = p(form, "period");
                if (!"monthly".equals(period) && !"annual".equals(period)) {
                    throw new Bad(400, "monthly or annual? (custom deals: ask Memoryq)");
                }
                LicenseStore.Licence x = store.create(user, p(form, "kind"), ch, period, 0, 0, "");
                store.flushIfDirty();
                return "{\"licence\":" + licenceJson(x, false) + "}";
            }
            case "cancel": {
                LicenseStore.Licence x = own(user, p(form, "licence"));
                if (!"pending".equals(x.status)) {
                    throw new Bad(409, "only a licence that was never paid can be removed here");
                }
                Map<String, String> v = new LinkedHashMap<String, String>();
                v.put("status", "cancelled");
                store.adminEdit(x.id, v, user);
                store.flushIfDirty();
                return mine(user);
            }
            case "pay": {
                if (!post) {
                    throw new Bad(405, "use POST");
                }
                LicenseStore.Licence x = own(user, p(form, "licence"));
                String method = p(form, "method");
                if (!method.matches("stripe|paypal|mpesa|manual")) {
                    throw new Bad(400, "pay by card, PayPal, M-Pesa or bank transfer?");
                }
                int ch = 0;
                try {
                    ch = p(form, "channels").isEmpty() ? 0 : Integer.parseInt(p(form, "channels"));
                } catch (NumberFormatException e) {
                    throw new Bad(400, "how many channels?");
                }
                LicenseStore.Invoice i = store.bill(x.id, "upgrade".equals(p(form, "what")) ? "upgrade" : "period", ch, method);
                store.flushIfDirty();
                String w = what(i, x);
                switch (method) {
                    case "stripe":
                        return "{\"invoice\":" + invoiceJson(i) + ",\"redirect\":" + q(pay.stripeCheckout(i, w, publicBase)) + "}";
                    case "paypal":
                        return "{\"invoice\":" + invoiceJson(i) + ",\"redirect\":" + q(pay.paypalOrder(i, w, publicBase)) + "}";
                    case "mpesa":
                        pay.mpesaPush(i, p(form, "phone"), publicBase, clock.getAsLong());
                        store.flushIfDirty();
                        return "{\"invoice\":" + invoiceJson(store.invoice(i.id)) + ",\"waiting\":true}";
                    default:
                        store.event(x.id, user, "admin", "asked to pay " + LicenseStore.money(i.cents, i.currency) + " by bank transfer (invoice #" + i.id + ")", ip);
                        store.flushIfDirty();
                        mailTo(user, "Memoryq invoice #" + i.id, "Invoice #" + i.id + ": " + w + "\nAmount: " + LicenseStore.money(i.cents, i.currency)
                                + "\n\nPay by bank transfer with the reference MQ-INV-" + i.id + ". Memoryq confirms it and your licence starts.\n");
                        Mailer m = mailer;
                        if (m != null && adminEmail != null) {
                            m.send(adminEmail, "Licence invoice to confirm: #" + i.id, user + " will pay " + LicenseStore.money(i.cents, i.currency)
                                    + " by bank transfer (reference MQ-INV-" + i.id + "). Mark it paid in Admin -> Licences -> Payments when it arrives.\n");
                        }
                        return "{\"invoice\":" + invoiceJson(i) + ",\"manual\":true,\"reference\":" + q("MQ-INV-" + i.id) + "}";
                }
            }
            case "confirm": {
                LicenseStore.Invoice i;
                try {
                    i = store.invoice(Long.parseLong(p(form, "invoice")));
                } catch (NumberFormatException e) {
                    i = null;
                }
                if (i == null || !i.owner.equalsIgnoreCase(user)) {
                    throw new Bad(404, "no such payment");
                }
                if ("paid".equals(i.status)) {
                    return "{\"paid\":true," + mine(user).substring(1);
                }
                boolean ok;
                if ("stripe".equals(i.method)) {
                    ok = pay.stripePaid(i, p(form, "session"));
                } else if ("paypal".equals(i.method)) {
                    ok = pay.paypalCapture(i, p(form, "token"));
                } else {
                    throw new Bad(400, "this payment is confirmed another way");
                }
                if (!ok) {
                    throw new Bad(402, "the payment is not finished yet - if you paid, wait a minute and reload");
                }
                LicenseStore.Licence x = store.markPaid(i.id, i.method, p(form, "session").isEmpty() ? p(form, "token") : p(form, "session"), user);
                if (x != null) {
                    paidMail(x, i);
                }
                return "{\"paid\":true," + mine(user).substring(1);
            }
            case "invoice": { // M-Pesa: waiting for the PIN on the phone
                LicenseStore.Invoice i;
                try {
                    i = store.invoice(Long.parseLong(p(form, "id")));
                } catch (NumberFormatException e) {
                    i = null;
                }
                if (i == null || !i.owner.equalsIgnoreCase(user)) {
                    throw new Bad(404, "no such payment");
                }
                String st = i.status;
                String why = "";
                if ("open".equals(st) && "mpesa".equals(i.method)) {
                    String r = pay.mpesaStatus(i, clock.getAsLong());
                    if ("paid".equals(r)) {
                        LicenseStore.Licence x = store.markPaid(i.id, "mpesa", i.ref, user);
                        if (x != null) {
                            paidMail(x, i);
                        }
                        st = "paid";
                    } else if (!"waiting".equals(r)) {
                        store.failed(i.id, r);
                        store.flushIfDirty();
                        st = "failed";
                        why = r;
                    }
                } else if ("failed".equals(st)) {
                    why = i.note;
                }
                return "{\"status\":" + q(st) + ",\"why\":" + q(why) + "}";
            }
            case "key": {
                if (!post) {
                    throw new Bad(405, "use POST");
                }
                LicenseStore.Licence x = own(user, p(form, "licence"));
                String token = store.newKey(x.id, user, ip);
                store.flushIfDirty();
                LicenseStore.Licence after = store.licence(x.id);
                if (!x.ip.isEmpty()) {
                    mailTo(user, "New install command for Memoryq licence #" + x.id, "A new install command was made for your licence #" + x.id
                            + ". The server " + x.ip + " no longer runs on it.\nIf this was not you, change your Memoryq password now.\n");
                }
                return "{\"command\":" + q(installCommand(token)) + ",\"key\":" + q(token) + ",\"expires\":" + after.keyExpires + ",\"licence\":"
                        + licenceJson(after, false) + "}";
            }
            default:
                throw new Bad(404, "not found");
        }
    }

    // ---- the super-admin ----

    private void admin(HttpExchange ex) throws IOException {
        int status = 200;
        String out;
        try {
            if (!SuperAgent.isAdmin(ex, sessions)) {
                throw new Bad(403, "super-admin only");
            }
            String path = ex.getRequestURI().getPath();
            String op = path.length() > "/api/admin/licences".length() ? path.substring("/api/admin/licences/".length()) : "";
            boolean post = "POST".equalsIgnoreCase(ex.getRequestMethod());
            String form = post ? body(ex, 16384) : ex.getRequestURI().getRawQuery();
            SessionStore.Session s = me(ex);
            String by = s == null ? "admin" : s.username;
            switch (op) {
                case "":
                    out = adminList();
                    break;
                case "events": {
                    long id = p(form, "licence").isEmpty() ? 0 : Long.parseLong(p(form, "licence"));
                    StringBuilder sb = new StringBuilder("{\"events\":[");
                    List<LicenseStore.Event> es = store.events(id, null, 300);
                    for (int k = 0; k < es.size(); k++) {
                        sb.append(k > 0 ? "," : "").append(eventJson(es.get(k)));
                    }
                    out = sb.append("]}").toString();
                    break;
                }
                case "edit": {
                    Map<String, String> v = new LinkedHashMap<String, String>();
                    for (String k : new String[] {"status", "paidUntil", "channels", "period", "customDays", "price", "basePort", "note", "unbind"}) {
                        String val = MediaAuthHttpServer.queryParam(form, k);
                        if (val != null) {
                            v.put(k, val.trim());
                        }
                    }
                    store.adminEdit(Long.parseLong(p(form, "id")), v, by);
                    store.flushIfDirty();
                    out = adminList();
                    break;
                }
                case "create": { // a custom deal: the admin sets the price and the days; may start it as paid at once
                    String period = p(form, "period").isEmpty() ? "custom" : p(form, "period");
                    long cents = p(form, "price").isEmpty() ? 0 : Math.round(Double.parseDouble(p(form, "price")) * 100);
                    int days = p(form, "days").isEmpty() ? 30 : Integer.parseInt(p(form, "days"));
                    String owner = p(form, "owner");
                    if (!owner.matches("[A-Za-z0-9._-]{2,40}")) {
                        throw new Bad(400, "the customer's Memoryq username");
                    }
                    LicenseStore.Licence x = store.create(owner, p(form, "kind"), p(form, "channels").isEmpty() ? 1 : Integer.parseInt(p(form, "channels")),
                            period, cents, days, p(form, "note"));
                    if ("1".equals(p(form, "paid"))) {
                        LicenseStore.Invoice i = store.bill(x.id, "period", 0, "manual");
                        store.markPaid(i.id, "manual", p(form, "ref").isEmpty() ? "set by the admin" : p(form, "ref"), by);
                    }
                    store.flushIfDirty();
                    out = adminList();
                    break;
                }
                case "markpaid": {
                    LicenseStore.Licence x = store.markPaid(Long.parseLong(p(form, "invoice")), "manual", p(form, "ref"), by);
                    if (x == null) {
                        throw new Bad(409, "that payment is already paid (or gone)");
                    }
                    paidMail(x, store.invoice(Long.parseLong(p(form, "invoice"))));
                    out = adminList();
                    break;
                }
                case "settings": {
                    if (post) {
                        LicenseStore.Prices cur = store.prices();
                        LicenseStore.Prices n = new LicenseStore.Prices();
                        n.singleMonth = cents(form, "singleMonth", cur.singleMonth);
                        n.channelMonth = cents(form, "channelMonth", cur.channelMonth);
                        n.stepOff = cents(form, "stepOff", cur.stepOff);
                        n.floor = cents(form, "floor", cur.floor);
                        n.stepSize = num(form, "stepSize", cur.stepSize);
                        n.annualMonths = num(form, "annualMonths", cur.annualMonths);
                        n.maxChannels = num(form, "maxChannels", cur.maxChannels);
                        n.keyTtlHours = num(form, "keyTtlHours", cur.keyTtlHours);
                        n.basePort = num(form, "basePort", cur.basePort);
                        n.currency = cur.currency;
                        store.setPrices(n);
                        for (String k : new String[] {"paypal.client", "paypal.secret", "paypal.mode", "mpesa.key", "mpesa.secret", "mpesa.shortcode", "mpesa.passkey",
                            "mpesa.type", "mpesa.till", "mpesa.env", "mpesa.rate"}) {
                            String v = MediaAuthHttpServer.queryParam(form, k);
                            if (v != null && !v.trim().isEmpty()) {
                                store.setSecret(k, v);
                            }
                        }
                        store.flushIfDirty();
                    }
                    out = adminList();
                    break;
                }
                default:
                    throw new Bad(404, "not found");
            }
        } catch (Bad e) {
            status = e.status;
            out = "{\"error\":" + q(e.getMessage()) + "}";
        } catch (IllegalArgumentException e) {
            status = 400;
            out = "{\"error\":" + q(e.getMessage()) + "}";
        } catch (Exception e) {
            status = 500;
            out = "{\"error\":" + q("server error: " + e.getMessage()) + "}";
        }
        send(ex, status, "application/json; charset=utf-8", out);
    }

    private static long cents(String form, String k, long old) {
        String v = MediaAuthHttpServer.queryParam(form, k);
        if (v == null || v.trim().isEmpty()) {
            return old;
        }
        return Math.round(Double.parseDouble(v.trim().replace("$", "").replace(",", "")) * 100);
    }

    private static int num(String form, String k, int old) {
        String v = MediaAuthHttpServer.queryParam(form, k);
        return v == null || v.trim().isEmpty() ? old : Integer.parseInt(v.trim());
    }

    private String hint(String k) {
        String v = store.secret(k);
        return v.isEmpty() ? "" : (v.length() <= 6 ? "set" : v.substring(0, 3) + "..." + v.substring(v.length() - 3));
    }

    private String adminList() {
        long now = clock.getAsLong();
        StringBuilder sb = new StringBuilder("{\"prices\":").append(pricesJson()).append(",\"licences\":[");
        List<LicenseStore.Licence> ls = store.licences(null);
        long monthly = 0;
        int active = 0, online = 0, channels = 0;
        for (int k = ls.size() - 1, n = 0; k >= 0; k--) {
            LicenseStore.Licence x = ls.get(k);
            sb.append(n++ > 0 ? "," : "").append(licenceJson(x, true));
            String st = x.state(now);
            if ("active".equals(st) || "overdue".equals(st)) {
                active++;
                channels += x.channels;
                monthly += "annual".equals(x.period) ? x.priceCents / 12 : "custom".equals(x.period) ? x.priceCents * 30 / Math.max(1, x.customDays) : x.priceCents;
                if (x.lastSeen > 0 && now - x.lastSeen < 15 * 60000L && !x.serverHash.isEmpty()) {
                    online++;
                }
            }
        }
        sb.append("],\"invoices\":[");
        List<LicenseStore.Invoice> is = store.invoices(null);
        for (int k = is.size() - 1, n = 0; k >= 0 && n < 300; k--) {
            sb.append(n++ > 0 ? "," : "").append(invoiceJson(is.get(k)));
        }
        sb.append("],\"summary\":{\"active\":").append(active).append(",\"online\":").append(online).append(",\"channels\":").append(channels)
                .append(",\"monthly\":").append(q(LicenseStore.money(monthly, store.prices().currency))).append("},\"keys\":{\"paypalClient\":")
                .append(q(hint("paypal.client"))).append(",\"paypalSecret\":").append(q(hint("paypal.secret"))).append(",\"paypalMode\":")
                .append(q(store.secret("paypal.mode").isEmpty() ? "sandbox" : store.secret("paypal.mode"))).append(",\"mpesaKey\":").append(q(hint("mpesa.key")))
                .append(",\"mpesaSecret\":").append(q(hint("mpesa.secret"))).append(",\"mpesaShortcode\":").append(q(store.secret("mpesa.shortcode")))
                .append(",\"mpesaPasskey\":").append(q(hint("mpesa.passkey"))).append(",\"mpesaType\":")
                .append(q(store.secret("mpesa.type").isEmpty() ? "paybill" : store.secret("mpesa.type"))).append(",\"mpesaTill\":")
                .append(q(store.secret("mpesa.till"))).append(",\"mpesaEnv\":").append(q(store.secret("mpesa.env").isEmpty() ? "sandbox" : store.secret("mpesa.env")))
                .append(",\"mpesaCallback\":").append(q(publicBase + "/api/licence/mpesa/callback")).append("}}");
        return sb.toString();
    }

    /** Small helper so the licence JSON knows when to show the per-channel price. */
    static final class RadioStoreSafe {
        static boolean isReseller(LicenseStore.Licence x) {
            return LicenseStore.RESELLER.equals(x.kind);
        }
    }
}
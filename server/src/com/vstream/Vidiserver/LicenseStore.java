package com.vstream.Vidiserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Memoryq Radio Server licences: Single (one licence, one streaming port - 8000) and Reseller (10, 20, 30... channels,
 * ports 8000 upwards), paid monthly, yearly or on a custom deal. Each licence has an install key: encrypted, with an
 * expiry (TTL), used once to activate one server, after which the licence is tied to that server's IP address. A new
 * key (moved server / new IP) is made only from the customer's own account page. Everything that happens is logged and
 * scored (Admin -> Licences).
 *
 * Files: licences.txt (licences, invoices, events, prices) and licence-secrets.txt (the encryption secret and the
 * PayPal / M-Pesa keys - readable by the server only). Keys and server secrets are kept only as SHA-256 hashes.
 */
public final class LicenseStore {

    public static final String SINGLE = "single";
    public static final String RESELLER = "reseller";
    public static final String[] PERIODS = {"monthly", "annual", "custom"};
    static final long DAY = 86400000L;
    static final long GRACE = 7 * DAY;

    /** Prices and rules (Admin -> Licences -> Prices and payments). Money in cents (USD unless changed). */
    public static final class Prices {
        public long singleMonth = 900;
        public long channelMonth = 500;
        public long stepOff = 25;
        public int stepSize = 10;
        public long floor = 300;
        public int annualMonths = 10;
        public int maxChannels = 500;
        public int keyTtlHours = 72;
        public int basePort = 8000;
        public String currency = "usd";
    }

    public static final class Licence {
        public long id;
        public String owner = "";
        public String kind = SINGLE;
        public int channels = 1;
        public String period = "monthly";
        public long priceCents;
        public int customDays;
        public String status = "pending"; // pending, active, suspended, cancelled
        public long created;
        public long paidUntil;
        public int basePort = 8000;
        public String note = "";
        // the install key (only its id and hash are kept)
        public String keyId = "";
        public String keyHash = "";
        public long keyExpires;
        public boolean keyUsed;
        // the server it runs on
        public String serverHash = "";
        public String ip = "";
        public String host = "";
        public String os = "";
        public String version = "";
        public long activated;
        public long lastSeen;
        public String stats = "";
        public String reminded = "";

        Licence copy() {
            Licence l = new Licence();
            l.id = id;
            l.owner = owner;
            l.kind = kind;
            l.channels = channels;
            l.period = period;
            l.priceCents = priceCents;
            l.customDays = customDays;
            l.status = status;
            l.created = created;
            l.paidUntil = paidUntil;
            l.basePort = basePort;
            l.note = note;
            l.keyId = keyId;
            l.keyHash = keyHash;
            l.keyExpires = keyExpires;
            l.keyUsed = keyUsed;
            l.serverHash = serverHash;
            l.ip = ip;
            l.host = host;
            l.os = os;
            l.version = version;
            l.activated = activated;
            l.lastSeen = lastSeen;
            l.stats = stats;
            l.reminded = reminded;
            return l;
        }

        public String label() {
            return SINGLE.equals(kind) ? "Single (1 channel)" : "Reseller (" + channels + " channels)";
        }

        /** active / overdue (in the 7 days after the paid time) / expired / pending / suspended / cancelled. */
        public String state(long now) {
            if (!"active".equals(status)) {
                return status;
            }
            if (now <= paidUntil) {
                return "active";
            }
            return now <= paidUntil + GRACE ? "overdue" : "expired";
        }

        public int lastPort() {
            return basePort + Math.max(1, channels) - 1;
        }
    }

    public static final class Invoice {
        public long id;
        public long licence;
        public String owner = "";
        public long cents;
        public String currency = "usd";
        public String what = "";      // new, renew, upgrade
        public int days;              // how much paid time it buys (renew/new)
        public int channels;          // the channels after paying (upgrade/new)
        public String method = "";    // stripe, paypal, mpesa, manual
        public String status = "open";
        public String ref = "";
        public long created;
        public long paid;
        public String note = "";
    }

    public static final class Event {
        public long at;
        public long licence;
        public String user = "";
        public String kind = "";
        public String detail = "";
        public String ip = "";
        public int points;
    }

    /** What each kind of activity adds to (or takes off) the licence's score. */
    static final Map<String, Integer> POINTS = new LinkedHashMap<String, Integer>();
    static {
        POINTS.put("created", 0);
        POINTS.put("paid", 20);
        POINTS.put("renewed", 20);
        POINTS.put("upgraded", 10);
        POINTS.put("activated", 10);
        POINTS.put("checkin", 1);
        POINTS.put("key", -2);
        POINTS.put("key-expired", -3);
        POINTS.put("ip-mismatch", -10);
        POINTS.put("bad-key", -5);
        POINTS.put("offline", -5);
        POINTS.put("overdue", -10);
        POINTS.put("expired", -20);
        POINTS.put("suspended", -20);
        POINTS.put("payment-failed", -5);
        POINTS.put("admin", 0);
    }

    private final Path file;
    private final Path secrets;
    private final LongSupplier clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<Long, Licence> licences = new LinkedHashMap<Long, Licence>();
    private final Map<Long, Invoice> invoices = new LinkedHashMap<Long, Invoice>();
    private final List<Event> events = new ArrayList<Event>();
    private final Map<String, String> secret = new LinkedHashMap<String, String>();
    private Prices prices = new Prices();
    private long nextLicence = 1;
    private long nextInvoice = 1;
    private boolean dirty;

    public LicenseStore(Path file, Path secrets, LongSupplier clock) {
        this.file = file;
        this.secrets = secrets;
        this.clock = clock;
    }

    // ---- files ----

    private static String c(String s, int max) {
        String t = s == null ? "" : s.replaceAll("[\\t\\r\\n]+", " ").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static long l(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public synchronized void init() throws IOException {
        if (Files.isRegularFile(secrets)) {
            for (String line : Files.readAllLines(secrets, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq > 0 && !line.startsWith("#")) {
                    secret.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }
        }
        if (!secret.containsKey("aes")) {
            byte[] k = new byte[32];
            random.nextBytes(k);
            secret.put("aes", Base64.getEncoder().encodeToString(k));
            saveSecrets();
        }
        if (!Files.isRegularFile(file)) {
            return;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] f = line.split("\t", -1);
            try {
                if (f[0].equals("L") && f.length >= 27) {
                    Licence x = new Licence();
                    x.id = l(f[1]);
                    x.owner = f[2];
                    x.kind = f[3];
                    x.channels = (int) l(f[4]);
                    x.period = f[5];
                    x.priceCents = l(f[6]);
                    x.customDays = (int) l(f[7]);
                    x.status = f[8];
                    x.created = l(f[9]);
                    x.paidUntil = l(f[10]);
                    x.basePort = (int) l(f[11]);
                    x.note = f[12];
                    x.keyId = f[13];
                    x.keyHash = f[14];
                    x.keyExpires = l(f[15]);
                    x.keyUsed = "1".equals(f[16]);
                    x.serverHash = f[17];
                    x.ip = f[18];
                    x.host = f[19];
                    x.os = f[20];
                    x.version = f[21];
                    x.activated = l(f[22]);
                    x.lastSeen = l(f[23]);
                    x.stats = f[24];
                    x.reminded = f[25];
                    licences.put(x.id, x);
                    nextLicence = Math.max(nextLicence, x.id + 1);
                } else if (f[0].equals("I") && f.length >= 15) {
                    Invoice i = new Invoice();
                    i.id = l(f[1]);
                    i.licence = l(f[2]);
                    i.owner = f[3];
                    i.cents = l(f[4]);
                    i.currency = f[5];
                    i.what = f[6];
                    i.days = (int) l(f[7]);
                    i.channels = (int) l(f[8]);
                    i.method = f[9];
                    i.status = f[10];
                    i.ref = f[11];
                    i.created = l(f[12]);
                    i.paid = l(f[13]);
                    i.note = f[14];
                    invoices.put(i.id, i);
                    nextInvoice = Math.max(nextInvoice, i.id + 1);
                } else if (f[0].equals("E") && f.length >= 8) {
                    Event e = new Event();
                    e.at = l(f[1]);
                    e.licence = l(f[2]);
                    e.user = f[3];
                    e.kind = f[4];
                    e.detail = f[5];
                    e.ip = f[6];
                    e.points = (int) l(f[7]);
                    events.add(e);
                } else if (f[0].equals("P") && f.length >= 12) {
                    Prices p = new Prices();
                    p.singleMonth = l(f[1]);
                    p.channelMonth = l(f[2]);
                    p.stepOff = l(f[3]);
                    p.stepSize = Math.max(1, (int) l(f[4]));
                    p.floor = l(f[5]);
                    p.annualMonths = (int) l(f[6]);
                    p.maxChannels = (int) l(f[7]);
                    p.keyTtlHours = Math.max(1, (int) l(f[8]));
                    p.basePort = (int) l(f[9]);
                    p.currency = f[10];
                    prices = p;
                }
            } catch (RuntimeException e) {
                // a damaged line
            }
        }
    }

    public synchronized void flushIfDirty() {
        if (!dirty) {
            return;
        }
        StringBuilder sb = new StringBuilder("# Memoryq Radio Server licences (L), invoices (I), activity (E), prices (P)\n");
        Prices p = prices;
        sb.append("P\t").append(p.singleMonth).append('\t').append(p.channelMonth).append('\t').append(p.stepOff).append('\t').append(p.stepSize)
                .append('\t').append(p.floor).append('\t').append(p.annualMonths).append('\t').append(p.maxChannels).append('\t').append(p.keyTtlHours)
                .append('\t').append(p.basePort).append('\t').append(p.currency).append("\tend\n");
        for (Licence x : licences.values()) {
            sb.append("L\t").append(x.id).append('\t').append(c(x.owner, 40)).append('\t').append(x.kind).append('\t').append(x.channels).append('\t')
                    .append(x.period).append('\t').append(x.priceCents).append('\t').append(x.customDays).append('\t').append(x.status).append('\t')
                    .append(x.created).append('\t').append(x.paidUntil).append('\t').append(x.basePort).append('\t').append(c(x.note, 200)).append('\t')
                    .append(x.keyId).append('\t').append(x.keyHash).append('\t').append(x.keyExpires).append('\t').append(x.keyUsed ? 1 : 0).append('\t')
                    .append(x.serverHash).append('\t').append(c(x.ip, 60)).append('\t').append(c(x.host, 100)).append('\t').append(c(x.os, 100)).append('\t')
                    .append(c(x.version, 30)).append('\t').append(x.activated).append('\t').append(x.lastSeen).append('\t').append(c(x.stats, 300))
                    .append('\t').append(c(x.reminded, 60)).append("\tend\n");
        }
        for (Invoice i : invoices.values()) {
            sb.append("I\t").append(i.id).append('\t').append(i.licence).append('\t').append(c(i.owner, 40)).append('\t').append(i.cents).append('\t')
                    .append(i.currency).append('\t').append(i.what).append('\t').append(i.days).append('\t').append(i.channels).append('\t').append(i.method)
                    .append('\t').append(i.status).append('\t').append(c(i.ref, 120)).append('\t').append(i.created).append('\t').append(i.paid).append('\t')
                    .append(c(i.note, 200)).append("\tend\n");
        }
        for (Event e : events) {
            sb.append("E\t").append(e.at).append('\t').append(e.licence).append('\t').append(c(e.user, 40)).append('\t').append(e.kind).append('\t')
                    .append(c(e.detail, 200)).append('\t').append(c(e.ip, 60)).append('\t').append(e.points).append('\n');
        }
        try {
            Path part = file.resolveSibling(file.getFileName() + ".part");
            Files.write(part, sb.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } catch (IOException e) {
            System.out.println("[licences] could not save: " + e.getMessage());
        }
    }

    private synchronized void saveSecrets() throws IOException {
        StringBuilder sb = new StringBuilder("# Memoryq licences - keep private (the key secret, PayPal and M-Pesa keys)\n");
        for (Map.Entry<String, String> e : secret.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        Path part = secrets.resolveSibling(secrets.getFileName() + ".part");
        Files.write(part, sb.toString().getBytes(StandardCharsets.UTF_8));
        try {
            Files.setPosixFilePermissions(part, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            // Windows
        }
        Files.move(part, secrets, StandardCopyOption.REPLACE_EXISTING);
    }

    public synchronized String secret(String key) {
        String v = secret.get(key);
        return v == null ? "" : v;
    }

    public synchronized void setSecret(String key, String value) throws IOException {
        if (value == null) {
            return;
        }
        secret.put(key, value.trim());
        saveSecrets();
    }

    // ---- prices ----

    public synchronized Prices prices() {
        return prices;
    }

    public synchronized void setPrices(Prices p) {
        if (p.singleMonth < 0 || p.channelMonth < 0 || p.floor < 0 || p.stepOff < 0 || p.stepSize < 1 || p.annualMonths < 1 || p.annualMonths > 12
                || p.maxChannels < 10 || p.keyTtlHours < 1 || p.basePort < 1024 || p.basePort > 60000) {
            throw new IllegalArgumentException("check the prices: no negative amounts, annual 1-12 months, ports 1024-60000");
        }
        prices = p;
        dirty = true;
    }

    /** The price of one channel per month for a reseller with this many channels. */
    public synchronized long perChannel(int channels) {
        Prices p = prices;
        int steps = Math.max(0, (channels - 1) / p.stepSize);
        return Math.max(p.floor, p.channelMonth - p.stepOff * steps);
    }

    /** The price for one period (monthly / annual) of a licence of this kind and size. */
    public synchronized long price(String kind, int channels, String period) {
        Prices p = prices;
        long month = SINGLE.equals(kind) ? p.singleMonth : perChannel(channels) * channels;
        return "annual".equals(period) ? month * p.annualMonths : month;
    }

    public static int periodDays(String period, int customDays) {
        return "annual".equals(period) ? 365 : "custom".equals(period) ? Math.max(1, customDays) : 30;
    }

    static int validChannels(String kind, int channels, int max) {
        if (SINGLE.equals(kind)) {
            return 1;
        }
        if (channels < 10 || channels % 10 != 0 || channels > max) {
            throw new IllegalArgumentException("a reseller licence has 10, 20, 30... channels (up to " + max + ")");
        }
        return channels;
    }

    // ---- licences and invoices ----

    public synchronized Licence licence(long id) {
        Licence x = licences.get(id);
        return x == null ? null : x.copy();
    }

    public synchronized List<Licence> licences(String owner) {
        List<Licence> out = new ArrayList<Licence>();
        for (Licence x : licences.values()) {
            if (owner == null || x.owner.equalsIgnoreCase(owner)) {
                out.add(x.copy());
            }
        }
        return out;
    }

    /** A new licence, waiting for its first payment (or a custom one the admin made). */
    public synchronized Licence create(String owner, String kind, int channels, String period, long customCents, int customDays, String note) {
        if (!SINGLE.equals(kind) && !RESELLER.equals(kind)) {
            throw new IllegalArgumentException("single or reseller?");
        }
        if (!java.util.Arrays.asList(PERIODS).contains(period)) {
            throw new IllegalArgumentException("monthly, annual or custom?");
        }
        int open = 0;
        int want = validChannels(kind, channels, prices.maxChannels);
        for (Licence x : licences.values()) {
            if (x.owner.equalsIgnoreCase(owner) && "pending".equals(x.status)) {
                if (x.kind.equals(kind) && x.channels == want && x.period.equals(period) && !"custom".equals(period)) {
                    return x.copy(); // the same unpaid choice again (a second try at paying)
                }
                open++;
            }
        }
        if (open >= 5) {
            throw new IllegalArgumentException("you have 5 licences waiting for payment - pay or cancel one first");
        }
        Licence x = new Licence();
        x.id = nextLicence++;
        x.owner = owner;
        x.kind = kind;
        x.channels = validChannels(kind, channels, prices.maxChannels);
        x.period = period;
        x.customDays = "custom".equals(period) ? Math.max(1, customDays) : 0;
        x.priceCents = "custom".equals(period) ? Math.max(0, customCents) : price(kind, x.channels, period);
        x.created = clock.getAsLong();
        x.basePort = prices.basePort;
        x.note = c(note, 200);
        licences.put(x.id, x);
        event(x.id, owner, "created", x.label() + ", " + x.period, "");
        dirty = true;
        return x.copy();
    }

    public synchronized Invoice invoice(long id) {
        return invoices.get(id);
    }

    public synchronized List<Invoice> invoices(String owner) {
        List<Invoice> out = new ArrayList<Invoice>();
        for (Invoice i : invoices.values()) {
            if (owner == null || i.owner.equalsIgnoreCase(owner)) {
                out.add(i);
            }
        }
        return out;
    }

    /**
     * What there is to pay now for a licence: "new" (first period), "renew" (one more period from the end of the paid
     * time) or "upgrade" (more channels: the difference for the days left, at least one day).
     */
    public synchronized Invoice bill(long licenceId, String what, int newChannels, String method) {
        Licence x = licences.get(licenceId);
        if (x == null) {
            throw new IllegalArgumentException("no such licence");
        }
        if ("cancelled".equals(x.status) || "suspended".equals(x.status)) {
            throw new IllegalArgumentException("this licence is " + x.status + " - contact Memoryq");
        }
        long now = clock.getAsLong();
        Invoice i = new Invoice();
        i.licence = x.id;
        i.owner = x.owner;
        i.currency = prices.currency;
        i.method = method;
        i.created = now;
        i.channels = x.channels;
        if ("upgrade".equals(what)) {
            if (!RESELLER.equals(x.kind) || !"active".equals(x.state(now))) {
                throw new IllegalArgumentException("only an active reseller licence can get more channels");
            }
            int n = validChannels(RESELLER, newChannels, prices.maxChannels);
            if (n <= x.channels) {
                throw new IllegalArgumentException("choose more channels than now (" + x.channels + ")");
            }
            long daysLeft = Math.max(1, (x.paidUntil - now + DAY - 1) / DAY);
            long monthNow = perChannel(x.channels) * x.channels, monthNew = perChannel(n) * n;
            i.cents = Math.max(0, (monthNew - monthNow) * daysLeft / 30);
            i.channels = n;
            i.days = 0;
            i.what = "upgrade";
        } else {
            i.what = "pending".equals(x.status) ? "new" : "renew";
            i.cents = x.priceCents;
            i.days = periodDays(x.period, x.customDays);
        }
        if (i.cents <= 0 && !"manual".equals(method)) {
            throw new IllegalArgumentException("nothing to pay");
        }
        // an open invoice for the same thing is replaced
        for (Invoice o : invoices.values()) {
            if (o.licence == x.id && "open".equals(o.status) && o.what.equals(i.what)) {
                o.status = "cancelled";
            }
        }
        i.id = nextInvoice++;
        invoices.put(i.id, i);
        dirty = true;
        return i;
    }

    public synchronized void setRef(long invoiceId, String ref) {
        Invoice i = invoices.get(invoiceId);
        if (i != null) {
            i.ref = c(ref, 120);
            dirty = true;
        }
    }

    /** The invoice is paid: the licence gets its time (or its channels). Returns the licence; null if it was paid before. */
    public synchronized Licence markPaid(long invoiceId, String method, String ref, String by) {
        Invoice i = invoices.get(invoiceId);
        if (i == null || "paid".equals(i.status)) {
            return null;
        }
        Licence x = licences.get(i.licence);
        if (x == null) {
            return null;
        }
        long now = clock.getAsLong();
        i.status = "paid";
        i.paid = now;
        i.method = method;
        if (ref != null && !ref.isEmpty()) {
            i.ref = c(ref, 120);
        }
        String kind;
        if ("upgrade".equals(i.what)) {
            x.channels = i.channels;
            x.priceCents = "custom".equals(x.period) ? x.priceCents : price(x.kind, x.channels, x.period);
            kind = "upgraded";
        } else {
            long from = Math.max(now, x.paidUntil);
            x.paidUntil = from + i.days * DAY;
            kind = "pending".equals(x.status) ? "paid" : "renewed";
            if ("pending".equals(x.status) || "active".equals(x.status)) {
                x.status = "active";
            }
            x.reminded = "";
        }
        event(x.id, by, kind, i.what + " " + money(i.cents, i.currency) + " by " + method + (ref == null || ref.isEmpty() ? "" : " (" + c(ref, 40) + ")"), "");
        dirty = true;
        return x.copy();
    }

    public synchronized void failed(long invoiceId, String why) {
        Invoice i = invoices.get(invoiceId);
        if (i != null && "open".equals(i.status)) {
            i.status = "failed";
            i.note = c(why, 200);
            event(i.licence, i.owner, "payment-failed", i.method + ": " + c(why, 120), "");
            dirty = true;
        }
    }

    public static String money(long cents, String currency) {
        String cur = currency == null ? "usd" : currency.toLowerCase(Locale.ROOT);
        String sym = "usd".equals(cur) ? "$" : "eur".equals(cur) ? "\u20ac" : "gbp".equals(cur) ? "\u00a3" : cur.toUpperCase(Locale.ROOT) + " ";
        return sym + String.format(Locale.ENGLISH, "%,d.%02d", cents / 100, Math.abs(cents % 100));
    }

    /** The admin's changes: status, paid until, channels, price, period, port, note, owner. */
    public synchronized Licence adminEdit(long id, Map<String, String> v, String by) {
        Licence x = licences.get(id);
        if (x == null) {
            throw new IllegalArgumentException("no such licence");
        }
        StringBuilder what = new StringBuilder();
        if (v.containsKey("status")) {
            String s = v.get("status");
            if (!s.matches("pending|active|suspended|cancelled")) {
                throw new IllegalArgumentException("status: pending, active, suspended or cancelled");
            }
            if (!s.equals(x.status)) {
                what.append("status ").append(x.status).append(" -> ").append(s).append("; ");
                x.status = s;
                if ("suspended".equals(s)) {
                    event(x.id, by, "suspended", "by the admin", "");
                }
            }
        }
        if (v.containsKey("paidUntil") && !v.get("paidUntil").isEmpty()) {
            Long t = DownloadApi.releasedParam(v.get("paidUntil"));
            if (t == null) {
                throw new IllegalArgumentException("paid until: a date like 2026-12-31");
            }
            x.paidUntil = t + 12 * 3600000L - 1; // the end of that day (UTC)
            what.append("paid until ").append(v.get("paidUntil")).append("; ");
        }
        if (v.containsKey("channels") && !v.get("channels").isEmpty()) {
            x.channels = validChannels(x.kind, Integer.parseInt(v.get("channels").trim()), prices.maxChannels);
            what.append(x.channels).append(" channels; ");
        }
        if (v.containsKey("period") && !v.get("period").isEmpty()) {
            if (!java.util.Arrays.asList(PERIODS).contains(v.get("period"))) {
                throw new IllegalArgumentException("monthly, annual or custom?");
            }
            x.period = v.get("period");
            what.append(x.period).append("; ");
        }
        if (v.containsKey("customDays") && !v.get("customDays").isEmpty()) {
            x.customDays = Math.max(1, Integer.parseInt(v.get("customDays").trim()));
        }
        if (v.containsKey("price") && !v.get("price").isEmpty()) {
            x.priceCents = Math.round(Double.parseDouble(v.get("price").trim().replace(",", "")) * 100);
            what.append("price ").append(money(x.priceCents, prices.currency)).append("; ");
        } else if (!"custom".equals(x.period)) {
            x.priceCents = price(x.kind, x.channels, x.period);
        }
        if (v.containsKey("basePort") && !v.get("basePort").isEmpty()) {
            int p = Integer.parseInt(v.get("basePort").trim());
            if (p < 1024 || p + x.channels > 65535) {
                throw new IllegalArgumentException("ports 1024-65535");
            }
            x.basePort = p;
        }
        if (v.containsKey("note")) {
            x.note = c(v.get("note"), 200);
        }
        if ("1".equals(v.get("unbind"))) {
            x.serverHash = "";
            x.ip = "";
            what.append("server freed; ");
        }
        event(x.id, by, "admin", what.length() == 0 ? "saved" : what.toString(), "");
        dirty = true;
        return x.copy();
    }

    // ---- install keys and servers ----

    static String sha(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String rnd(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private byte[] aes() {
        return Base64.getDecoder().decode(secret("aes"));
    }

    /** "MQ1." + AES-256-GCM(licence|key id|expiry|nonce): opaque to the customer, readable only by memoryq.com. */
    String seal(String plain) {
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aes(), "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(ct, 0, all, iv.length, ct.length);
            return "MQ1." + Base64.getUrlEncoder().withoutPadding().encodeToString(all);
        } catch (Exception e) {
            throw new IllegalStateException("could not make the key", e);
        }
    }

    String open(String token) {
        if (token == null || !token.startsWith("MQ1.") || token.length() > 400) {
            return null;
        }
        try {
            byte[] all = Base64.getUrlDecoder().decode(token.substring(4));
            if (all.length < 29) {
                return null;
            }
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aes(), "AES"), new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A new install key for the owner's licence (the first one, or after a server / IP change). The old key and the
     * old server's access stop working. Returns the key (shown once).
     */
    public synchronized String newKey(long id, String owner, String ip) {
        Licence x = licences.get(id);
        if (x == null || !x.owner.equalsIgnoreCase(owner)) {
            throw new IllegalArgumentException("no such licence");
        }
        String st = x.state(clock.getAsLong());
        if (!"active".equals(st) && !"overdue".equals(st)) {
            throw new IllegalArgumentException("the licence is " + st + " - " + ("pending".equals(st) ? "pay for it first" : "renew it first"));
        }
        long now = clock.getAsLong();
        boolean moving = !x.serverHash.isEmpty();
        x.keyId = rnd(9);
        x.keyExpires = now + prices.keyTtlHours * 3600000L;
        x.keyUsed = false;
        String token = seal(x.id + "|" + x.keyId + "|" + x.keyExpires + "|" + rnd(6));
        x.keyHash = sha(token);
        x.serverHash = "";
        event(x.id, owner, "key", moving ? "new install key - the old server (" + x.ip + ") is cut off" : "install key made", ip);
        x.ip = "";
        dirty = true;
        return token;
    }

    /** The result of an activation or a check-in. */
    public static final class Answer {
        public int status = 200;
        public String error = "";
        public String serverSecret = "";
        public Licence licence;
    }

    /** The installer asks first (nothing is used up): is this key good, and which licence is it? */
    public synchronized Answer peek(String token) {
        Answer a = new Answer();
        long now = clock.getAsLong();
        String plain = open(token);
        Licence x = null;
        if (plain != null) {
            String[] p = plain.split("\\|");
            x = p.length >= 3 ? licences.get(l(p[0])) : null;
            if (x != null && (!p[1].equals(x.keyId) || !sha(token).equals(x.keyHash))) {
                x = null;
            }
        }
        if (x == null) {
            a.status = 403;
            a.error = "this install key is not valid (or a newer one was made) - copy the newest install command from memoryq.com/licence";
        } else if (now > x.keyExpires) {
            a.status = 403;
            a.error = "this install key has expired - make a new one at memoryq.com/licence (New install command)";
        } else if (x.keyUsed) {
            a.status = 403;
            a.error = "this install key was already used - make a new install command at memoryq.com/licence";
        } else if (!"active".equals(x.state(now)) && !"overdue".equals(x.state(now))) {
            a.status = 402;
            a.error = "the licence is " + x.state(now) + " - renew it at memoryq.com/licence";
        } else {
            a.licence = x.copy();
        }
        return a;
    }

    /** The installer on the customer's server activates the licence with its install key (once). */
    public synchronized Answer activate(String token, String ip, String host, String os, String version) {
        Answer a = new Answer();
        long now = clock.getAsLong();
        String plain = open(token);
        Licence x = null;
        if (plain != null) {
            String[] p = plain.split("\\|");
            x = p.length >= 3 ? licences.get(l(p[0])) : null;
            if (x != null && (!p[1].equals(x.keyId) || !sha(token).equals(x.keyHash))) {
                event(x.id, x.owner, "bad-key", "an old install key was used (a newer one exists)", ip);
                a.status = 403;
                a.error = "this install key was replaced by a newer one - copy the newest install command from memoryq.com/licence";
                dirty = true;
                return a;
            }
        }
        if (x == null) {
            a.status = 403;
            a.error = "this is not a Memoryq install key - copy the install command from memoryq.com/licence";
            return a;
        }
        if (now > x.keyExpires) {
            event(x.id, x.owner, "key-expired", "the install key had expired", ip);
            a.status = 403;
            a.error = "this install key expired " + new java.text.SimpleDateFormat("d MMM yyyy HH:mm 'UTC'", Locale.ENGLISH).format(new java.util.Date(x.keyExpires))
                    + " - make a new one at memoryq.com/licence (New install command)";
            dirty = true;
            return a;
        }
        if (x.keyUsed) {
            event(x.id, x.owner, "bad-key", "the install key was used a second time", ip);
            a.status = 403;
            a.error = "this install key was already used on " + (x.ip.isEmpty() ? "another server" : x.ip)
                    + " - to install on another server, make a new install command at memoryq.com/licence";
            dirty = true;
            return a;
        }
        String st = x.state(now);
        if (!"active".equals(st) && !"overdue".equals(st)) {
            a.status = 402;
            a.error = "the licence is " + st + " - renew it at memoryq.com/licence";
            return a;
        }
        String secretKey = "MQS." + rnd(32);
        x.keyUsed = true;
        x.serverHash = sha(secretKey);
        x.ip = c(ip, 60);
        x.host = c(host, 100);
        x.os = c(os, 100);
        x.version = c(version, 30);
        x.activated = now;
        x.lastSeen = now;
        event(x.id, x.owner, "activated", (host == null ? "" : c(host, 60) + " ") + "(" + c(os, 60) + ")", ip);
        dirty = true;
        a.serverSecret = secretKey;
        a.licence = x.copy();
        return a;
    }

    /** A check-in from an activated server (every 5 minutes): only from the IP it was activated on. */
    public synchronized Answer checkin(String serverSecret, String ip, String version, String stats) {
        Answer a = new Answer();
        long now = clock.getAsLong();
        Licence x = null;
        if (serverSecret != null && serverSecret.startsWith("MQS.")) {
            String h = sha(serverSecret);
            for (Licence y : licences.values()) {
                if (!y.serverHash.isEmpty() && y.serverHash.equals(h)) {
                    x = y;
                }
            }
        }
        if (x == null) {
            a.status = 401;
            a.error = "this server's licence was moved to another server or ended - see memoryq.com/licence";
            return a;
        }
        if (!x.ip.equals(ip)) {
            if (!x.reminded.contains("ip:" + ip)) {
                event(x.id, x.owner, "ip-mismatch", "a check-in came from " + ip + " (licensed: " + x.ip + ")", ip);
                x.reminded = c(x.reminded + " ip:" + ip, 60);
            }
            a.status = 409;
            a.error = "this licence is tied to the server " + x.ip + " - if you moved, make a new install command at memoryq.com/licence";
            dirty = true;
            return a;
        }
        boolean firstToday = x.lastSeen / DAY != now / DAY;
        x.lastSeen = now;
        if (version != null && !version.isEmpty()) {
            x.version = c(version, 30);
        }
        if (stats != null) {
            x.stats = c(stats, 300);
        }
        x.reminded = x.reminded.replace("offline", "").trim();
        if (firstToday) {
            event(x.id, x.owner, "checkin", "online" + (x.version.isEmpty() ? "" : ", version " + x.version), ip);
        }
        dirty = true;
        a.licence = x.copy();
        String st = x.state(now);
        if ("suspended".equals(st) || "cancelled".equals(st) || "expired".equals(st)) {
            a.status = 402;
            a.error = "the licence is " + st + " - see memoryq.com/licence";
        }
        return a;
    }

    // ---- activity and score ----

    public synchronized void event(long licence, String user, String kind, String detail, String ip) {
        Event e = new Event();
        e.at = clock.getAsLong();
        e.licence = licence;
        e.user = user == null ? "" : user;
        e.kind = kind;
        e.detail = detail == null ? "" : detail;
        e.ip = ip == null ? "" : ip;
        Integer p = POINTS.get(kind);
        e.points = p == null ? 0 : p;
        events.add(e);
        if (events.size() > 20000) {
            events.subList(0, events.size() - 20000).clear();
        }
        dirty = true;
    }

    public synchronized List<Event> events(long licence, String owner, int max) {
        List<Event> out = new ArrayList<Event>();
        for (int k = events.size() - 1; k >= 0 && out.size() < max; k--) {
            Event e = events.get(k);
            if ((licence <= 0 || e.licence == licence) && (owner == null || e.user.equalsIgnoreCase(owner) || ownerOf(e.licence).equalsIgnoreCase(owner))) {
                out.add(e);
            }
        }
        return out;
    }

    private String ownerOf(long licence) {
        Licence x = licences.get(licence);
        return x == null ? "" : x.owner;
    }

    /** 0-100: 50 + the points of the last 30 days (paying, check-ins and activations up; problems down). */
    public synchronized int score(long licence) {
        long since = clock.getAsLong() - 30 * DAY;
        int s = 50;
        for (Event e : events) {
            if (e.licence == licence && e.at >= since) {
                s += e.points;
            }
        }
        return Math.max(0, Math.min(100, s));
    }

    /** Every hour: renewal reminders (7, 3 and 1 day before), overdue / expired, servers silent for an hour. Returns the messages to send. */
    public synchronized List<String[]> tick() {
        long now = clock.getAsLong();
        List<String[]> mail = new ArrayList<String[]>();
        for (Licence x : licences.values()) {
            if (!"active".equals(x.status)) {
                continue;
            }
            long left = x.paidUntil - now;
            for (int d : new int[] {1, 3, 7}) {
                String tag = "r" + d;
                if (left > 0 && left <= d * DAY && !x.reminded.contains(tag)) {
                    x.reminded = c(x.reminded + (d == 7 ? " r7" : d == 3 ? " r3 r7" : " r1 r3 r7"), 60);
                    mail.add(new String[] {x.owner, "Your Memoryq licence #" + x.id + " ends in " + d + " day" + (d == 1 ? "" : "s"),
                        "Your " + x.label() + " licence is paid until " + date(x.paidUntil) + ".\n\nRenew it at https://memoryq.com/licence so your "
                            + "server keeps running. You can pay by card, PayPal or M-Pesa.\n"});
                    dirty = true;
                    break;
                }
            }
            String st = x.state(now);
            if ("overdue".equals(st) && !x.reminded.contains("overdue")) {
                x.reminded = c(x.reminded + " overdue", 60);
                event(x.id, x.owner, "overdue", "the paid time ended " + date(x.paidUntil), "");
                mail.add(new String[] {x.owner, "Your Memoryq licence #" + x.id + " needs renewing",
                    "The paid time of your " + x.label() + " licence ended on " + date(x.paidUntil) + ". Your server keeps running for 7 more days.\n\n"
                        + "Renew at https://memoryq.com/licence\n"});
            } else if ("expired".equals(st) && !x.reminded.contains("expired")) {
                x.reminded = c(x.reminded + " expired", 60);
                event(x.id, x.owner, "expired", "not renewed 7 days after " + date(x.paidUntil), "");
                mail.add(new String[] {x.owner, "Your Memoryq licence #" + x.id + " has expired",
                    "Your " + x.label() + " licence was not renewed. Renew at https://memoryq.com/licence to start it again.\n"});
            }
            if (!x.serverHash.isEmpty() && x.lastSeen > 0 && now - x.lastSeen > 3600000L && !x.reminded.contains("offline")) {
                x.reminded = c(x.reminded + " offline", 60);
                event(x.id, x.owner, "offline", "no check-in since " + new java.util.Date(x.lastSeen), "");
            }
        }
        flushIfDirty();
        return mail;
    }

    static String date(long t) {
        return new java.text.SimpleDateFormat("d MMMM yyyy", Locale.ENGLISH).format(new java.util.Date(t));
    }
}
package com.vstream.Vidiserver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Paying for radio stations: the prices the admin sets (per month and per year), the free trial for new stations,
 * and Stripe (Checkout for paying, the customer portal for changing the card or cancelling, and the subscription to
 * see renewals). The Stripe secret key lives in billing.txt (readable only by the server).
 */
public final class RadioBilling {

    /** Days a paid station keeps playing after its paid time runs out (a renewal may be a little late). */
    public static final long GRACE_MS = 3L * 24 * 3600 * 1000;

    private final Path file;
    private String currency = "usd";
    private long monthCents = 0;
    private long yearCents = 0;
    private int trialDays = 0;
    private String stripeKey = "";
    /** Where Stripe is (a test can point this at a pretend Stripe with -Dvstream.stripeBase). */
    private String baseUrl = System.getProperty("vstream.stripeBase", "https://api.stripe.com");

    /**
     * A plan a station can be on: its prices and what it may use. 0 means "no limit" for listeners, storage and
     * data; kbps is the stream quality; djs is how many remote DJs it may have (at most 3).
     */
    public static final class Plan {
        public int id;
        public String name = "";
        public long monthCents;
        public long yearCents;
        public int listeners;
        public int kbps = 128;
        public int storageMb;
        public int transferGb;
        public int djs = 3;
        public boolean djPro = true;
        /** Offered when people sign up online (the admin can still give any plan by hand). */
        public boolean signup = true;

        Plan copy() {
            Plan p = new Plan();
            p.id = id;
            p.name = name;
            p.monthCents = monthCents;
            p.yearCents = yearCents;
            p.listeners = listeners;
            p.kbps = kbps;
            p.storageMb = storageMb;
            p.transferGb = transferGb;
            p.djs = djs;
            p.djPro = djPro;
            p.signup = signup;
            return p;
        }

        /** The plan with the admin's own limits for one station on top (null plan + no own limits = null). */
        public static Plan effective(Plan base, RadioStore.Station s) {
            if (s == null || !s.hasOwnLimits()) {
                return base;
            }
            Plan p;
            if (base != null) {
                p = base.copy();
            } else {
                p = new Plan();
                p.name = "Custom";
                p.kbps = Integer.getInteger("vstream.radioKbps", 128);
                p.djs = MAX_DJS;
            }
            if (s.limListeners != 0) {
                p.listeners = Math.max(0, s.limListeners);
            }
            if (s.limKbps > 0) {
                p.kbps = s.limKbps;
            }
            if (s.limStorageMb != 0) {
                p.storageMb = Math.max(0, s.limStorageMb);
            }
            if (s.limTransferGb != 0) {
                p.transferGb = Math.max(0, s.limTransferGb);
            }
            if (s.limDjs != 0) {
                p.djs = Math.max(0, s.limDjs);
            }
            return p;
        }

        public boolean priced() {
            return monthCents > 0 || yearCents > 0;
        }
    }

    public static final int MAX_DJS = 3;
    public static final int[] KBPS = {64, 96, 128, 192, 256, 320};

    private final java.util.TreeMap<Integer, Plan> plans = new java.util.TreeMap<Integer, Plan>();

    public RadioBilling(Path file) {
        this.file = file;
    }

    public synchronized void init() throws IOException {
        if (file == null || !Files.exists(file)) {
            return;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] f = line.split("\t", -1);
            if ("P".equals(f[0]) && f.length >= 13) {
                try {
                    Plan p = new Plan();
                    p.id = Integer.parseInt(f[1]);
                    p.name = f[2];
                    p.monthCents = Long.parseLong(f[3]);
                    p.yearCents = Long.parseLong(f[4]);
                    p.listeners = Integer.parseInt(f[5]);
                    p.kbps = Integer.parseInt(f[6]);
                    p.storageMb = Integer.parseInt(f[7]);
                    p.transferGb = Integer.parseInt(f[8]);
                    p.djs = Integer.parseInt(f[9]);
                    p.djPro = "1".equals(f[10]);
                    p.signup = "1".equals(f[11]);
                    plans.put(p.id, p);
                } catch (RuntimeException e) {
                    // skip a damaged line
                }
                continue;
            }
            if (f.length < 3 || !"K".equals(f[0])) {
                continue;
            }
            try {
                switch (f[1]) {
                    case "currency": currency = f[2]; break;
                    case "month": monthCents = Long.parseLong(f[2]); break;
                    case "year": yearCents = Long.parseLong(f[2]); break;
                    case "trialDays": trialDays = Integer.parseInt(f[2]); break;
                    case "stripeKey": stripeKey = f[2]; break;
                    default: break;
                }
            } catch (RuntimeException e) {
                // skip a damaged line
            }
        }
        if (plans.isEmpty() && (monthCents > 0 || yearCents > 0)) {
            // from before plans: the one price becomes the "Standard" plan (no limits, 3 DJs)
            Plan p = new Plan();
            p.id = 1;
            p.name = "Standard";
            p.monthCents = monthCents;
            p.yearCents = yearCents;
            plans.put(1, p);
            write();
        }
    }

    // ---- Plans ----

    public synchronized List<Plan> plans() {
        List<Plan> out = new ArrayList<Plan>();
        for (Plan p : plans.values()) {
            out.add(p.copy());
        }
        return out;
    }

    /** The plan, or null (stations on plan 0 or a deleted plan have no limits). */
    public synchronized Plan plan(int id) {
        Plan p = plans.get(id);
        return p == null ? null : p.copy();
    }

    /** Creates (id 0) or changes a plan. Returns it. */
    public synchronized Plan savePlan(Plan in) throws IOException {
        String n = RadioStore.clean(in.name, 40);
        if (n.isEmpty()) {
            throw new IllegalArgumentException("give the plan a name");
        }
        for (Plan o : plans.values()) {
            if (o.id != in.id && o.name.equalsIgnoreCase(n)) {
                throw new IllegalArgumentException("there is already a plan called " + o.name);
            }
        }
        if (in.monthCents < 0 || in.yearCents < 0 || in.listeners < 0 || in.storageMb < 0 || in.transferGb < 0) {
            throw new IllegalArgumentException("numbers cannot be negative");
        }
        boolean okKbps = false;
        for (int k : KBPS) {
            okKbps |= k == in.kbps;
        }
        if (!okKbps) {
            throw new IllegalArgumentException("stream quality must be 64, 96, 128, 192, 256 or 320 kbps");
        }
        if (in.djs < 0 || in.djs > MAX_DJS) {
            throw new IllegalArgumentException("remote DJs: 0 to " + MAX_DJS);
        }
        Plan p = in.id == 0 ? null : plans.get(in.id);
        if (in.id != 0 && p == null) {
            throw new IllegalArgumentException("no such plan");
        }
        if (p == null) {
            p = new Plan();
            p.id = plans.isEmpty() ? 1 : plans.lastKey() + 1;
            plans.put(p.id, p);
        }
        p.name = n;
        p.monthCents = in.monthCents;
        p.yearCents = in.yearCents;
        p.listeners = in.listeners;
        p.kbps = in.kbps;
        p.storageMb = in.storageMb;
        p.transferGb = in.transferGb;
        p.djs = in.djs;
        p.djPro = in.djPro;
        p.signup = in.signup;
        write();
        return p.copy();
    }

    public synchronized boolean deletePlan(int id) throws IOException {
        if (plans.remove(id) == null) {
            return false;
        }
        write();
        return true;
    }

    /** The plan shown first (the cheapest one offered at sign-up), or null. */
    public synchronized Plan defaultPlan() {
        Plan best = null;
        for (Plan p : plans.values()) {
            if (p.signup && p.priced() && (best == null || price(p) < price(best))) {
                best = p;
            }
        }
        return best == null ? null : best.copy();
    }

    private static long price(Plan p) {
        return p.monthCents > 0 ? p.monthCents : p.yearCents / 12;
    }

    // ---- Settings ----

    public synchronized String currency() {
        return currency;
    }

    /** The monthly price of the first plan (from before plans; kept for older callers). */
    public synchronized long monthCents() {
        Plan p = plans.get(1);
        return p == null ? monthCents : p.monthCents;
    }

    public synchronized long yearCents() {
        Plan p = plans.get(1);
        return p == null ? yearCents : p.yearCents;
    }

    public synchronized int trialDays() {
        return trialDays;
    }

    /** Stripe is ready when there is a secret key and at least one price. */
    public synchronized boolean stripeReady() {
        if (stripeKey.isEmpty()) {
            return false;
        }
        for (Plan p : plans.values()) {
            if (p.priced()) {
                return true;
            }
        }
        return false;
    }

    public synchronized boolean hasKey() {
        return !stripeKey.isEmpty();
    }

    /** "sk_live_...abcd" style, for showing the admin which key is in use. */
    public synchronized String keyHint() {
        if (stripeKey.isEmpty()) {
            return "";
        }
        int us = stripeKey.indexOf('_', 3);
        String head = us > 0 ? stripeKey.substring(0, us + 1) : "";
        return head + "..." + stripeKey.substring(Math.max(0, stripeKey.length() - 4));
    }

    public synchronized boolean testMode() {
        return stripeKey.startsWith("sk_test_") || stripeKey.startsWith("rk_test_");
    }

    /**
     * Saves the admin's settings. Prices are in normal money ("19.99"); key: null or "" keeps the old one,
     * "-" removes it.
     */
    public synchronized void save(String cur, String month, String year, String trial, String key) throws IOException {
        String c = cur == null ? currency : cur.trim().toLowerCase(Locale.ROOT);
        if (!c.matches("[a-z]{3}")) {
            throw new IllegalArgumentException("the currency is a 3-letter code such as usd, eur, gbp or kes");
        }
        long m = month == null ? monthCents() : cents(month);
        long y = year == null ? yearCents() : cents(year);
        int t = trialDays;
        if (trial != null) {
            try {
                t = trial.trim().isEmpty() ? 0 : Integer.parseInt(trial.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("trial days: give a number");
            }
            if (t < 0 || t > 90) {
                throw new IllegalArgumentException("trial days must be 0 to 90");
            }
        }
        String k = stripeKey;
        if (key != null && !key.trim().isEmpty()) {
            k = "-".equals(key.trim()) ? "" : key.trim();
            if (!k.isEmpty() && !k.matches("(sk|rk)_(test|live)_[A-Za-z0-9]{10,200}")) {
                throw new IllegalArgumentException("that does not look like a Stripe secret key (sk_live_... or sk_test_...)");
            }
        }
        currency = c;
        trialDays = t;
        stripeKey = k;
        if (month != null || year != null) {
            Plan p = plans.get(1);
            if (p == null) {
                p = new Plan();
                p.id = 1;
                p.name = "Standard";
                plans.put(1, p);
            }
            p.monthCents = m;
            p.yearCents = y;
        }
        monthCents = 0;
        yearCents = 0;
        write();
    }

    /** "19.99" -> 1999; "" -> 0. */
    static long cents(String money) {
        String m = money.trim().replace(",", "");
        if (m.isEmpty()) {
            return 0;
        }
        if (!m.matches("\\d{1,7}(\\.\\d{1,2})?")) {
            throw new IllegalArgumentException("prices look like 19.99");
        }
        return Math.round(Double.parseDouble(m) * 100);
    }

    /** 1999 -> "19.99". */
    static String money(long cents) {
        return String.format(Locale.ROOT, "%d.%02d", cents / 100, cents % 100);
    }

    /** For tests: talk to a pretend Stripe. */
    synchronized void setBaseUrl(String url) {
        this.baseUrl = url;
    }

    private void write() throws IOException {
        if (file == null) {
            return;
        }
        StringBuilder sb = new StringBuilder("# Memoryq radio billing - keep private (holds the Stripe secret key)\n");
        sb.append("K\tcurrency\t").append(currency).append('\n');
        sb.append("K\ttrialDays\t").append(trialDays).append('\n');
        sb.append("K\tstripeKey\t").append(stripeKey).append('\n');
        for (Plan p : plans.values()) {
            sb.append("P\t").append(p.id).append('\t').append(p.name).append('\t').append(p.monthCents).append('\t')
                    .append(p.yearCents).append('\t').append(p.listeners).append('\t').append(p.kbps).append('\t')
                    .append(p.storageMb).append('\t').append(p.transferGb).append('\t').append(p.djs).append('\t')
                    .append(p.djPro ? "1" : "0").append('\t').append(p.signup ? "1" : "0").append("\tend\n");
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, sb.toString().getBytes(StandardCharsets.UTF_8));
        try {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            // not a POSIX file system (Windows)
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ---- Stripe ----

    public static final class StripeException extends Exception {
        private static final long serialVersionUID = 1L;

        /** false when Stripe could not be reached at all (network), true when Stripe answered with a refusal. */
        public final boolean answered;

        StripeException(String message) {
            this(message, true);
        }

        StripeException(String message, boolean answered) {
            super(message);
            this.answered = answered;
        }
    }

    /** A Stripe Checkout page for a monthly or yearly subscription to one station. Returns {id, url}. */
    public Map<String, Object> checkout(long stationId, String stationName, String email, String customer, boolean yearly,
                                        String successUrl, String cancelUrl) throws StripeException {
        Plan p = plan(1);
        if (p == null) {
            p = defaultPlan();
        }
        if (p == null) {
            throw new StripeException("there is no plan to pay for");
        }
        return checkout(stationId, stationName, email, customer, p, yearly, successUrl, cancelUrl);
    }

    /** A Stripe Checkout page for this plan, monthly or yearly. Returns {id, url}. */
    public Map<String, Object> checkout(long stationId, String stationName, String email, String customer, Plan plan,
                                        boolean yearly, String successUrl, String cancelUrl) throws StripeException {
        long amount = yearly ? plan.yearCents : plan.monthCents;
        String cur;
        synchronized (this) {
            cur = currency;
        }
        if (amount <= 0) {
            throw new StripeException(plan.name + " has no " + (yearly ? "yearly" : "monthly") + " price");
        }
        Map<String, String> p = new LinkedHashMap<String, String>();
        p.put("mode", "subscription");
        p.put("success_url", successUrl);
        p.put("cancel_url", cancelUrl);
        p.put("client_reference_id", "station-" + stationId);
        p.put("metadata[station]", String.valueOf(stationId));
        p.put("metadata[plan]", String.valueOf(plan.id));
        p.put("subscription_data[metadata][station]", String.valueOf(stationId));
        p.put("subscription_data[metadata][plan]", String.valueOf(plan.id));
        p.put("line_items[0][quantity]", "1");
        p.put("line_items[0][price_data][currency]", cur);
        p.put("line_items[0][price_data][unit_amount]", String.valueOf(amount));
        p.put("line_items[0][price_data][recurring][interval]", yearly ? "year" : "month");
        p.put("line_items[0][price_data][product_data][name]", "Memoryq radio " + plan.name + ": " + stationName);
        if (customer != null && !customer.isEmpty()) {
            p.put("customer", customer);
        } else if (email != null && !email.isEmpty()) {
            p.put("customer_email", email);
        }
        return call("POST", "/v1/checkout/sessions", p);
    }

    /** A finished (or not) Checkout, with its subscription. */
    public Map<String, Object> checkoutSession(String id) throws StripeException {
        if (id == null || !id.matches("cs_[A-Za-z0-9_]{3,250}")) {
            throw new StripeException("that payment reference is not right");
        }
        return call("GET", "/v1/checkout/sessions/" + id + "?expand%5B%5D=subscription", null);
    }

    public Map<String, Object> subscription(String id) throws StripeException {
        if (id == null || !id.matches("sub_[A-Za-z0-9_]{3,250}")) {
            throw new StripeException("bad subscription reference");
        }
        return call("GET", "/v1/subscriptions/" + id, null);
    }

    /** A Stripe page where the owner changes the card, sees invoices or cancels. Returns its address. */
    public String portal(String customer, String returnUrl) throws StripeException {
        Map<String, String> p = new LinkedHashMap<String, String>();
        p.put("customer", customer);
        p.put("return_url", returnUrl);
        return MiniJson.str(call("POST", "/v1/billing_portal/sessions", p), "url");
    }

    /** When the subscription's paid time ends (ms), from either API layout; 0 if unknown. */
    static long periodEnd(Object subscription) {
        long end = MiniJson.num(subscription, "current_period_end");
        if (end <= 0) {
            end = MiniJson.num(subscription, "items", "data", 0, "current_period_end");
        }
        return end * 1000L;
    }

    /** Stripe says this subscription is paid up (or in a Stripe trial). */
    static boolean subscriptionGood(Object subscription) {
        String st = MiniJson.str(subscription, "status");
        return "active".equals(st) || "trialing".equals(st);
    }

    private Map<String, Object> call(String method, String path, Map<String, String> form) throws StripeException {
        String key;
        String base;
        synchronized (this) {
            key = stripeKey;
            base = baseUrl;
        }
        if (key.isEmpty()) {
            throw new StripeException("online payment is not set up yet");
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + path).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Authorization", "Bearer " + key);
            c.setRequestProperty("Stripe-Version", "2024-06-20");
            if (form != null) {
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, String> e : form.entrySet()) {
                    if (sb.length() > 0) {
                        sb.append('&');
                    }
                    sb.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=')
                            .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), "UTF-8"));
                }
                byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                OutputStream os = c.getOutputStream();
                os.write(body);
                os.close();
            }
            int code = c.getResponseCode();
            String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            Map<String, Object> json;
            try {
                json = MiniJson.object(text);
            } catch (IllegalArgumentException e) {
                throw new StripeException("Stripe gave an answer that could not be read (" + code + ")");
            }
            if (code >= 400) {
                String msg = MiniJson.str(json, "error", "message");
                System.out.println("[billing] Stripe " + method + " " + path.replaceAll("\\?.*", "") + ": " + code + " " + msg);
                throw new StripeException(msg.isEmpty() ? "Stripe said no (" + code + ")" : msg);
            }
            return json;
        } catch (IOException e) {
            throw new StripeException("could not reach Stripe: " + e.getMessage(), false);
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) != -1) {
            out.write(b, 0, n);
            if (out.size() > 2_000_000) {
                break;
            }
        }
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
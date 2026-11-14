package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Memoryq Radio Server licences: prices (Single, Reseller steps), buying and paying (Stripe, PayPal, M-Pesa, bank),
 * the install key (encrypted, one use, expiry), activation tied to the server's IP, check-ins, a new key after a move,
 * upgrades, reminders, the score, the admin view - and the installer itself (a test run, where bash is available).
 */
public final class LicenceTest {

    private static int failures = 0;
    static volatile boolean mpesaPaid = false;
    static volatile long stripeAmount = 0;
    static volatile String stripeInvoice = "";
    static volatile String paypalValue = "";

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace();
            System.out.println("FAILED: " + t);
            System.exit(1);
        }
    }

    /** Pretend Stripe, PayPal and Safaricom. */
    static int fakeProviders() throws Exception {
        HttpServer f = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        f.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String body = new String(readAll(ex.getRequestBody()), StandardCharsets.UTF_8);
            String out;
            if (path.equals("/v1/checkout/sessions")) {
                Map<String, String> m = new java.util.HashMap<String, String>();
                for (String kv : body.split("&")) {
                    String[] p = kv.split("=", 2);
                    m.put(java.net.URLDecoder.decode(p[0], "UTF-8"), p.length > 1 ? java.net.URLDecoder.decode(p[1], "UTF-8") : "");
                }
                stripeAmount = Long.parseLong(m.get("line_items[0][price_data][unit_amount]"));
                stripeInvoice = m.get("metadata[invoice]");
                out = "{\"id\":\"cs_test_abc123\",\"url\":\"https://checkout.stripe.test/pay/cs_test_abc123\"}";
            } else if (path.startsWith("/v1/checkout/sessions/")) {
                out = "{\"id\":\"cs_test_abc123\",\"status\":\"complete\",\"payment_status\":\"paid\",\"metadata\":{\"invoice\":\"" + stripeInvoice
                        + "\"},\"amount_total\":" + stripeAmount + "}";
            } else if (path.equals("/v1/oauth2/token")) {
                out = "{\"access_token\":\"pp-token\"}";
            } else if (path.equals("/v2/checkout/orders")) {
                int v = body.indexOf("\"value\":\"");
                paypalValue = body.substring(v + 9, body.indexOf('"', v + 9));
                out = "{\"id\":\"ORDER12345678\",\"links\":[{\"rel\":\"self\",\"href\":\"x\"},{\"rel\":\"approve\",\"href\":\"https://paypal.test/approve/ORDER12345678\"}]}";
            } else if (path.equals("/v2/checkout/orders/ORDER12345678/capture")) {
                out = "{\"status\":\"COMPLETED\",\"purchase_units\":[{\"payments\":{\"captures\":[{\"status\":\"COMPLETED\",\"amount\":{\"value\":\"" + paypalValue
                        + "\",\"currency_code\":\"USD\"}}]}}]}";
            } else if (path.equals("/oauth/v1/generate")) {
                out = "{\"access_token\":\"mp-token\",\"expires_in\":\"3599\"}";
            } else if (path.equals("/mpesa/stkpush/v1/processrequest")) {
                out = body.contains("\"PartyA\":\"254712345678\"") && body.contains("\"Amount\":")
                        ? "{\"ResponseCode\":\"0\",\"CheckoutRequestID\":\"ws_CO_123\",\"ResponseDescription\":\"Success\"}" : "{\"errorMessage\":\"bad request\"}";
            } else if (path.equals("/mpesa/stkpushquery/v1/query")) {
                out = mpesaPaid ? "{\"ResultCode\":\"0\",\"ResultDesc\":\"The service request is processed successfully.\"}"
                        : "{\"errorCode\":\"500.001.1001\",\"errorMessage\":\"The transaction is being processed\"}";
            } else {
                out = "{}";
            }
            byte[] b = out.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        f.start();
        return f.getAddress().getPort();
    }

    static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while (in != null && (n = in.read(buf)) > 0) {
            b.write(buf, 0, n);
        }
        return b.toByteArray();
    }

    static int port;

    static String[] call(String method, String path, String body, String token, String fwd) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
        c.setRequestProperty("Connection", "close");
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        if (token != null) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (fwd != null) {
            c.setRequestProperty("X-Forwarded-For", fwd);
        }
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        return new String[] {String.valueOf(code), new String(readAll(code >= 400 ? c.getErrorStream() : c.getInputStream()), StandardCharsets.UTF_8)};
    }

    static String e(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    static String jstr(String json, String key) {
        int i = json.indexOf("\"" + key + "\":");
        if (i < 0) {
            return "";
        }
        int s = i + key.length() + 3;
        if (json.charAt(s) == '"') {
            return json.substring(s + 1, json.indexOf('"', s + 1));
        }
        int end = s;
        while (end < json.length() && ",}]".indexOf(json.charAt(end)) < 0) {
            end++;
        }
        return json.substring(s, end);
    }

    private static void run() throws Exception {
        Path dir = Files.createTempDirectory("memoryq-licence");
        long[] now = {1792900000000L};
        int fake = fakeProviders();
        System.setProperty("vstream.paypalBase", "http://127.0.0.1:" + fake);
        System.setProperty("vstream.mpesaBase", "http://127.0.0.1:" + fake);
        RadioBilling billing = new RadioBilling(dir.resolve("billing.txt"));
        billing.init();
        billing.save("usd", null, null, null, "sk_" + "test_1234567890abcdef");
        billing.setBaseUrl("http://127.0.0.1:" + fake);
        LicenseStore store = new LicenseStore(dir.resolve("licences.txt"), dir.resolve("licence-secrets.txt"), () -> now[0]);
        store.init();
        SessionStore sessions = new SessionStore();
        String admin = sessions.create("admin", UserStore.Role.ADMIN);
        String juma = sessions.create("juma", UserStore.Role.CHATTER);
        String other = sessions.create("eve", UserStore.Role.CHATTER);
        LicenseApi api = new LicenseApi(store, new LicensePay(store, billing), sessions, () -> now[0]);
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        api.register(http);
        http.start();
        port = http.getAddress().getPort();
        api.publicBase("http://127.0.0.1:" + port);
        try {
            System.out.println("-- Prices --");
            checkTrue("reseller: $5.00 per channel for 10, $0.25 less per step of 10, never under $3.00",
                    store.perChannel(10) == 500 && store.perChannel(20) == 475 && store.perChannel(30) == 450 && store.perChannel(90) == 300 && store.perChannel(200) == 300);
            checkTrue("single $9/month, annual = 10 months ($90); 20 channels $95/month", store.price("single", 1, "monthly") == 900
                    && store.price("single", 1, "annual") == 9000 && store.price("reseller", 20, "monthly") == 9500);
            String[] r = call("GET", "/api/licence/prices", null, null, null);
            checkTrue("the public prices say which payments work", r[1].contains("\"stripe\":true") && r[1].contains("\"paypal\":false") && r[1].contains("\"manual\":true"));
            checkTrue("buying needs a sign-in", call("POST", "/api/licence/new", "kind=single&period=monthly", null, null)[0].equals("401"));
            checkTrue("reseller channels go in tens", call("POST", "/api/licence/new", "kind=reseller&channels=15&period=monthly", juma, null)[0].equals("400"));

            System.out.println("-- Card (Stripe) --");
            r = call("POST", "/api/licence/new", "kind=reseller&channels=20&period=monthly", juma, null);
            long lic = Long.parseLong(jstr(r[1], "id"));
            checkTrue("a reseller licence with 20 channels waits for payment", r[0].equals("200") && r[1].contains("\"state\":\"pending\"") && r[1].contains("\"lastPort\":8019"));
            checkTrue("the same choice again is the same licence (no pile of unpaid ones)",
                    jstr(call("POST", "/api/licence/new", "kind=reseller&channels=20&period=monthly", juma, null)[1], "id").equals(String.valueOf(lic)));
            r = call("POST", "/api/licence/pay", "licence=" + lic + "&method=stripe", juma, null);
            checkTrue("pay by card: off to Stripe for $95.00", r[1].contains("checkout.stripe.test") && stripeAmount == 9500);
            long inv = Long.parseLong(jstr(r[1].substring(r[1].indexOf("\"invoice\":{")), "id"));
            checkTrue("someone else cannot pay or see it", call("POST", "/api/licence/pay", "licence=" + lic + "&method=stripe", other, null)[0].equals("404"));
            r = call("POST", "/api/licence/confirm", "invoice=" + inv + "&session=cs_test_abc123", juma, null);
            LicenseStore.Licence L = store.licence(lic);
            checkTrue("back from Stripe: paid, active for 30 days", r[0].equals("200") && L.state(now[0]).equals("active") && L.paidUntil == now[0] + 30 * LicenseStore.DAY);

            System.out.println("-- The install key --");
            checkTrue("no install command before paying (another licence)", call("POST", "/api/licence/key", "licence=" + jstr(call("POST", "/api/licence/new",
                    "kind=single&period=monthly", juma, null)[1], "id"), juma, null)[0].equals("400"));
            r = call("POST", "/api/licence/key", "licence=" + lic, juma, null);
            String key = jstr(r[1], "key");
            checkTrue("the command to paste: curl ... install.sh | sudo bash -s -- MQ1.<encrypted key>", r[0].equals("200")
                    && jstr(r[1], "command").equals("curl -fsSL http://127.0.0.1:" + port + "/install.sh | sudo bash -s -- " + key) && key.startsWith("MQ1.") && key.length() > 60);
            checkTrue("the key is encrypted (no licence number or key id readable in it)", store.open(key) != null && !key.contains("|"));
            checkTrue("a forged key is refused", call("POST", "/api/licence/check", "key=" + e("MQ1.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"), null, null)[0].equals("403"));
            r = call("POST", "/api/licence/check", "key=" + e(key), null, null);
            checkTrue("the installer asks first: 20 channels, ports 8000-8019 (key not used up)", r[1].contains("\"channels\":20") && r[1].contains("\"lastPort\":8019")
                    && !store.licence(lic).keyUsed);
            r = call("POST", "/api/licence/activate", "key=" + e(key) + "&host=radio1&os=" + e("Ubuntu 24.04") + "&version=1.0.0", null, null);
            String secret = jstr(r[1], "secret");
            checkTrue("activation: tied to this server's IP, a server secret back", r[0].equals("200") && secret.startsWith("MQS.") && store.licence(lic).ip.equals("127.0.0.1"));
            r = call("POST", "/api/licence/activate", "key=" + e(key), null, null);
            checkTrue("the key works once", r[0].equals("403") && r[1].contains("already used"));
            r = call("POST", "/api/licence/checkin", "version=1.0.0&stats=" + e("load 0.1"), secret, null);
            checkTrue("check-in from the licensed server", r[0].equals("200") && r[1].contains("\"state\":\"active\"") && store.licence(lic).stats.equals("load 0.1"));
            r = call("POST", "/api/licence/checkin", "version=1.0.0", secret, "203.0.113.9");
            checkTrue("a check-in from another IP is refused (and logged)", r[0].equals("409") && r[1].contains("memoryq.com/licence")
                    && store.events(lic, null, 50).get(0).kind.equals("ip-mismatch"));
            String raw = new String(Files.readAllBytes(dir.resolve("licences.txt")), StandardCharsets.UTF_8);
            checkTrue("only hashes are kept (no key, no server secret in the file)", !raw.contains(key.substring(4, 30)) && !raw.contains(secret.substring(4, 30)));

            System.out.println("-- Moving to another server --");
            checkTrue("only the owner can make a new key", call("POST", "/api/licence/key", "licence=" + lic, other, null)[0].equals("404"));
            r = call("POST", "/api/licence/key", "licence=" + lic, juma, "198.51.100.7");
            String key2 = jstr(r[1], "key");
            checkTrue("new install command: the old server is cut off", r[0].equals("200") && call("POST", "/api/licence/checkin", "", secret, null)[0].equals("401"));
            r = call("POST", "/api/licence/activate", "key=" + e(key), null, null);
            checkTrue("the old key says a newer one exists", r[0].equals("403") && r[1].contains("replaced"));
            r = call("POST", "/api/licence/activate", "key=" + e(key2) + "&host=radio2", null, "203.0.113.9");
            String secret2 = jstr(r[1], "secret");
            checkTrue("the new server activates from its own IP", r[0].equals("200") && store.licence(lic).ip.equals("203.0.113.9")
                    && call("POST", "/api/licence/checkin", "", secret2, "203.0.113.9")[0].equals("200"));
            String key3 = jstr(call("POST", "/api/licence/key", "licence=" + lic, juma, null)[1], "key");
            now[0] += 73 * 3600000L;
            r = call("POST", "/api/licence/activate", "key=" + e(key3), null, null);
            checkTrue("an install key expires after 72 hours", r[0].equals("403") && r[1].contains("expired"));
            key3 = jstr(call("POST", "/api/licence/key", "licence=" + lic, juma, null)[1], "key");
            secret2 = jstr(call("POST", "/api/licence/activate", "key=" + e(key3), null, "203.0.113.9")[1], "secret");

            System.out.println("-- More channels (PayPal) --");
            store.setSecret("paypal.client", "client-id-123");
            store.setSecret("paypal.secret", "secret-456");
            r = call("POST", "/api/licence/pay", "licence=" + lic + "&method=paypal&what=upgrade&channels=40", juma, null);
            long inv2 = Long.parseLong(jstr(r[1].substring(r[1].indexOf("\"invoice\":{")), "id"));
            long left = (store.licence(lic).paidUntil - now[0] + LicenseStore.DAY - 1) / LicenseStore.DAY;
            long due = (store.price("reseller", 40, "monthly") - 9500) * left / 30;
            checkTrue("upgrade 20 -> 40 channels: only the difference for the days left (" + LicenseStore.money(due, "usd") + ")",
                    r[1].contains("paypal.test/approve") && store.invoice(inv2).cents == due && paypalValue.equals(LicensePay.amount(due)));
            r = call("POST", "/api/licence/confirm", "invoice=" + inv2 + "&token=ORDER12345678", juma, null);
            checkTrue("back from PayPal: captured, 40 channels (ports 8000-8039), new monthly price", r[0].equals("200") && store.licence(lic).channels == 40
                    && store.licence(lic).lastPort() == 8039 && store.licence(lic).priceCents == 17000);

            System.out.println("-- Renewing (M-Pesa) --");
            store.setSecret("mpesa.key", "k");
            store.setSecret("mpesa.secret", "s");
            store.setSecret("mpesa.shortcode", "174379");
            store.setSecret("mpesa.passkey", "pk");
            checkTrue("Kenyan numbers in any form", "254712345678".equals(LicensePay.phone("0712 345 678")) && "254712345678".equals(LicensePay.phone("+254712345678"))
                    && "254112345678".equals(LicensePay.phone("0112345678")) && LicensePay.phone("12345") == null);
            long until = store.licence(lic).paidUntil;
            r = call("POST", "/api/licence/pay", "licence=" + lic + "&method=mpesa&phone=" + e("0712 345 678"), juma, null);
            long inv3 = Long.parseLong(jstr(r[1].substring(r[1].indexOf("\"invoice\":{")), "id"));
            checkTrue("the PIN request goes to the phone", r[0].equals("200") && r[1].contains("\"waiting\":true") && store.invoice(inv3).ref.equals("ws_CO_123"));
            checkTrue("waiting while the customer types the PIN", call("GET", "/api/licence/invoice?id=" + inv3, null, juma, null)[1].contains("\"status\":\"open\""));
            mpesaPaid = true;
            call("POST", "/api/licence/mpesa/callback", "{\"Body\":{\"stkCallback\":{\"CheckoutRequestID\":\"ws_CO_123\",\"ResultCode\":0,\"CallbackMetadata\":{\"Item\":"
                    + "[{\"Name\":\"Amount\",\"Value\":21930},{\"Name\":\"MpesaReceiptNumber\",\"Value\":\"QKX1ABC23\"}]}}}}", null, null);
            checkTrue("Safaricom calls back, checked again, paid: 30 more days", store.invoice(inv3).status.equals("paid") && store.invoice(inv3).ref.equals("QKX1ABC23")
                    && store.licence(lic).paidUntil == until + 30 * LicenseStore.DAY);

            System.out.println("-- Bank transfer, admin --");
            r = call("POST", "/api/licence/new", "kind=single&period=annual", juma, null);
            long lic2 = Long.parseLong(jstr(r[1], "id"));
            r = call("POST", "/api/licence/pay", "licence=" + lic2 + "&method=manual", juma, null);
            long inv4 = Long.parseLong(jstr(r[1].substring(r[1].indexOf("\"invoice\":{")), "id"));
            checkTrue("bank transfer: an invoice with a reference", r[1].contains("MQ-INV-" + inv4) && store.licence(lic2).state(now[0]).equals("pending"));
            checkTrue("members cannot use the admin pages", call("GET", "/api/admin/licences", null, juma, null)[0].equals("403"));
            r = call("POST", "/api/admin/licences/markpaid", "invoice=" + inv4 + "&ref=" + e("NMB 4455"), admin, null);
            checkTrue("the admin marks it paid: a year, port 8000", r[0].equals("200") && store.licence(lic2).state(now[0]).equals("active")
                    && store.licence(lic2).paidUntil == now[0] + 365 * LicenseStore.DAY && store.licence(lic2).lastPort() == 8000);
            r = call("POST", "/api/admin/licences/create", "owner=radiozuri&kind=reseller&channels=100&period=custom&price=350&days=90&paid=1&note=" + e("Deal"), admin, null);
            checkTrue("a custom deal: 100 channels, $350 for 90 days, started at once", r[0].equals("200") && r[1].contains("\"owner\":\"radiozuri\"")
                    && r[1].contains("\"priceText\":\"$350.00\"") && r[1].contains("\"lastPort\":8099"));
            r = call("GET", "/api/admin/licences", null, admin, null);
            checkTrue("the admin sees every licence, server and score", r[1].contains("\"ip\":\"203.0.113.9\"") && r[1].contains("\"score\":") && r[1].contains("\"active\":3"));
            int score = store.score(lic);
            checkTrue("the score counts paying, activating and problems (" + score + ")", score > 50 && score <= 100);
            r = call("POST", "/api/admin/licences/settings", "channelMonth=6&stepOff=0.5&paypal.secret=" + e("new-secret"), admin, null);
            checkTrue("the admin changes prices and keys (keys never shown)", r[0].equals("200") && store.perChannel(20) == 550 && store.secret("paypal.secret").equals("new-secret")
                    && !r[1].contains("new-secret"));
            call("POST", "/api/admin/licences/settings", "channelMonth=5&stepOff=0.25", admin, null);

            System.out.println("-- Reminders, overdue, expired --");
            now[0] = store.licence(lic).paidUntil - 2 * LicenseStore.DAY;
            java.util.List<String[]> mail = store.tick();
            checkTrue("a reminder 3 days before the end", mail.size() >= 1 && mail.get(0)[1].contains("ends in 3 days") && store.tick().isEmpty());
            now[0] = store.licence(lic).paidUntil + LicenseStore.DAY;
            store.tick();
            checkTrue("overdue: still running for 7 days", store.licence(lic).state(now[0]).equals("overdue")
                    && call("POST", "/api/licence/checkin", "", secret2, "203.0.113.9")[0].equals("200"));
            now[0] += 8 * LicenseStore.DAY;
            store.tick();
            r = call("POST", "/api/licence/checkin", "", secret2, "203.0.113.9");
            checkTrue("expired: the server is told", r[0].equals("402") && r[1].contains("expired"));
            store.flushIfDirty();
            LicenseStore again = new LicenseStore(dir.resolve("licences.txt"), dir.resolve("licence-secrets.txt"), () -> now[0]);
            again.init();
            checkTrue("kept across a restart (licences, invoices, activity, the key secret)", again.licence(lic).channels == 40 && again.invoices(null).size() >= 4
                    && again.events(lic, null, 100).size() >= 8 && again.open(key2) != null);

            System.out.println("-- The installer --");
            r = call("GET", "/install.sh", null, null, null);
            checkTrue("memoryq.com/install.sh serves the installer", r[0].equals("200") && r[1].startsWith("#!/bin/bash") && r[1].contains("BASE=\"http://127.0.0.1:" + port + "\""));
            boolean bash = !System.getProperty("os.name", "").toLowerCase().startsWith("windows") && new File("/bin/bash").exists();
            if (!bash) {
                System.out.println("  (the installer test run is skipped on this PC - it needs Linux)");
            } else {
                now[0] = System.currentTimeMillis();
                LicenseStore.Licence fresh = store.create("juma", "single", 1, "monthly", 0, 0, "");
                LicenseStore.Invoice fi = store.bill(fresh.id, "period", 0, "manual");
                store.markPaid(fi.id, "manual", "test", "admin");
                String k = store.newKey(fresh.id, "juma", "127.0.0.1");
                store.flushIfDirty();
                Path sh = dir.resolve("install.sh");
                Files.write(sh, r[1].getBytes(StandardCharsets.UTF_8));
                Path root = dir.resolve("root");
                ProcessBuilder pb = new ProcessBuilder("/bin/bash", sh.toString(), k);
                pb.environment().put("MQ_DRYRUN", "1");
                pb.environment().put("MQ_ROOT", root.toString());
                pb.redirectErrorStream(true);
                Process pr = pb.start();
                String outText = new String(readAll(pr.getInputStream()), StandardCharsets.UTF_8);
                pr.waitFor(120, TimeUnit.SECONDS);
                Path conf = root.resolve("etc/memoryq/licence.conf");
                checkTrue("a test run: checks, activation, first check-in (" + (outText.contains("DONE.") && !outText.contains("NOTE") ? "DONE" : outText.replaceAll("\\s+", " ")) + ")",
                        outText.contains("DONE.") && Files.exists(conf) && new String(Files.readAllBytes(conf), StandardCharsets.UTF_8).contains("MQ_SECRET=\"MQS.")
                        && Files.exists(root.resolve("var/lib/memoryq/last-checkin")) && new String(Files.readAllBytes(root.resolve("var/lib/memoryq/last-checkin")), StandardCharsets.UTF_8).contains("\"ok\":true")
                        && store.licence(fresh.id).keyUsed);
                pb = new ProcessBuilder("/bin/bash", sh.toString(), k);
                pb.environment().put("MQ_DRYRUN", "1");
                pb.environment().put("MQ_ROOT", dir.resolve("root2").toString());
                pb.redirectErrorStream(true);
                pr = pb.start();
                outText = new String(readAll(pr.getInputStream()), StandardCharsets.UTF_8);
                pr.waitFor(120, TimeUnit.SECONDS);
                checkTrue("the same command again stops before changing anything", pr.exitValue() == 1 && outText.contains("already used")
                        && outText.contains("nothing was changed") && !Files.exists(dir.resolve("root2/etc/memoryq/licence.conf")));
            }
        } finally {
            http.stop(0);
        }
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void checkTrue(String name, boolean ok) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + name);
        if (!ok) {
            failures++;
        }
    }
}
package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Selling radio stations: the admin makes a station with its own login, owners sign up (details, then payment),
 * prices and the free trial, paying with (a pretend) Stripe, renewals, running out, the website player.
 */
public final class RadioSalesTest {

    private static int failures = 0;
    private static final long DAY = 24L * 3600 * 1000;

    public static void main(String[] args) throws Exception {
        System.setProperty("vstream.livePortMin", "18410");
        System.setProperty("vstream.livePortMax", "18419");
        Path dir = Files.createTempDirectory("memoryq-sales");

        System.out.println("-- Older station files --");
        Files.write(dir.resolve("old.txt"), ("S\t1\tOld Hits\tGood\tDJ Memoryq\t\t1\t3\t1\t1\t1\t100\tpromo\t\tactive\t18410\tabcdefgh2345\tend\n")
                .getBytes(StandardCharsets.UTF_8));
        RadioStore old = new RadioStore(dir.resolve("old.txt"), dir.resolve("oldmusic"));
        old.init();
        checkTrue("an older station counts as paid (stays on air)", old.station(1).onAir()
                && RadioStore.BILL_PAID.equals(old.station(1).billing) && old.station(1).paidUntil == 0);

        UserStore us = new UserStore(dir.resolve("users.txt"), new LiveChannelRegistry(), c -> { });
        us.init(dir.resolve("none.txt"));
        us.createUser("amina", "password123", UserStore.Role.CHATTER);
        us.createUser("other", "password123", UserStore.Role.CHATTER);
        SessionStore sessions = new SessionStore();
        String admin = sessions.create("admin", UserStore.Role.ADMIN);
        String amina = sessions.create("amina", UserStore.Role.CHATTER);
        String other = sessions.create("other", UserStore.Role.CHATTER);
        long[] now = {1767225600000L}; // 2026-01-01
        Mailer mailer = Mailer.fromSystemProperties();
        EmailService emails = new EmailService(dir.resolve("emails.txt"), us, mailer, () -> now[0]);
        emails.init();

        RadioStore store = new RadioStore(dir.resolve("radio.txt"), dir.resolve("music"));
        store.init();
        AudioTools tools = new AudioTools(dir.resolve("tools"), dir.resolve("tmp"));
        RadioService radio = new RadioService(store, tools, null, null, null, null, () -> now[0], new Random(3));
        RadioApi api = new RadioApi(radio, us, sessions, () -> now[0], dir.resolve("tmp"));
        api.setLiveHost("radio.example");
        java.util.List<String> sent = new java.util.ArrayList<String>();
        api.setMail(mailer, u -> {
            EmailService.Entry e = emails.get(u);
            return e == null ? null : e.email;
        }, "boss@radio.example");
        RadioBilling billing = new RadioBilling(dir.resolve("billing.txt"));
        billing.init();
        api.setBilling(billing);
        api.setEmails(emails);
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.register(http);
        http.start();
        int port = http.getAddress().getPort();

        // a pretend Stripe
        AtomicReference<String> lastCheckout = new AtomicReference<String>("");
        AtomicReference<String> sessionJson = new AtomicReference<String>("{}");
        AtomicReference<String> subJson = new AtomicReference<String>("{}");
        AtomicReference<String> lastAuth = new AtomicReference<String>("");
        HttpServer stripe = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stripe.createContext("/v1/", ex -> {
            lastAuth.set(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            String p = ex.getRequestURI().getPath();
            String out;
            int code = 200;
            if (p.equals("/v1/checkout/sessions") && "POST".equals(ex.getRequestMethod())) {
                lastCheckout.set(URLDecoder.decode(new String(readAll(ex.getRequestBody()), StandardCharsets.UTF_8), "UTF-8"));
                out = "{\"id\":\"cs_test_abc123\",\"url\":\"https://checkout.stripe.test/c/pay/cs_test_abc123\"}";
            } else if (p.startsWith("/v1/checkout/sessions/cs_")) {
                out = sessionJson.get();
            } else if (p.startsWith("/v1/subscriptions/sub_")) {
                out = subJson.get();
            } else if (p.equals("/v1/billing_portal/sessions")) {
                out = "{\"url\":\"https://billing.stripe.test/p/session/xyz\"}";
            } else {
                code = 404;
                out = "{\"error\":{\"message\":\"No such thing\"}}";
            }
            byte[] b = out.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(code, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        stripe.start();
        billing.setBaseUrl("http://127.0.0.1:" + stripe.getAddress().getPort());

        System.out.println("-- The admin makes a station with its own login --");
        String[] r = post(port, "/api/admin/radio/station/create", "name=Kilimani+FM&description=Nairobi+vibes"
                + "&website=kilimanifm.co.ke&livePort=18415&livePass=Secret12345&loginUser=kilimanifm&loginPassword=radiopass1"
                + "&email=owner%40kilimani.example&billing=trial&until=2026-01-15", admin);
        check("created", r[0], "200");
        long kid = Long.parseLong(num(r[1], "id"));
        RadioStore.Station k = store.station(kid);
        checkTrue("name, slogan, website, port and stream password as given", k.name.equals("Kilimani FM")
                && k.description.equals("Nairobi vibes") && k.website.equals("https://kilimanifm.co.ke")
                && k.livePort == 18415 && k.livePass.equals("Secret12345"));
        checkTrue("a trial until 15 January, on air", RadioStore.BILL_TRIAL.equals(k.billing) && k.onAir()
                && k.paidUntil == 1768521599999L && k.owner.equals("kilimanifm"));
        checkTrue("the login account was made and works", us.authenticate("kilimanifm", "radiopass1") != null
                && r[1].contains("\"newAccount\":true"));
        checkTrue("its email is saved as confirmed", emails.get("kilimanifm") != null && emails.get("kilimanifm").verified);
        String kilimani = sessions.create("kilimanifm", UserStore.Role.CHATTER);
        r = get(port, "/api/studio/status", kilimani);
        checkTrue("the owner signs in and sees every setting", r[1].contains("Kilimani FM") && r[1].contains("Secret12345")
                && r[1].contains("\"livePort\":18415") && r[1].contains("kilimanifm.co.ke") && r[1].contains("\"billing\":\"trial\""));
        r = post(port, "/api/admin/radio/station/create", "name=Clash+FM&livePort=18415", admin);
        checkTrue("a port in use is refused", r[0].equals("400") && r[1].contains("18415"));
        r = post(port, "/api/admin/radio/station/create", "name=Clash+FM&livePass=short", admin);
        checkTrue("a weak stream password is refused", r[0].equals("400") && r[1].contains("8 to 40"));
        r = post(port, "/api/admin/radio/station/create", "name=Clash+FM&loginUser=newowner", admin);
        checkTrue("a new login user needs a password", r[0].equals("400") && store.stations().size() == 1);
        r = post(port, "/api/admin/radio/station/create", "name=Kilimani+Two&loginUser=kilimanifm&billing=paid", admin);
        checkTrue("an existing login user gets a second station", r[0].equals("200") && r[1].contains("\"newAccount\":false")
                && store.ownedBy("kilimanifm").size() == 2);
        long k2 = Long.parseLong(num(r[1], "id"));
        checkTrue("paid with no end date is on air", store.station(k2).onAir() && store.station(k2).paidUntil == 0);
        r = post(port, "/api/admin/radio/station/create", "name=Admin+FM&loginUser=admin&loginPassword=whatever12", admin);
        check("an admin login is refused", r[0], "400");
        r = post(port, "/api/studio/station/create", "name=Sneaky", amina);
        check("an owner cannot use admin create", r[0], "403");

        System.out.println("-- An owner signs up online --");
        r = post(port, "/api/studio/request", "name=Amina+Radio&description=Taarab+and+more&website=https%3A%2F%2Famina.example"
                + "&livePort=18412&livePass=AminaLive2026", amina);
        check("details accepted", r[0], "200");
        long aid = Long.parseLong(num(r[1], "id"));
        RadioStore.Station a = store.station(aid);
        checkTrue("waiting for payment, not on air", RadioStore.STATUS_PENDING.equals(a.status)
                && RadioStore.BILL_UNPAID.equals(a.billing) && !a.onAir() && a.livePort == 18412 && a.livePass.equals("AminaLive2026"));
        r = post(port, "/api/studio/request", "name=Amina+Two", amina);
        checkTrue("one unfinished sign-up at a time", r[0].equals("409") && r[1].contains("payment"));
        r = get(port, "/api/studio/status", amina);
        Matcher fp = Pattern.compile("\"freePorts\":\\[([0-9,]*)\\]").matcher(r[1]);
        checkTrue("free ports and prices are shown", fp.find() && fp.group(1).startsWith("18411,")
                && !("," + fp.group(1) + ",").contains(",18412,") && !("," + fp.group(1) + ",").contains(",18415,")
                && r[1].contains("\"online\":false"));
        r = post(port, "/api/studio/pay", "station=" + aid, amina);
        checkTrue("no online payment until it is set up", r[0].equals("409") && r[1].contains("not set up"));
        r = post(port, "/api/studio/trial", "station=" + aid, amina);
        check("no free trial unless the admin offers one", r[0], "409");

        System.out.println("-- The admin sets prices --");
        r = post(port, "/api/admin/radio/billing/save", "currency=USD&month=19.99&year=199&trialDays=7&stripeKey=sk_test_12345abcdefXYZ", admin);
        checkTrue("saved", r[0].equals("200") && r[1].contains("\"month\":\"19.99\"") && r[1].contains("\"year\":\"199.00\"")
                && r[1].contains("\"online\":true") && r[1].contains("\"testMode\":true"));
        checkTrue("the key is never shown in full", r[1].contains("sk_test_...") && !r[1].contains("12345abcdefXYZ"));
        r = post(port, "/api/admin/radio/billing/save", "stripeKey=hello", admin);
        check("a wrong key is refused", r[0], "400");
        r = post(port, "/api/studio/billing/save", "month=0.01", amina);
        check("owners cannot change prices", r[0], "403");
        RadioBilling again = new RadioBilling(dir.resolve("billing.txt"));
        again.init();
        checkTrue("prices and key are kept", again.monthCents() == 1999 && again.yearCents() == 19900 && again.stripeReady());
        String billingFile = new String(Files.readAllBytes(dir.resolve("billing.txt")), StandardCharsets.UTF_8);
        checkTrue("the file holds the key", billingFile.contains("sk_test_12345abcdefXYZ"));

        System.out.println("-- Paying with Stripe --");
        r = post(port, "/api/studio/pay", "station=" + aid + "&plan=year", other);
        check("someone else cannot pay for it", r[0], "403");
        r = post(port, "/api/studio/pay", "station=" + aid + "&plan=year", amina);
        checkTrue("a payment page", r[0].equals("200") && r[1].contains("checkout.stripe.test"));
        String co = lastCheckout.get();
        checkTrue("for the yearly price, this station", co.contains("line_items[0][price_data][unit_amount]=19900")
                && co.contains("[recurring][interval]=year") && co.contains("metadata[station]=" + aid)
                && co.contains("currency]=usd") && co.contains("Amina Radio") && co.contains("mode=subscription")
                && co.contains("success_url=https://radio.example/studio?paid={CHECKOUT_SESSION_ID}"));
        checkTrue("signed with the secret key", lastAuth.get().equals("Bearer sk_test_12345abcdefXYZ"));
        long periodEnd = (now[0] + 365 * DAY) / 1000;
        sessionJson.set("{\"id\":\"cs_test_abc123\",\"status\":\"open\",\"payment_status\":\"unpaid\",\"metadata\":{\"station\":\"" + aid + "\"}}");
        r = post(port, "/api/studio/paid", "station=" + aid + "&session=cs_test_abc123", amina);
        check("not paid yet", r[0], "402");
        sessionJson.set("{\"id\":\"cs_test_abc123\",\"status\":\"complete\",\"payment_status\":\"paid\",\"customer\":\"cus_A1\","
                + "\"metadata\":{\"station\":\"" + (aid + 100) + "\"}}");
        r = post(port, "/api/studio/paid", "station=" + aid + "&session=cs_test_abc123", amina);
        check("a payment for another station does not count", r[0], "400");
        sessionJson.set("{\"id\":\"cs_test_abc123\",\"status\":\"complete\",\"payment_status\":\"paid\",\"customer\":\"cus_A1\","
                + "\"metadata\":{\"station\":\"" + aid + "\"},\"subscription\":{\"id\":\"sub_A1xyz\",\"status\":\"active\","
                + "\"items\":{\"data\":[{\"current_period_end\":" + periodEnd + ",\"price\":{\"recurring\":{\"interval\":\"year\"}}}]}}}");
        r = post(port, "/api/studio/paid", "station=" + aid + "&session=cs_test_abc123", amina);
        a = store.station(aid);
        checkTrue("paid: active and on air for a year", r[0].equals("200") && a.onAir() && RadioStore.BILL_PAID.equals(a.billing)
                && a.paidUntil == periodEnd * 1000 && a.stripeSub.equals("sub_A1xyz") && a.plan.equals("year"));
        r = post(port, "/api/studio/paid", "station=" + aid + "&session=cs_test_abc123", amina);
        check("coming back to the page again is fine", r[0], "200");
        r = post(port, "/api/studio/billing/portal", "station=" + aid, amina);
        checkTrue("the owner can manage the card / cancel", r[0].equals("200") && r[1].contains("billing.stripe.test"));
        r = post(port, "/api/studio/billing/portal", "station=" + kid, kilimani);
        check("no card to manage for a hand-set trial", r[0], "409");

        System.out.println("-- Renewals and running out --");
        now[0] = 1768521599999L - 2 * DAY;
        api.billingTick();
        checkTrue("a reminder before the trial ends (once)", store.station(kid).reminded == store.station(kid).paidUntil
                && store.station(kid).onAir());
        now[0] = 1768521599999L + 1;
        api.billingTick();
        checkTrue("the trial ends: off air", !store.station(kid).onAir() && RadioStore.BILL_UNPAID.equals(store.station(kid).billing));
        now[0] = a.paidUntil - 3600 * 1000;
        long next = (a.paidUntil + 365 * DAY) / 1000;
        subJson.set("{\"id\":\"sub_A1xyz\",\"status\":\"active\",\"current_period_end\":" + next + "}");
        api.billingTick();
        checkTrue("a renewal moves the end date on", store.station(aid).paidUntil == next * 1000 && store.station(aid).onAir());
        now[0] = next * 1000 + RadioBilling.GRACE_MS + 1000;
        subJson.set("{\"id\":\"sub_A1xyz\",\"status\":\"canceled\",\"current_period_end\":" + next + "}");
        api.billingTick();
        checkTrue("a cancelled subscription goes off air after the grace days", !store.station(aid).onAir()
                && RadioStore.BILL_UNPAID.equals(store.station(aid).billing));

        r = post(port, "/api/studio/trial", "station=" + kid, kilimani);
        check("no second trial", r[0], "409");
        r = post(port, "/api/admin/radio/station/admin", "id=" + kid + "&billing=paid&until=2026-12-31", admin);
        checkTrue("the admin marks it paid", r[0].equals("200") && store.station(kid).onAir()
                && store.station(kid).paidUntil == 1798761599999L);
        r = post(port, "/api/admin/radio/station/admin", "id=" + kid + "&website=nonsense", admin);
        check("a bad website is refused", r[0], "400");
        r = post(port, "/api/studio/station/save", "id=" + kid + "&website=https%3A%2F%2Fnew.kilimani.example", kilimani);
        checkTrue("the owner changes the website", r[0].equals("200") && store.station(kid).website.equals("https://new.kilimani.example"));
        r = post(port, "/api/studio/live/password", "station=" + kid + "&pass=MyOwnPass99", kilimani);
        checkTrue("the owner sets their own stream password", r[0].equals("200") && store.station(kid).livePass.equals("MyOwnPass99"));

        System.out.println("-- A free trial from the studio --");
        r = post(port, "/api/studio/request", "name=Other+Radio", other);
        long oid = Long.parseLong(num(r[1], "id"));
        r = post(port, "/api/studio/trial", "station=" + oid, other);
        checkTrue("7 free days, on air", r[0].equals("200") && store.station(oid).onAir()
                && RadioStore.BILL_TRIAL.equals(store.station(oid).billing) && store.station(oid).paidUntil == now[0] + 7 * DAY);

        System.out.println("-- Saved --");
        store.flushIfDirty();
        RadioStore reload = new RadioStore(dir.resolve("radio.txt"), dir.resolve("music"));
        reload.init();
        RadioStore.Station ra = reload.station(aid);
        RadioStore.Station rk = reload.station(kid);
        checkTrue("billing, Stripe and website survive a restart", ra.stripeSub.equals("sub_A1xyz") && ra.stripeCustomer.equals("cus_A1")
                && ra.plan.equals("year") && RadioStore.BILL_UNPAID.equals(ra.billing) && rk.website.equals("https://new.kilimani.example")
                && rk.paidUntil == 1798761599999L && rk.trialUsed && rk.livePass.equals("MyOwnPass99"));

        System.out.println("-- The player for websites --");
        store.updateStation(withName(store.station(kid), "Kilimani <FM>"));
        r = get(port, "/radio/embed/" + kid, null);
        checkTrue("the player page", r[0].equals("200") && r[1].contains("Kilimani &lt;FM&gt;") && !r[1].contains("Kilimani <FM>")
                && r[1].contains("/radio/'+id+'.mp3") && r[1].contains("var id=" + kid));
        r = get(port, "/radio/" + kid + ".m3u", null);
        checkTrue("a playlist for radio apps", r[0].equals("200") && r[1].contains("https://radio.example/radio/" + kid + ".mp3"));
        check("no player for a missing station", get(port, "/radio/embed/999", null)[0], "404");

        radio.stopAll();
        http.stop(0);
        stripe.stop(0);
        System.out.println();
        if (failures == 0) {
            System.out.println("ALL CHECKS PASSED");
        } else {
            System.out.println(failures + " CHECK(S) FAILED");
            System.exit(1);
        }
        System.exit(0);
    }

    private static RadioStore.Station withName(RadioStore.Station s, String name) {
        s.name = name;
        return s;
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            b.write(buf, 0, n);
        }
        return b.toByteArray();
    }

    private static String num(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":(\\d+)").matcher(json);
        return m.find() ? m.group(1) : "0";
    }

    private static String[] get(int port, String path, String token) throws Exception {
        return http(port, "GET", path, null, token);
    }

    private static String[] post(int port, String path, String body, String token) throws Exception {
        return http(port, "POST", path, body, token);
    }

    private static String[] http(int port, String method, String path, String body, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
        if (token != null) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        byte[] b = in == null ? new byte[0] : readAll(in);
        return new String[]{String.valueOf(code), new String(b, StandardCharsets.UTF_8)};
    }

    private static void check(String name, String actual, String expected) {
        checkTrue(name + " (got " + actual + ")", expected.equals(actual));
    }

    private static void checkTrue(String name, boolean ok) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + name);
        if (!ok) {
            failures++;
        }
    }
}
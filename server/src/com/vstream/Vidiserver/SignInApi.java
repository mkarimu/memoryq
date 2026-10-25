package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * One Memoryq account everywhere: memoryq.com, the admin panel, DJ Pro, World Radio, the Memoryq app and the Vistr game.
 *   GET  /api/auth/config                 {google: the Google client ID or ""}
 *   POST /api/auth/google                 credential (Google's ID token), remember, link=1 (with a sign-in: link Google to it)
 *   POST /api/auth/google/unlink          (signed in)
 *   POST /api/auth/handoff                app, return, remember (signed in) -> {code, url}: hands the sign-in to the Vistr game
 *                                         or the Memoryq app; the code works once, for 2 minutes, only for known addresses
 *   POST /api/auth/exchange               code -> a sign-in of its own for that app (the same answer as /api/login + email)
 *   GET/POST /api/admin/auth/settings     googleClientId (super-admin)
 * Google: the ID token's signature is checked with Google's published keys; the address must be verified by Google.
 * A Google account is linked to a Memoryq account the first time (same verified email), or a new account is made.
 * The super-admin's account is never linked by email alone - link Google once in the profile while signed in.
 */
public final class SignInApi {

    public interface Fetcher {
        String get(String url) throws IOException;
    }

    /** Signs a member in (AccountApi: checks suspension, makes the sign-in, writes the usual answer); null when refused. */
    public interface Issuer {
        String issue(UserStore.Info info, long ttlMs) throws IOException;

        String refusal(UserStore.Info info);
    }

    static final class Code {
        String username;
        String app;
        long ttl;
        long expires;
        boolean remember;
    }

    private final Path file;
    private final UserStore users;
    private final SessionStore sessions;
    private final EmailService emails;
    private final Issuer issuer;
    private final LongSupplier clock;
    private final Map<String, String[]> links = new LinkedHashMap<String, String[]>(); // google sub -> {username, email, at}
    private final Map<String, Code> codes = new HashMap<String, Code>();
    private final Map<String, PublicKey> keys = new HashMap<String, PublicKey>();
    private long keysAt;
    private String clientId = "";
    private volatile Fetcher fetcher = SignInApi::httpGet;
    private final Limiter limiter = new Limiter();
    private static final SecureRandom RANDOM = new SecureRandom();

    public SignInApi(Path file, UserStore users, SessionStore sessions, EmailService emails, Issuer issuer, LongSupplier clock) {
        this.file = file;
        this.users = users;
        this.sessions = sessions;
        this.emails = emails;
        this.issuer = issuer;
        this.clock = clock;
        String env = System.getenv("GOOGLE_CLIENT_ID");
        if (env != null) {
            clientId = env.trim();
        }
    }

    public void setFetcher(Fetcher f) {
        this.fetcher = f;
        synchronized (this) {
            keys.clear();
            keysAt = 0;
        }
    }

    public synchronized void setClientId(String id) {
        clientId = id == null ? "" : id.trim();
        save();
    }

    public synchronized String clientId() {
        return clientId;
    }

    // ---- the file: G lines (Google links), C (client ID) ----

    public synchronized void init() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t", -1);
                if (f.length >= 2 && f[0].equals("C") && !f[1].isEmpty()) {
                    clientId = f[1];
                } else if (f.length >= 5 && f[0].equals("G")) {
                    links.put(f[1], new String[]{f[2], f[3], f[4]});
                }
            }
        } catch (IOException e) {
            System.out.println("[signin] could not read " + file + ": " + e.getMessage());
        }
    }

    private synchronized void save() {
        if (file == null) {
            return;
        }
        StringBuilder sb = new StringBuilder("C\t").append(clientId).append('\n');
        for (Map.Entry<String, String[]> e : links.entrySet()) {
            sb.append("G\t").append(e.getKey()).append('\t').append(e.getValue()[0]).append('\t').append(e.getValue()[1]).append('\t').append(e.getValue()[2]).append('\n');
        }
        try {
            Path part = file.resolveSibling(file.getFileName() + ".part");
            Files.write(part, sb.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.out.println("[signin] could not save: " + e.getMessage());
        }
    }

    public synchronized void rename(String from, String to) {
        for (String[] v : links.values()) {
            if (v[0].equalsIgnoreCase(from)) {
                v[0] = to;
            }
        }
        save();
    }

    /** A one-time code for a member, made by the server itself (the Profiler asks the Vistr game for its player list). */
    String codeFor(String username, String app, long ttlMs) {
        Code c = new Code();
        c.username = username;
        c.app = app;
        c.remember = false;
        c.ttl = ttlMs;
        c.expires = clock.getAsLong() + 120000L;
        String code = newCode();
        synchronized (codes) {
            codes.put(code, c);
        }
        return code;
    }

    public synchronized String googleOf(String username) {
        for (String[] v : links.values()) {
            if (v[0].equalsIgnoreCase(username)) {
                return v[1];
            }
        }
        return null;
    }

    // ---- Google's ID token ----

    static String httpGet(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            if (c.getResponseCode() != 200) {
                throw new IOException("HTTP " + c.getResponseCode());
            }
            return new String(AudioTools.readAll(c.getInputStream()), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    private static byte[] b64(String s) {
        return Base64.getUrlDecoder().decode(s.replace('+', '-').replace('/', '_').replace("=", ""));
    }

    private PublicKey key(String kid) throws IOException {
        synchronized (this) {
            PublicKey k = keys.get(kid);
            if (k != null && clock.getAsLong() - keysAt < 6 * 3600000L) {
                return k;
            }
        }
        String body = fetcher.get("https://www.googleapis.com/oauth2/v3/certs");
        Object all = MiniJson.at(MiniJson.parse(body), "keys");
        Map<String, PublicKey> fresh = new HashMap<String, PublicKey>();
        if (all instanceof List) {
            for (Object o : (List<?>) all) {
                try {
                    RSAPublicKeySpec spec = new RSAPublicKeySpec(new BigInteger(1, b64(MiniJson.str(o, "n"))), new BigInteger(1, b64(MiniJson.str(o, "e"))));
                    fresh.put(MiniJson.str(o, "kid"), KeyFactory.getInstance("RSA").generatePublic(spec));
                } catch (Exception e) {
                    // a key we cannot read
                }
            }
        }
        synchronized (this) {
            keys.clear();
            keys.putAll(fresh);
            keysAt = clock.getAsLong();
            return keys.get(kid);
        }
    }

    /** Checks Google's ID token: {sub, email, name}; throws with a reason. */
    String[] verify(String jwt) throws IOException {
        String cid = clientId();
        if (cid.isEmpty()) {
            throw new IOException("Google sign-in is not set up on this server yet");
        }
        String[] parts = jwt == null ? new String[0] : jwt.trim().split("\\.");
        if (parts.length != 3) {
            throw new IOException("that is not a Google sign-in");
        }
        Object head = MiniJson.parse(new String(b64(parts[0]), StandardCharsets.UTF_8));
        Object body = MiniJson.parse(new String(b64(parts[1]), StandardCharsets.UTF_8));
        if (!"RS256".equals(MiniJson.str(head, "alg"))) {
            throw new IOException("that is not a Google sign-in");
        }
        PublicKey pk = key(MiniJson.str(head, "kid"));
        if (pk == null) {
            throw new IOException("Google's key for this sign-in was not found - try again");
        }
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(pk);
            sig.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!sig.verify(b64(parts[2]))) {
                throw new IOException("the Google sign-in is not genuine");
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("the Google sign-in could not be checked");
        }
        String iss = MiniJson.str(body, "iss");
        if (!iss.equals("accounts.google.com") && !iss.equals("https://accounts.google.com")) {
            throw new IOException("that sign-in is not from Google");
        }
        if (!cid.equals(MiniJson.str(body, "aud"))) {
            throw new IOException("that Google sign-in was made for another website");
        }
        long now = clock.getAsLong() / 1000;
        if (MiniJson.num(body, "exp") < now - 60) {
            throw new IOException("the Google sign-in is too old - try again");
        }
        Object ev = MiniJson.at(body, "email_verified");
        if (!(Boolean.TRUE.equals(ev) || "true".equals(String.valueOf(ev)))) {
            throw new IOException("Google has not verified that email address");
        }
        String sub = MiniJson.str(body, "sub");
        String email = MiniJson.str(body, "email").toLowerCase(Locale.ROOT);
        if (sub.isEmpty() || email.isEmpty()) {
            throw new IOException("Google did not send the account details");
        }
        return new String[]{sub, email, MiniJson.str(body, "name")};
    }

    /** The Memoryq account for a Google account: linked before, the same verified email, or a new one. */
    UserStore.Info accountFor(String[] g, UserStore.Info current) throws Exception {
        String sub = g[0], email = g[1];
        synchronized (this) {
            if (current != null) { // linking Google to the account that is signed in
                for (Map.Entry<String, String[]> e : links.entrySet()) {
                    if (!e.getKey().equals(sub) && e.getValue()[0].equalsIgnoreCase(current.username)) {
                        throw new Bad(409, "this Memoryq account is already linked to another Google account (" + EmailService.mask(e.getValue()[1]) + ")");
                    }
                }
                String[] was = links.get(sub);
                if (was != null && !was[0].equalsIgnoreCase(current.username) && users.info(was[0]) != null) {
                    throw new Bad(409, "that Google account is already linked to another Memoryq account");
                }
                links.put(sub, new String[]{current.username, email, String.valueOf(clock.getAsLong())});
                save();
                return current;
            }
            String[] l = links.get(sub);
            if (l != null) {
                UserStore.Info i = users.info(l[0]);
                if (i != null) {
                    return i;
                }
                links.remove(sub); // the account was deleted
            }
        }
        String owner = emails == null ? null : emails.ownerOf(email);
        if (owner != null) {
            UserStore.Info i = users.info(owner);
            if (i != null) {
                if (i.role == UserStore.Role.ADMIN) {
                    throw new Bad(403, "for the super-admin account, sign in with the password once and press \"Link Google\" in your profile");
                }
                synchronized (this) {
                    links.put(sub, new String[]{i.username, email, String.valueOf(clock.getAsLong())});
                    save();
                }
                return i;
            }
        }
        // a new member
        String base = (g[2].isEmpty() ? email.substring(0, email.indexOf('@')) : g[2]).replaceAll("[^A-Za-z0-9_]+", "_").replaceAll("^_+|_+$", "");
        if (base.length() < 3) {
            base = "member_" + base;
        }
        if (base.length() > 20) {
            base = base.substring(0, 20);
        }
        String name = base;
        for (int n = 2; users.info(name) != null; n++) {
            name = base + n;
        }
        byte[] pw = new byte[18];
        RANDOM.nextBytes(pw);
        UserStore.Info made = users.createUser(name, Base64.getUrlEncoder().withoutPadding().encodeToString(pw), UserStore.Role.CHATTER);
        if (emails != null) {
            try {
                emails.adminSet(made.username, email);
            } catch (Exception e) {
                // the email is used by another account somehow: the account still works
            }
        }
        synchronized (this) {
            links.put(sub, new String[]{made.username, email, String.valueOf(clock.getAsLong())});
            save();
        }
        System.out.println("[signin] new member " + made.username + " (Google)");
        return made;
    }

    // ---- handing a sign-in to the Vistr game or the Memoryq app ----

    /** Where a sign-in may be handed: the Vistr game, the Memoryq app, and the Vistr Android app (its built-in page). */
    static boolean allowedReturn(String url) {
        try {
            URI u = new URI(url);
            String s = String.valueOf(u.getScheme()).toLowerCase(Locale.ROOT), h = String.valueOf(u.getHost()).toLowerCase(Locale.ROOT);
            if (s.equals("memoryq-app")) {
                return true;
            }
            if (s.equals("https") && (h.equals("vistr.memoryq.com") || h.equals("memoryq.com") || h.equals("www.memoryq.com") || h.equals("localhost"))) {
                return true;
            }
            return (s.equals("http") || s.equals("capacitor")) && h.equals("localhost") || extraReturn != null && url.startsWith(extraReturn);
        } catch (Exception e) {
            return false;
        }
    }

    static volatile String extraReturn = System.getProperty("vstream.extraReturn"); // tests

    private static String newCode() {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    // ---- HTTP ----

    static final class Bad extends Exception {
        final int status;

        Bad(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    public void register(HttpServer server) {
        server.createContext("/mq-auth.js", ex -> {
            byte[] b = AuthJs.JS.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/javascript; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/api/auth/", this::handle);
        server.createContext("/api/admin/auth/", this::handleAdmin);
    }

    private static String q(String s) {
        return ProfileApi.quote(s == null ? "" : s);
    }

    private static String p(String form, String k) {
        String v = MediaAuthHttpServer.queryParam(form, k);
        return v == null ? "" : v.trim();
    }

    private static String bearer(HttpExchange ex) {
        String a = ex.getRequestHeaders().getFirst("Authorization");
        return a != null && a.startsWith("Bearer ") ? a.substring(7).trim() : "";
    }

    private static String ip(HttpExchange ex) {
        String direct = ex.getRemoteAddress().getAddress().getHostAddress();
        String fwd = ex.getRequestHeaders().getFirst("X-Forwarded-For");
        if (fwd != null && (direct.startsWith("127.") || direct.equals("0:0:0:0:0:0:0:1"))) {
            return fwd.split(",")[0].trim();
        }
        return direct;
    }

    private UserStore.Info signedIn(HttpExchange ex) {
        SessionStore.Session s = sessions.get(bearer(ex));
        UserStore.Info i = s == null ? null : users.info(s.username);
        return i == null || i.disabled ? null : i;
    }

    private void handle(HttpExchange ex) throws IOException {
        int status = 200;
        String out;
        try {
            String op = ex.getRequestURI().getPath().substring("/api/auth/".length());
            if (op.equals("config")) {
                out = "{\"google\":" + q(clientId()) + "}";
            } else if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                throw new Bad(405, "use POST");
            } else {
                String form = SuperAgent.readBody(ex, 16384);
                String ipKey = "auth|" + ip(ex);
                if (!op.equals("exchange")) { // codes are long and random; the Vistr server exchanges for all its players
                    if (limiter.count(ipKey, 60000) >= 30) {
                        throw new Bad(429, "too many tries - wait a minute");
                    }
                    limiter.record(ipKey);
                }
                switch (op) {
                    case "google": {
                        String[] g;
                        try {
                            g = verify(p(form, "credential"));
                        } catch (IOException e) {
                            throw new Bad(401, e.getMessage());
                        }
                        UserStore.Info current = "1".equals(p(form, "link")) ? signedIn(ex) : null;
                        if ("1".equals(p(form, "link")) && current == null) {
                            throw new Bad(401, "sign in first, then link Google");
                        }
                        UserStore.Info i = accountFor(g, current);
                        if (i.disabled) {
                            throw new Bad(403, "this account is switched off");
                        }
                        String why = issuer.refusal(i);
                        if (why != null) {
                            throw new Bad(403, why);
                        }
                        out = issuer.issue(i, SessionStore.ttlFor(p(form, "remember")));
                        break;
                    }
                    case "google/unlink": {
                        UserStore.Info i = signedIn(ex);
                        if (i == null) {
                            throw new Bad(401, "sign in first");
                        }
                        synchronized (this) {
                            Iterator<Map.Entry<String, String[]>> it = links.entrySet().iterator();
                            while (it.hasNext()) {
                                if (it.next().getValue()[0].equalsIgnoreCase(i.username)) {
                                    it.remove();
                                }
                            }
                            save();
                        }
                        out = "{\"ok\":true}";
                        break;
                    }
                    case "handoff": {
                        UserStore.Info i = signedIn(ex);
                        if (i == null) {
                            throw new Bad(401, "sign in first");
                        }
                        String ret = p(form, "return");
                        if (!allowedReturn(ret)) {
                            throw new Bad(400, "that address may not receive a Memoryq sign-in");
                        }
                        Code c = new Code();
                        c.username = i.username;
                        c.app = p(form, "app");
                        c.remember = !"0".equals(p(form, "remember"));
                        c.ttl = c.remember ? SessionStore.REMEMBER_MS : SessionStore.SHORT_MS;
                        c.expires = clock.getAsLong() + 120000L;
                        String code = newCode();
                        synchronized (codes) {
                            codes.values().removeIf(x -> x.expires < clock.getAsLong());
                            codes.put(code, c);
                        }
                        String url = ret.replaceAll("#.*$", "") + "#mq=" + code;
                        out = "{\"code\":" + q(code) + ",\"url\":" + q(url) + "}";
                        break;
                    }
                    case "exchange": {
                        Code c;
                        synchronized (codes) {
                            c = codes.remove(p(form, "code"));
                        }
                        if (c == null || c.expires < clock.getAsLong()) {
                            throw new Bad(401, "that sign-in link has expired - sign in again");
                        }
                        UserStore.Info i = users.info(c.username);
                        if (i == null || i.disabled) {
                            throw new Bad(403, "this account is switched off");
                        }
                        String why = issuer.refusal(i);
                        if (why != null) {
                            throw new Bad(403, why);
                        }
                        String j = issuer.issue(i, c.ttl);
                        EmailService.Entry e = emails == null ? null : emails.get(i.username);
                        out = j.substring(0, j.length() - 1) + ",\"email\":" + q(e != null && e.verified ? e.email : "") + ",\"remember\":" + c.remember
                                + ",\"days\":" + (c.ttl / SessionStore.DAY_MS) + ",\"app\":" + q(c.app) + "}";
                        break;
                    }
                    default:
                        throw new Bad(404, "not found");
                }
            }
        } catch (Bad e) {
            status = e.status;
            out = "{\"error\":" + q(e.getMessage()) + "}";
        } catch (UserStore.UserException e) {
            status = 400;
            out = "{\"error\":" + q(e.getMessage()) + "}";
        } catch (Exception e) {
            status = 500;
            out = "{\"error\":" + q("server error: " + e.getMessage()) + "}";
        }
        send(ex, status, out);
    }

    private void handleAdmin(HttpExchange ex) throws IOException {
        int status = 200;
        String out;
        try {
            if (!SuperAgent.isAdmin(ex, sessions)) {
                throw new Bad(403, "super-admin only");
            }
            if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
                String id = p(SuperAgent.readBody(ex, 4096), "googleClientId");
                if (!id.isEmpty() && !id.matches("[0-9]+-[0-9a-z]+\\.apps\\.googleusercontent\\.com")) {
                    throw new Bad(400, "a Google client ID looks like 1234567890-abc123.apps.googleusercontent.com");
                }
                setClientId(id);
            }
            int n;
            synchronized (this) {
                n = links.size();
            }
            out = "{\"googleClientId\":" + q(clientId()) + ",\"linked\":" + n + "}";
        } catch (Bad e) {
            status = e.status;
            out = "{\"error\":" + q(e.getMessage()) + "}";
        } catch (Exception e) {
            status = 500;
            out = "{\"error\":" + q("server error: " + e.getMessage()) + "}";
        }
        send(ex, status, out);
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin != null && (origin.equals("https://vistr.memoryq.com") || origin.startsWith("http://localhost") || origin.startsWith("https://localhost")
                || origin.startsWith("capacitor://localhost"))) {
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", origin); // the Vistr game exchanges its code from its own pages
            ex.getResponseHeaders().set("Vary", "Origin");
        }
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }
}
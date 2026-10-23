package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * The members' marketplace (memoryq.com/market and the Memoryq app).
 * Everyone:   GET /api/market/meta | list?q=&cat=&min=&max=&cur=&sort=new|price|-price&offset=&limit= | item?id= |
 *             photo/ID.jpg
 * Members:    POST /api/market/save (id=0 new; title, desc, price, currency, category, condition, location)
 *             POST /api/market/photo/add?id=   (the picture is the body; JPEG or PNG)   /photo/remove id, photo   /photo/cover
 *             POST /api/market/status id, status=active|paused|sold|deleted     GET /api/market/mine
 *             POST /api/market/ask id, text (to the seller)   GET /api/market/threads   GET /api/market/thread?id=&buyer=
 *             POST /api/market/reply id, buyer, text   POST /api/market/report id, reason
 * Super-admin: GET /api/admin/market/list?status=   POST /api/admin/market/approve|reject|remove|restore id, reason
 *             GET /api/admin/market/reports   POST /api/admin/market/dismiss id   GET/POST /api/admin/market/settings autoApprove
 * New listings wait for the admin's approval (unless "approve automatically" is on); so do changed titles, texts and photos.
 * Memoryq does not handle payments: buyer and seller agree in the messages.
 */
public final class MarketApi {

    static final int MAX_LISTINGS = 30;
    static final int NEW_PER_DAY = 10;
    static final int MESSAGES_PER_HOUR = 40;
    static final int MAX_PHOTO_BYTES = 12 * 1024 * 1024;
    static final int PHOTO_SIDE = 1600;
    static final long ADMIN_MAIL_GAP_MS = 10L * 60000L;
    static final long SELLER_MAIL_GAP_MS = 60L * 60000L;

    private final MarketStore store;
    private final UserStore users;
    private final SessionStore sessions;
    private final LongSupplier clock;
    private final SecureRandom random = new SecureRandom();
    private volatile NotificationStore notes;
    private volatile StreamersApi.Sender sender;
    private volatile Function<String, String> emailOf;
    private volatile String adminEmail = "";
    private volatile String host = "memoryq.com";
    private long lastAdminMail;
    private final Map<String, Long> lastSellerMail = new HashMap<String, Long>();

    public MarketApi(MarketStore store, UserStore users, SessionStore sessions, LongSupplier clock) {
        this.store = store;
        this.users = users;
        this.sessions = sessions;
        this.clock = clock;
    }

    public MarketApi notifications(NotificationStore n) {
        this.notes = n;
        return this;
    }

    public MarketApi mail(StreamersApi.Sender s, Function<String, String> emailOfUser, String admin, String publicHost) {
        this.sender = s;
        this.emailOf = emailOfUser;
        this.adminEmail = admin == null ? "" : admin;
        if (publicHost != null && !publicHost.isEmpty()) {
            this.host = publicHost;
        }
        return this;
    }

    public void register(HttpServer server) {
        server.createContext("/api/market/", this::handle);
        server.createContext("/api/admin/market/", this::handleAdmin);
    }

    // ---- helpers ----

    static final class Bad extends Exception {
        final int status;

        Bad(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private UserStore.Info me(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : "";
        SessionStore.Session s = token.isEmpty() ? null : sessions.get(token);
        UserStore.Info i = s == null ? null : users.info(s.username);
        return i == null || i.disabled ? null : i;
    }

    private UserStore.Info member(HttpExchange ex) throws Bad {
        UserStore.Info i = me(ex);
        if (i == null) {
            throw new Bad(401, "sign in to Memoryq first");
        }
        if (i.role == UserStore.Role.GUEST || StreamersApi.anonymous(i, i.username)) {
            throw new Bad(403, "create your own Memoryq account to buy and sell");
        }
        return i;
    }

    private static boolean admin(UserStore.Info i) {
        return i != null && i.role == UserStore.Role.ADMIN;
    }

    private static String p(String form, String key) {
        String v = MediaAuthHttpServer.queryParam(form, key);
        return v == null ? "" : v.trim();
    }

    private static long id(String v) throws Bad {
        try {
            return Long.parseLong(v.trim());
        } catch (RuntimeException e) {
            throw new Bad(400, "which listing?");
        }
    }

    private static String body(HttpExchange ex, int max) throws IOException {
        return SuperAgent.readBody(ex, max);
    }

    private static String q(String s) {
        return ProfileApi.quote(s == null ? "" : s);
    }

    /** "1,500.50" -> 150050 cents; empty -> 0 (ask the seller). */
    static long cents(String v) throws Bad {
        String t = v.replace(",", "").replace(" ", "").trim();
        if (t.isEmpty()) {
            return 0;
        }
        try {
            java.math.BigDecimal b = new java.math.BigDecimal(t);
            if (b.signum() < 0 || b.compareTo(new java.math.BigDecimal("10000000000")) > 0) {
                throw new Bad(400, "the price does not look right");
            }
            return b.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new Bad(400, "the price must be a number, like 1500 or 15.50");
        }
    }

    /** Seen by everyone: approved and the seller's account still in good standing. */
    private boolean publicOk(MarketStore.Listing l) {
        if (!MarketStore.ACTIVE.equals(l.status) && !MarketStore.SOLD.equals(l.status)) {
            return false;
        }
        UserStore.Info s = users.info(l.seller);
        return s != null && !s.disabled;
    }

    private boolean mayEdit(MarketStore.Listing l, UserStore.Info me) {
        return me != null && (admin(me) || l.seller.equalsIgnoreCase(me.username));
    }

    // ---- JSON ----

    String listingJson(MarketStore.Listing l, UserStore.Info me, boolean full) {
        StringBuilder sb = new StringBuilder("{\"id\":").append(l.id).append(",\"seller\":").append(q(l.seller))
                .append(",\"title\":").append(q(l.title)).append(",\"priceCents\":").append(l.priceCents)
                .append(",\"currency\":").append(q(l.currency)).append(",\"category\":").append(q(l.category))
                .append(",\"condition\":").append(q(l.condition)).append(",\"location\":").append(q(l.location))
                .append(",\"status\":").append(q(l.status)).append(",\"created\":").append(l.created)
                .append(",\"updated\":").append(l.updated).append(",\"photos\":[");
        for (int i = 0; i < l.photos.size(); i++) {
            sb.append(i > 0 ? "," : "").append(q("/api/market/photo/" + l.photos.get(i) + ".jpg"));
        }
        sb.append("],\"photoIds\":[");
        for (int i = 0; i < l.photos.size(); i++) {
            sb.append(i > 0 ? "," : "").append(q(l.photos.get(i)));
        }
        sb.append(']');
        boolean mine = me != null && l.seller.equalsIgnoreCase(me.username);
        sb.append(",\"mine\":").append(mine);
        if (full || mine || admin(me)) {
            sb.append(",\"desc\":").append(q(l.desc)).append(",\"views\":").append(l.views);
        }
        if (mine || admin(me)) {
            sb.append(",\"reason\":").append(q(l.reason));
        }
        return sb.append('}').toString();
    }

    private String metaJson() {
        StringBuilder sb = new StringBuilder("{\"categories\":[");
        for (int i = 0; i < MarketStore.CATEGORIES.size(); i++) {
            String c = MarketStore.CATEGORIES.get(i);
            final String cc = c;
            int n = store.count(l -> l.category.equals(cc) && publicOk(l) && MarketStore.ACTIVE.equals(l.status));
            sb.append(i > 0 ? "," : "").append("{\"name\":").append(q(c)).append(",\"count\":").append(n).append('}');
        }
        sb.append("],\"currencies\":[");
        for (int i = 0; i < MarketStore.CURRENCIES.size(); i++) {
            sb.append(i > 0 ? "," : "").append(q(MarketStore.CURRENCIES.get(i)));
        }
        sb.append("],\"conditions\":[\"new\",\"like new\",\"used\"],\"maxPhotos\":").append(MarketStore.MAX_PHOTOS)
                .append(",\"autoApprove\":").append(store.autoApprove()).append('}');
        return sb.toString();
    }

    // ---- members ----

    private void handle(HttpExchange ex) throws IOException {
        try {
            String op = ex.getRequestURI().getPath().substring("/api/market/".length());
            String query = ex.getRequestURI().getRawQuery();
            boolean post = "POST".equalsIgnoreCase(ex.getRequestMethod());
            long now = clock.getAsLong();
            if (op.startsWith("photo/") && op.endsWith(".jpg") && !post) {
                String pid = op.substring(6, op.length() - 4);
                Path f = pid.matches("[0-9a-f]{20}") ? store.photoDir().resolve(pid + ".jpg") : null;
                if (f == null || !Files.isRegularFile(f)) {
                    throw new Bad(404, "no such photo");
                }
                VideoApi.sendFile(ex, f, "image/jpeg", "public, max-age=86400");
                return;
            }
            switch (op) {
                case "meta":
                    json(ex, 200, metaJson());
                    return;
                case "list":
                    json(ex, 200, listJson(query, me(ex)));
                    return;
                case "item": {
                    UserStore.Info me = me(ex);
                    MarketStore.Listing l = store.get(id(p(query, "id")));
                    if (l == null || (!publicOk(l) && !mayEdit(l, me)) || MarketStore.DELETED.equals(l.status) && !admin(me)) {
                        throw new Bad(404, "this listing is not available");
                    }
                    if (me == null || !l.seller.equalsIgnoreCase(me.username)) {
                        store.addView(l.id);
                    }
                    String lj = listingJson(l, me, true);
                    StringBuilder sb = new StringBuilder(lj.substring(0, lj.length() - 1));
                    sb.append(",\"sellerSince\":").append(firstListing(l.seller));
                    sb.append(",\"sellerActive\":").append(store.count(x -> x.seller.equalsIgnoreCase(l.seller) && MarketStore.ACTIVE.equals(x.status)));
                    if (me != null && !l.seller.equalsIgnoreCase(me.username)) {
                        sb.append(",\"myThread\":").append(store.thread(l.id, me.username).size());
                    }
                    json(ex, 200, sb.append('}').toString());
                    return;
                }
                default:
                    break;
            }
            UserStore.Info me = member(ex);
            switch (op) {
                case "mine": {
                    List<MarketStore.Listing> list = store.find(l -> l.seller.equalsIgnoreCase(me.username) && !MarketStore.DELETED.equals(l.status));
                    list.sort((a, b) -> Long.compare(b.updated, a.updated));
                    StringBuilder sb = new StringBuilder("{\"listings\":[");
                    for (int i = 0; i < list.size(); i++) {
                        sb.append(i > 0 ? "," : "").append(listingJson(list.get(i), me, true));
                    }
                    json(ex, 200, sb.append("],\"unread\":").append(store.unreadFor(me.username)).append('}').toString());
                    return;
                }
                case "threads":
                    json(ex, 200, threadsJson(me));
                    return;
                case "thread": {
                    MarketStore.Listing l = store.get(id(p(query, "id")));
                    String buyer = p(query, "buyer");
                    if (buyer.isEmpty()) {
                        buyer = me.username;
                    }
                    if (l == null || !(l.seller.equalsIgnoreCase(me.username) || buyer.equalsIgnoreCase(me.username))) {
                        throw new Bad(404, "no such conversation");
                    }
                    store.markRead(l.id, buyer, me.username);
                    json(ex, 200, threadJson(l, buyer, me));
                    return;
                }
                default:
                    break;
            }
            if (!post) {
                throw new Bad(405, "use POST");
            }
            if (op.equals("photo/add")) {
                MarketStore.Listing l = store.get(id(p(query, "id")));
                if (l == null || !mayEdit(l, me)) {
                    throw new Bad(404, "no such listing");
                }
                if (l.photos.size() >= MarketStore.MAX_PHOTOS) {
                    throw new Bad(409, MarketStore.MAX_PHOTOS + " photos at most");
                }
                byte[] jpeg = photo(ex.getRequestBody());
                String pid = photoId();
                Files.write(store.photoDir().resolve(pid + ".jpg"), jpeg);
                MarketStore.Listing after = store.addPhoto(l.id, pid);
                if (after == null) {
                    Files.deleteIfExists(store.photoDir().resolve(pid + ".jpg"));
                    throw new Bad(409, MarketStore.MAX_PHOTOS + " photos at most");
                }
                after = backToReview(after, me, now);
                json(ex, 200, listingJson(after, me, true));
                return;
            }
            String form = body(ex, 16 * 1024);
            switch (op) {
                case "save": {
                    long lid = p(form, "id").isEmpty() ? 0 : id(p(form, "id"));
                    MarketStore.Listing old = lid == 0 ? null : store.get(lid);
                    if (lid != 0 && (old == null || !mayEdit(old, me) || MarketStore.DELETED.equals(old.status))) {
                        throw new Bad(404, "no such listing");
                    }
                    if (old != null && MarketStore.REMOVED.equals(old.status) && !admin(me)) {
                        throw new Bad(403, "Memoryq took this listing down - it cannot be changed");
                    }
                    if (old == null) {
                        int open = store.count(l -> l.seller.equalsIgnoreCase(me.username) && !MarketStore.DELETED.equals(l.status)
                                && !MarketStore.SOLD.equals(l.status) && !MarketStore.REMOVED.equals(l.status));
                        if (open >= MAX_LISTINGS) {
                            throw new Bad(409, "you have " + open + " listings - mark some sold or delete them first");
                        }
                        int today = store.count(l -> l.seller.equalsIgnoreCase(me.username) && now - l.created < 86400000L);
                        if (today >= NEW_PER_DAY) {
                            throw new Bad(429, NEW_PER_DAY + " new listings a day at most - try again tomorrow");
                        }
                    }
                    MarketStore.Listing in = new MarketStore.Listing();
                    in.id = lid;
                    in.seller = old == null ? me.username : old.seller;
                    in.title = MarketStore.clean(p(form, "title"), 80);
                    in.desc = MarketStore.clean(MediaAuthHttpServer.queryParam(form, "desc"), 3000);
                    in.priceCents = cents(p(form, "price"));
                    in.currency = p(form, "currency").toUpperCase(Locale.ROOT);
                    in.category = p(form, "category");
                    in.condition = p(form, "condition").toLowerCase(Locale.ROOT);
                    in.location = MarketStore.clean(p(form, "location"), 60);
                    if (in.title.length() < 3) {
                        throw new Bad(400, "give it a title (3 letters or more)");
                    }
                    if (in.desc.length() < 10) {
                        throw new Bad(400, "describe it in a sentence or two");
                    }
                    if (!MarketStore.CURRENCIES.contains(in.currency)) {
                        in.currency = "USD";
                    }
                    if (!MarketStore.CATEGORIES.contains(in.category)) {
                        throw new Bad(400, "choose a category");
                    }
                    if (!MarketStore.CONDITIONS.contains(in.condition)) {
                        in.condition = "";
                    }
                    boolean review = old == null || !old.title.equals(in.title) || !old.desc.equals(in.desc) || !old.category.equals(in.category)
                            || MarketStore.REJECTED.equals(old.status) || MarketStore.PENDING.equals(old.status);
                    if (old == null || review) {
                        in.status = store.autoApprove() || admin(me) ? MarketStore.ACTIVE : MarketStore.PENDING;
                    } else {
                        in.status = old.status;
                    }
                    MarketStore.Listing saved = store.save(in, now);
                    if (MarketStore.PENDING.equals(saved.status)) {
                        store.setStatus(saved.id, MarketStore.PENDING, "", now);
                        tellAdmin(saved);
                    }
                    json(ex, 200, listingJson(store.get(saved.id), me, true));
                    return;
                }
                case "photo/remove":
                case "photo/cover": {
                    MarketStore.Listing l = store.get(id(p(form, "id")));
                    if (l == null || !mayEdit(l, me)) {
                        throw new Bad(404, "no such listing");
                    }
                    MarketStore.Listing after = op.equals("photo/remove") ? store.removePhoto(l.id, p(form, "photo"))
                            : store.coverPhoto(l.id, p(form, "photo"));
                    if (after == null) {
                        throw new Bad(404, "no such photo");
                    }
                    json(ex, 200, listingJson(after, me, true));
                    return;
                }
                case "status": {
                    MarketStore.Listing l = store.get(id(p(form, "id")));
                    if (l == null || !mayEdit(l, me) || MarketStore.DELETED.equals(l.status)) {
                        throw new Bad(404, "no such listing");
                    }
                    String to = p(form, "status");
                    boolean was = MarketStore.ACTIVE.equals(l.status) || MarketStore.PAUSED.equals(l.status) || MarketStore.SOLD.equals(l.status);
                    if (MarketStore.REMOVED.equals(l.status) && !admin(me) && !MarketStore.DELETED.equals(to)) {
                        throw new Bad(403, "Memoryq took this listing down");
                    }
                    if (MarketStore.ACTIVE.equals(to) || MarketStore.PAUSED.equals(to) || MarketStore.SOLD.equals(to)) {
                        if (!was) {
                            throw new Bad(409, "it is waiting for approval");
                        }
                    } else if (!MarketStore.DELETED.equals(to)) {
                        throw new Bad(400, "active, paused, sold or deleted");
                    }
                    MarketStore.Listing after = store.setStatus(l.id, to, l.reason, now);
                    if (MarketStore.DELETED.equals(to)) {
                        for (String ph : new ArrayList<String>(after.photos)) {
                            store.removePhoto(l.id, ph);
                        }
                    }
                    json(ex, 200, listingJson(store.get(l.id), me, true));
                    return;
                }
                case "ask":
                case "reply": {
                    MarketStore.Listing l = store.get(id(p(form, "id")));
                    if (l == null || MarketStore.DELETED.equals(l.status)) {
                        throw new Bad(404, "this listing is not available");
                    }
                    String text = MarketStore.clean(MediaAuthHttpServer.queryParam(form, "text"), 1000);
                    if (text.isEmpty()) {
                        throw new Bad(400, "write a message");
                    }
                    if (store.sentSince(me.username, now - 3600000L) >= MESSAGES_PER_HOUR) {
                        throw new Bad(429, "too many messages - wait a while");
                    }
                    String buyer;
                    if (op.equals("ask")) {
                        if (l.seller.equalsIgnoreCase(me.username)) {
                            throw new Bad(400, "this is your own listing");
                        }
                        if (!publicOk(l)) {
                            throw new Bad(404, "this listing is not available");
                        }
                        buyer = me.username;
                    } else {
                        buyer = p(form, "buyer");
                        boolean seller = l.seller.equalsIgnoreCase(me.username);
                        if (!seller && !buyer.equalsIgnoreCase(me.username)) {
                            throw new Bad(404, "no such conversation");
                        }
                        if (store.thread(l.id, buyer).isEmpty()) {
                            throw new Bad(404, "no such conversation");
                        }
                    }
                    store.addMessage(l.id, buyer, me.username, text, now);
                    String to = me.username.equalsIgnoreCase(l.seller) ? buyer : l.seller;
                    tell(to, me.username, l, text);
                    json(ex, 200, threadJson(store.get(l.id), buyer, me));
                    return;
                }
                case "report": {
                    MarketStore.Listing l = store.get(id(p(form, "id")));
                    if (l == null || !publicOk(l)) {
                        throw new Bad(404, "this listing is not available");
                    }
                    String reason = MarketStore.clean(p(form, "reason"), 300);
                    if (reason.isEmpty()) {
                        throw new Bad(400, "say what is wrong");
                    }
                    store.addReport(l.id, me.username, reason, now);
                    json(ex, 200, "{\"ok\":true}");
                    return;
                }
                default:
                    throw new Bad(404, "not found");
            }
        } catch (Bad e) {
            json(ex, e.status, "{\"error\":" + q(e.getMessage()) + "}");
        } catch (Exception e) {
            json(ex, 500, "{\"error\":" + q("server error: " + e.getMessage()) + "}");
        } finally {
            ex.close();
        }
    }

    /** A changed photo or text on an approved listing waits for approval again. */
    private MarketStore.Listing backToReview(MarketStore.Listing l, UserStore.Info me, long now) {
        if (admin(me) || store.autoApprove() || !MarketStore.ACTIVE.equals(l.status)) {
            return l;
        }
        MarketStore.Listing p = store.setStatus(l.id, MarketStore.PENDING, "", now);
        tellAdmin(p);
        return p;
    }

    private long firstListing(String seller) {
        long first = 0;
        for (MarketStore.Listing l : store.find(x -> x.seller.equalsIgnoreCase(seller))) {
            first = first == 0 ? l.created : Math.min(first, l.created);
        }
        return first;
    }

    private String photoId() {
        byte[] b = new byte[10];
        random.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    /** Any JPEG or PNG -> a clean JPEG (no camera data, such as where it was taken), at most 1600 pixels a side. */
    static byte[] photo(InputStream in) throws IOException, Bad {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) {
            raw.write(buf, 0, n);
            if (raw.size() > MAX_PHOTO_BYTES) {
                throw new Bad(413, "the photo is too big (12 MB at most)");
            }
        }
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(raw.toByteArray()));
        } catch (Exception e) {
            img = null;
        }
        if (img == null || img.getWidth() < 50 || img.getHeight() < 50) {
            throw new Bad(400, "that is not a photo we can use (JPEG or PNG)");
        }
        double sc = Math.min(1.0, (double) PHOTO_SIDE / Math.max(img.getWidth(), img.getHeight()));
        int w = Math.max(1, (int) Math.round(img.getWidth() * sc)), h = Math.max(1, (int) Math.round(img.getHeight() * sc));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.drawImage(img, 0, 0, w, h, null);
        g.dispose();
        ByteArrayOutputStream jpg = new ByteArrayOutputStream();
        ImageWriter wr = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(jpg)) {
            wr.setOutput(ios);
            ImageWriteParam prm = wr.getDefaultWriteParam();
            prm.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            prm.setCompressionQuality(0.85f);
            wr.write(null, new IIOImage(out, null, null), prm);
        } finally {
            wr.dispose();
        }
        return jpg.toByteArray();
    }

    String listJson(String query, UserStore.Info me) {
        String text = MarketStore.lower(p(query, "q"));
        String cat = p(query, "cat");
        String cur = p(query, "cur").toUpperCase(Locale.ROOT);
        String sort = p(query, "sort");
        String seller = p(query, "seller");
        long min, max;
        try {
            min = p(query, "min").isEmpty() ? -1 : cents(p(query, "min"));
            max = p(query, "max").isEmpty() ? -1 : cents(p(query, "max"));
        } catch (Bad e) {
            min = -1;
            max = -1;
        }
        int offset = 0, limit = 24;
        try {
            offset = Math.max(0, Integer.parseInt(p(query, "offset")));
        } catch (RuntimeException e) {
            offset = 0;
        }
        try {
            limit = Math.max(1, Math.min(60, Integer.parseInt(p(query, "limit"))));
        } catch (RuntimeException e) {
            limit = 24;
        }
        final long fmin = min, fmax = max;
        List<MarketStore.Listing> all = store.find(l -> MarketStore.ACTIVE.equals(l.status) && publicOk(l)
                && (cat.isEmpty() || l.category.equals(cat))
                && (seller.isEmpty() || l.seller.equalsIgnoreCase(seller))
                && (cur.isEmpty() || l.currency.equals(cur))
                && (fmin < 0 || l.priceCents >= fmin) && (fmax < 0 || (l.priceCents > 0 && l.priceCents <= fmax))
                && (text.isEmpty() || matches(l, text)));
        Comparator<MarketStore.Listing> c;
        if ("price".equals(sort)) {
            c = Comparator.comparingLong((MarketStore.Listing l) -> l.priceCents == 0 ? Long.MAX_VALUE : l.priceCents);
        } else if ("-price".equals(sort)) {
            c = Comparator.comparingLong((MarketStore.Listing l) -> l.priceCents).reversed();
        } else {
            c = Comparator.comparingLong((MarketStore.Listing l) -> l.created).reversed();
        }
        all.sort(c);
        StringBuilder sb = new StringBuilder("{\"total\":").append(all.size()).append(",\"offset\":").append(offset).append(",\"listings\":[");
        for (int i = offset, k = 0; i < all.size() && k < limit; i++, k++) {
            sb.append(k > 0 ? "," : "").append(listingJson(all.get(i), me, false));
        }
        return sb.append("]}").toString();
    }

    private static boolean matches(MarketStore.Listing l, String text) {
        String hay = MarketStore.lower(l.title + " " + l.desc + " " + l.location + " " + l.category + " " + l.seller);
        for (String w : text.split("\\s+")) {
            if (!w.isEmpty() && !hay.contains(w)) {
                return false;
            }
        }
        return true;
    }

    private String threadJson(MarketStore.Listing l, String buyer, UserStore.Info me) {
        StringBuilder sb = new StringBuilder("{\"listing\":").append(listingJson(l, me, false)).append(",\"buyer\":").append(q(buyer))
                .append(",\"seller\":").append(q(l.seller)).append(",\"messages\":[");
        List<MarketStore.Message> ms = store.thread(l.id, buyer);
        for (int i = 0; i < ms.size(); i++) {
            MarketStore.Message m = ms.get(i);
            sb.append(i > 0 ? "," : "").append("{\"from\":").append(q(m.from)).append(",\"text\":").append(q(m.text))
                    .append(",\"at\":").append(m.at).append(",\"mine\":").append(m.from.equalsIgnoreCase(me.username)).append('}');
        }
        return sb.append("]}").toString();
    }

    /** My conversations (as a buyer and as a seller), newest first. */
    private String threadsJson(UserStore.Info me) {
        Map<String, List<MarketStore.Message>> by = new LinkedHashMap<String, List<MarketStore.Message>>();
        for (MarketStore.Message m : store.messagesOf(me.username)) {
            by.computeIfAbsent(m.listing + "\t" + m.buyer.toLowerCase(Locale.ROOT), k -> new ArrayList<MarketStore.Message>()).add(m);
        }
        List<List<MarketStore.Message>> threads = new ArrayList<List<MarketStore.Message>>(by.values());
        threads.sort((a, b) -> Long.compare(b.get(b.size() - 1).at, a.get(a.size() - 1).at));
        StringBuilder sb = new StringBuilder("{\"threads\":[");
        int i = 0;
        for (List<MarketStore.Message> t : threads) {
            MarketStore.Message last = t.get(t.size() - 1);
            MarketStore.Listing l = store.get(last.listing);
            if (l == null) {
                continue;
            }
            int unread = 0;
            for (MarketStore.Message m : t) {
                if (!m.read && !m.from.equalsIgnoreCase(me.username)) {
                    unread++;
                }
            }
            boolean selling = l.seller.equalsIgnoreCase(me.username);
            sb.append(i++ > 0 ? "," : "").append("{\"id\":").append(l.id).append(",\"title\":").append(q(l.title))
                    .append(",\"photo\":").append(q(l.photos.isEmpty() ? "" : "/api/market/photo/" + l.photos.get(0) + ".jpg"))
                    .append(",\"buyer\":").append(q(last.buyer)).append(",\"with\":").append(q(selling ? last.buyer : l.seller))
                    .append(",\"selling\":").append(selling).append(",\"last\":").append(q(last.text)).append(",\"at\":").append(last.at)
                    .append(",\"unread\":").append(unread).append(",\"status\":").append(q(l.status)).append('}');
        }
        return sb.append("],\"unread\":").append(store.unreadFor(me.username)).append('}').toString();
    }

    // ---- notices ----

    private void tell(String to, String from, MarketStore.Listing l, String text) {
        NotificationStore n = notes;
        if (n != null) {
            n.add(to, "market", from, 0, l.id, (l.title.length() > 40 ? l.title.substring(0, 40) + "..." : l.title) + ": " + text, clock.getAsLong());
        }
        StreamersApi.Sender s = sender;
        Function<String, String> f = emailOf;
        if (s == null || f == null) {
            return;
        }
        String addr = f.apply(to);
        if (addr == null || addr.isEmpty()) {
            return;
        }
        synchronized (this) {
            String k = to.toLowerCase(Locale.ROOT) + "\t" + l.id;
            Long last = lastSellerMail.get(k);
            long now = clock.getAsLong();
            if (last != null && now - last < SELLER_MAIL_GAP_MS) {
                return;
            }
            lastSellerMail.put(k, now);
        }
        String body = "Hi " + to + ",\n\n" + from + " wrote about \"" + l.title + "\":\n\n" + text
                + "\n\nAnswer in the Marketplace: https://" + host + "/market#messages\n";
        new Thread(() -> {
            try {
                s.send(addr, "Marketplace: " + from + " about " + l.title, body);
            } catch (RuntimeException e) {
                System.out.println("[market] e-mail: " + e);
            }
        }, "market-mail").start();
    }

    private void tellAdmin(MarketStore.Listing l) {
        StreamersApi.Sender s = sender;
        if (s == null || adminEmail.isEmpty()) {
            return;
        }
        synchronized (this) {
            long now = clock.getAsLong();
            if (now - lastAdminMail < ADMIN_MAIL_GAP_MS) {
                return;
            }
            lastAdminMail = now;
        }
        int waiting = store.count(x -> MarketStore.PENDING.equals(x.status));
        String addr = adminEmail;
        new Thread(() -> {
            try {
                s.send(addr, "Marketplace: " + waiting + " listing(s) waiting for approval",
                        "Newest: \"" + l.title + "\" by " + l.seller + ".\n\nApprove or reject: https://" + host + "/admin (Marketplace)\n");
            } catch (RuntimeException e) {
                System.out.println("[market] e-mail: " + e);
            }
        }, "market-mail").start();
    }

    // ---- the super-admin ----

    private void handleAdmin(HttpExchange ex) throws IOException {
        try {
            UserStore.Info me = me(ex);
            if (!admin(me)) {
                throw new Bad(403, "admins only");
            }
            String op = ex.getRequestURI().getPath().substring("/api/admin/market/".length());
            String query = ex.getRequestURI().getRawQuery();
            boolean post = "POST".equalsIgnoreCase(ex.getRequestMethod());
            long now = clock.getAsLong();
            String form = post ? body(ex, 8192) : "";
            switch (op) {
                case "list": {
                    String st = p(query, "status");
                    List<MarketStore.Listing> list = store.find(l -> st.isEmpty() ? !MarketStore.DELETED.equals(l.status) : l.status.equals(st));
                    list.sort((a, b) -> Long.compare(b.updated, a.updated));
                    StringBuilder sb = new StringBuilder("{\"pending\":").append(store.count(l -> MarketStore.PENDING.equals(l.status)))
                            .append(",\"reports\":").append(store.openReports().size()).append(",\"listings\":[");
                    for (int i = 0; i < list.size() && i < 500; i++) {
                        sb.append(i > 0 ? "," : "").append(listingJson(list.get(i), me, true));
                    }
                    json(ex, 200, sb.append("]}").toString());
                    return;
                }
                case "reports": {
                    StringBuilder sb = new StringBuilder("{\"reports\":[");
                    int i = 0;
                    for (MarketStore.Report r : store.openReports()) {
                        MarketStore.Listing l = store.get(r.listing);
                        sb.append(i++ > 0 ? "," : "").append("{\"id\":").append(r.id).append(",\"by\":").append(q(r.by))
                                .append(",\"reason\":").append(q(r.reason)).append(",\"at\":").append(r.at)
                                .append(",\"listing\":").append(l == null ? "null" : listingJson(l, me, true)).append('}');
                    }
                    json(ex, 200, sb.append("]}").toString());
                    return;
                }
                case "settings":
                    if (post) {
                        store.setAutoApprove("1".equals(p(form, "autoApprove")));
                    }
                    json(ex, 200, "{\"autoApprove\":" + store.autoApprove() + "}");
                    return;
                default:
                    break;
            }
            if (!post) {
                throw new Bad(405, "use POST");
            }
            MarketStore.Listing l = store.get(id(p(form, "id")));
            if (l == null) {
                throw new Bad(404, "no such listing");
            }
            String reason = MarketStore.clean(p(form, "reason"), 300);
            switch (op) {
                case "approve":
                case "restore":
                    store.setStatus(l.id, MarketStore.ACTIVE, "", now);
                    store.handleReports(l.id);
                    notice(l, "approved", "Your listing \"" + l.title + "\" is now in the Marketplace.");
                    break;
                case "reject":
                    store.setStatus(l.id, MarketStore.REJECTED, reason.isEmpty() ? "not suitable for the Marketplace" : reason, now);
                    notice(l, "rejected", "Your listing \"" + l.title + "\" was not approved: "
                            + (reason.isEmpty() ? "not suitable for the Marketplace" : reason) + ". You can change it and send it again.");
                    break;
                case "remove":
                    store.setStatus(l.id, MarketStore.REMOVED, reason.isEmpty() ? "taken down by Memoryq" : reason, now);
                    store.handleReports(l.id);
                    notice(l, "removed", "Your listing \"" + l.title + "\" was taken down: " + (reason.isEmpty() ? "it broke the Marketplace rules" : reason) + ".");
                    break;
                case "dismiss":
                    store.handleReports(l.id);
                    break;
                default:
                    throw new Bad(404, "not found");
            }
            System.out.println("[market] admin " + me.username + " " + op + " listing " + l.id + " (" + l.title + ") of " + l.seller);
            json(ex, 200, listingJson(store.get(l.id), me, true));
        } catch (Bad e) {
            json(ex, e.status, "{\"error\":" + q(e.getMessage()) + "}");
        } catch (Exception e) {
            json(ex, 500, "{\"error\":" + q("server error: " + e.getMessage()) + "}");
        } finally {
            ex.close();
        }
    }

    private void notice(MarketStore.Listing l, String what, String text) {
        NotificationStore n = notes;
        if (n != null) {
            n.add(l.seller, "market", "Memoryq", 0, l.id, text, clock.getAsLong());
        }
    }

    private static void json(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
    }
}
package com.vstream.Vidiserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The members' marketplace: listings (with photos), the messages between a buyer and the seller about a listing, and
 * reports. One file (market.txt):
 *   L id seller title desc priceCents currency category condition location photos status created updated views reason end
 *   M id listing buyer from text at read end        (a message in the conversation of that buyer about that listing)
 *   R id listing by reason at handled end           (a report)
 *   S key value                                     (settings: autoApprove)
 * Photos are files in the photos folder: <photo id>.jpg (random, hard to guess).
 */
public final class MarketStore {

    public static final String PENDING = "pending";
    public static final String ACTIVE = "active";
    public static final String REJECTED = "rejected";
    public static final String PAUSED = "paused";
    public static final String SOLD = "sold";
    public static final String REMOVED = "removed";   // by the admin
    public static final String DELETED = "deleted";   // by the seller

    public static final List<String> CATEGORIES = Collections.unmodifiableList(Arrays.asList(
            "Phones & tablets", "Electronics", "Fashion", "Home & garden", "Vehicles", "Property", "Services",
            "Music & instruments", "Books & education", "Sports & outdoors", "Kids & baby", "Health & beauty",
            "Food & farm", "Jobs", "Other"));
    public static final List<String> CURRENCIES = Collections.unmodifiableList(Arrays.asList(
            "USD", "KES", "TZS", "UGX", "RWF", "NGN", "GHS", "ZAR", "EUR", "GBP", "CAD"));
    public static final List<String> CONDITIONS = Collections.unmodifiableList(Arrays.asList("", "new", "like new", "used"));
    public static final int MAX_PHOTOS = 8;

    public static final class Listing {
        public long id;
        public String seller = "";
        public String title = "";
        public String desc = "";
        public long priceCents;
        public String currency = "USD";
        public String category = "Other";
        public String condition = "";
        public String location = "";
        public List<String> photos = new ArrayList<String>();
        public String status = PENDING;
        public long created;
        public long updated;
        public long views;
        public String reason = "";

        Listing copy() {
            Listing c = new Listing();
            c.id = id;
            c.seller = seller;
            c.title = title;
            c.desc = desc;
            c.priceCents = priceCents;
            c.currency = currency;
            c.category = category;
            c.condition = condition;
            c.location = location;
            c.photos = new ArrayList<String>(photos);
            c.status = status;
            c.created = created;
            c.updated = updated;
            c.views = views;
            c.reason = reason;
            return c;
        }
    }

    public static final class Message {
        public long id;
        public long listing;
        public String buyer = "";
        public String from = "";
        public String text = "";
        public long at;
        public boolean read;
    }

    public static final class Report {
        public long id;
        public long listing;
        public String by = "";
        public String reason = "";
        public long at;
        public boolean handled;
    }

    private final Path file;
    private final Path photoDir;
    private final Map<Long, Listing> listings = new LinkedHashMap<Long, Listing>();
    private final List<Message> messages = new ArrayList<Message>();
    private final List<Report> reports = new ArrayList<Report>();
    private long nextListing = 1;
    private long nextMessage = 1;
    private long nextReport = 1;
    private boolean autoApprove;
    private boolean dirty;

    public MarketStore(Path file, Path photoDir) {
        this.file = file;
        this.photoDir = photoDir;
    }

    public Path photoDir() {
        return photoDir;
    }

    public static String clean(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\t', ' ').replace("\r", "").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** One line in the file: tabs and line breaks become spaces / "\n". */
    private static String enc(String s) {
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", " ");
    }

    private static String dec(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == 'n' ? '\n' : n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public synchronized void init() throws IOException {
        listings.clear();
        messages.clear();
        reports.clear();
        if (photoDir != null) {
            Files.createDirectories(photoDir);
        }
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] f = line.split("\t", -1);
            try {
                if ("L".equals(f[0]) && f.length >= 17) {
                    Listing l = new Listing();
                    l.id = Long.parseLong(f[1]);
                    l.seller = f[2];
                    l.title = dec(f[3]);
                    l.desc = dec(f[4]);
                    l.priceCents = Long.parseLong(f[5]);
                    l.currency = f[6];
                    l.category = f[7];
                    l.condition = f[8];
                    l.location = dec(f[9]);
                    for (String p : f[10].split(",")) {
                        if (p.matches("[0-9a-f]{20}")) {
                            l.photos.add(p);
                        }
                    }
                    l.status = f[11];
                    l.created = Long.parseLong(f[12]);
                    l.updated = Long.parseLong(f[13]);
                    l.views = Long.parseLong(f[14]);
                    l.reason = dec(f[15]);
                    listings.put(l.id, l);
                    nextListing = Math.max(nextListing, l.id + 1);
                } else if ("M".equals(f[0]) && f.length >= 9) {
                    Message m = new Message();
                    m.id = Long.parseLong(f[1]);
                    m.listing = Long.parseLong(f[2]);
                    m.buyer = f[3];
                    m.from = f[4];
                    m.text = dec(f[5]);
                    m.at = Long.parseLong(f[6]);
                    m.read = "1".equals(f[7]);
                    messages.add(m);
                    nextMessage = Math.max(nextMessage, m.id + 1);
                } else if ("R".equals(f[0]) && f.length >= 8) {
                    Report r = new Report();
                    r.id = Long.parseLong(f[1]);
                    r.listing = Long.parseLong(f[2]);
                    r.by = f[3];
                    r.reason = dec(f[4]);
                    r.at = Long.parseLong(f[5]);
                    r.handled = "1".equals(f[6]);
                    reports.add(r);
                    nextReport = Math.max(nextReport, r.id + 1);
                } else if ("S".equals(f[0]) && f.length >= 3 && "autoApprove".equals(f[1])) {
                    autoApprove = "1".equals(f[2]);
                }
            } catch (RuntimeException e) {
                // a damaged line
            }
        }
    }

    public synchronized void flushIfDirty() {
        if (!dirty || file == null) {
            return;
        }
        StringBuilder sb = new StringBuilder("# Memoryq marketplace: listings (L), messages (M), reports (R), settings (S)\n");
        sb.append("S\tautoApprove\t").append(autoApprove ? "1" : "0").append('\n');
        for (Listing l : listings.values()) {
            sb.append("L\t").append(l.id).append('\t').append(l.seller).append('\t').append(enc(l.title)).append('\t')
                    .append(enc(l.desc)).append('\t').append(l.priceCents).append('\t').append(l.currency).append('\t')
                    .append(l.category).append('\t').append(l.condition).append('\t').append(enc(l.location)).append('\t')
                    .append(String.join(",", l.photos)).append('\t').append(l.status).append('\t').append(l.created).append('\t')
                    .append(l.updated).append('\t').append(l.views).append('\t').append(enc(l.reason)).append("\tend\n");
        }
        for (Message m : messages) {
            sb.append("M\t").append(m.id).append('\t').append(m.listing).append('\t').append(m.buyer).append('\t').append(m.from)
                    .append('\t').append(enc(m.text)).append('\t').append(m.at).append('\t').append(m.read ? "1" : "0").append("\tend\n");
        }
        for (Report r : reports) {
            sb.append("R\t").append(r.id).append('\t').append(r.listing).append('\t').append(r.by).append('\t').append(enc(r.reason))
                    .append('\t').append(r.at).append('\t').append(r.handled ? "1" : "0").append("\tend\n");
        }
        try {
            Path part = file.resolveSibling(file.getFileName() + ".part");
            Files.write(part, sb.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } catch (IOException e) {
            System.out.println("[market] could not save: " + e.getMessage());
        }
    }

    // ---- Settings ----

    public synchronized boolean autoApprove() {
        return autoApprove;
    }

    public synchronized void setAutoApprove(boolean on) {
        autoApprove = on;
        dirty = true;
        flushIfDirty();
    }

    // ---- Listings ----

    public synchronized Listing get(long id) {
        Listing l = listings.get(id);
        return l == null ? null : l.copy();
    }

    public synchronized List<Listing> find(Predicate<Listing> p) {
        List<Listing> out = new ArrayList<Listing>();
        for (Listing l : listings.values()) {
            if (p.test(l)) {
                out.add(l.copy());
            }
        }
        return out;
    }

    public synchronized int count(Predicate<Listing> p) {
        int n = 0;
        for (Listing l : listings.values()) {
            if (p.test(l)) {
                n++;
            }
        }
        return n;
    }

    /** Saves a new listing (id 0) or the editable fields of an existing one. */
    public synchronized Listing save(Listing in, long now) {
        Listing l = in.id == 0 ? null : listings.get(in.id);
        if (l == null) {
            l = new Listing();
            l.id = nextListing++;
            l.seller = in.seller;
            l.created = now;
            l.status = in.status;
            listings.put(l.id, l);
        } else {
            l.status = in.status;
        }
        l.title = in.title;
        l.desc = in.desc;
        l.priceCents = in.priceCents;
        l.currency = in.currency;
        l.category = in.category;
        l.condition = in.condition;
        l.location = in.location;
        l.updated = now;
        dirty = true;
        flushIfDirty();
        return l.copy();
    }

    public synchronized Listing setStatus(long id, String status, String reason, long now) {
        Listing l = listings.get(id);
        if (l == null) {
            return null;
        }
        l.status = status;
        l.reason = reason == null ? "" : reason;
        l.updated = now;
        dirty = true;
        flushIfDirty();
        return l.copy();
    }

    public synchronized void addView(long id) {
        Listing l = listings.get(id);
        if (l != null) {
            l.views++;
            dirty = true;
        }
    }

    public synchronized Listing addPhoto(long id, String photo) {
        Listing l = listings.get(id);
        if (l == null || l.photos.size() >= MAX_PHOTOS) {
            return null;
        }
        l.photos.add(photo);
        dirty = true;
        flushIfDirty();
        return l.copy();
    }

    public synchronized Listing removePhoto(long id, String photo) {
        Listing l = listings.get(id);
        if (l == null || !l.photos.remove(photo)) {
            return null;
        }
        dirty = true;
        flushIfDirty();
        try {
            Files.deleteIfExists(photoDir.resolve(photo + ".jpg"));
        } catch (IOException e) {
            // left behind
        }
        return l.copy();
    }

    /** Moves a photo to the front (the cover picture). */
    public synchronized Listing coverPhoto(long id, String photo) {
        Listing l = listings.get(id);
        if (l == null || !l.photos.remove(photo)) {
            return null;
        }
        l.photos.add(0, photo);
        dirty = true;
        flushIfDirty();
        return l.copy();
    }

    // ---- Messages ----

    public synchronized Message addMessage(long listing, String buyer, String from, String text, long now) {
        Message m = new Message();
        m.id = nextMessage++;
        m.listing = listing;
        m.buyer = buyer;
        m.from = from;
        m.text = text;
        m.at = now;
        messages.add(m);
        dirty = true;
        flushIfDirty();
        return m;
    }

    /** The conversation between this buyer and the seller about a listing, oldest first. */
    public synchronized List<Message> thread(long listing, String buyer) {
        List<Message> out = new ArrayList<Message>();
        for (Message m : messages) {
            if (m.listing == listing && m.buyer.equalsIgnoreCase(buyer)) {
                out.add(m);
            }
        }
        return out;
    }

    /** The other side's messages in a conversation are read now. */
    public synchronized void markRead(long listing, String buyer, String reader) {
        for (Message m : messages) {
            if (m.listing == listing && m.buyer.equalsIgnoreCase(buyer) && !m.from.equalsIgnoreCase(reader) && !m.read) {
                m.read = true;
                dirty = true;
            }
        }
        flushIfDirty();
    }

    /** Every message where this person is the buyer or the seller of the listing. */
    public synchronized List<Message> messagesOf(String user) {
        List<Message> out = new ArrayList<Message>();
        for (Message m : messages) {
            Listing l = listings.get(m.listing);
            if (m.buyer.equalsIgnoreCase(user) || (l != null && l.seller.equalsIgnoreCase(user))) {
                out.add(m);
            }
        }
        return out;
    }

    public synchronized int unreadFor(String user) {
        int n = 0;
        for (Message m : messages) {
            Listing l = listings.get(m.listing);
            boolean mine = m.buyer.equalsIgnoreCase(user) || (l != null && l.seller.equalsIgnoreCase(user));
            if (mine && !m.read && !m.from.equalsIgnoreCase(user)) {
                n++;
            }
        }
        return n;
    }

    public synchronized int sentSince(String user, long since) {
        int n = 0;
        for (Message m : messages) {
            if (m.from.equalsIgnoreCase(user) && m.at >= since) {
                n++;
            }
        }
        return n;
    }

    // ---- Reports ----

    public synchronized Report addReport(long listing, String by, String reason, long now) {
        for (Report r : reports) {
            if (r.listing == listing && r.by.equalsIgnoreCase(by) && !r.handled) {
                return r;
            }
        }
        Report r = new Report();
        r.id = nextReport++;
        r.listing = listing;
        r.by = by;
        r.reason = reason;
        r.at = now;
        reports.add(r);
        dirty = true;
        flushIfDirty();
        return r;
    }

    public synchronized List<Report> openReports() {
        List<Report> out = new ArrayList<Report>();
        for (Report r : reports) {
            if (!r.handled) {
                out.add(r);
            }
        }
        return out;
    }

    public synchronized void handleReports(long listing) {
        for (Report r : reports) {
            if (r.listing == listing) {
                r.handled = true;
                dirty = true;
            }
        }
        flushIfDirty();
    }

    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
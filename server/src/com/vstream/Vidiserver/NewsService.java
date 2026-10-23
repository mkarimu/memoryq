package com.vstream.Vidiserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * News: RSS sources, AI-written headlines and summaries, and the list the app shows.
 * Only feed titles and short descriptions are sent to the AI; every story keeps its source name and
 * a link to the original. Saved to a text file.
 */
public final class NewsService {

    public interface Fetcher {
        byte[] fetch(String url) throws IOException;
    }

    public interface Summarizer {
        /** Returns null when the story is not suitable (the AI answered SKIP). */
        Summary summarize(String sourceName, String title, String description) throws IOException;

        boolean ready();

        String model();

        /** True if AI summaries are possible (a key is set). */
        default boolean aiReady() {
            return ready();
        }

        /** A short note for the admin page (for example why the AI was skipped). */
        default String note() {
            return "";
        }
    }

    public static final class Summary {
        public final String headline;
        public final String summary;
        public final String topic;
        public final int inTokens;
        public final int outTokens;
        public final boolean ai;

        public Summary(String headline, String summary, String topic, int inTokens, int outTokens) {
            this(headline, summary, topic, inTokens, outTokens, false);
        }

        public Summary(String headline, String summary, String topic, int inTokens, int outTokens, boolean ai) {
            this.ai = ai;
            this.headline = headline;
            this.summary = summary;
            this.topic = topic;
            this.inTokens = inTokens;
            this.outTokens = outTokens;
        }
    }

    /** The AI service refused our key (or there is none). */
    public static class AuthException extends IOException {
        private static final long serialVersionUID = 1L;

        public AuthException(String message) {
            super(message);
        }
    }

    /** The AI service says we are over a rate limit or out of quota. */
    public static class LimitException extends IOException {
        private static final long serialVersionUID = 1L;

        public LimitException(String message) {
            super(message);
        }
    }

    public static final class NewsException extends Exception {
        private static final long serialVersionUID = 1L;
        public final int status;

        public NewsException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    public static final class Source {
        public final long id;
        public final String name;
        public final String url;
        public final boolean enabled;
        public final long lastFetch;
        public final String lastError;
        public final int items;

        Source(long id, String name, String url, boolean enabled, long lastFetch, String lastError, int items) {
            this.id = id;
            this.name = name;
            this.url = url;
            this.enabled = enabled;
            this.lastFetch = lastFetch;
            this.lastError = lastError;
            this.items = items;
        }
    }

    public static final class Item {
        public final long id;
        public final long sourceId;
        public final String source;
        public final String link;
        public final String headline;
        public final String summary;
        public final String topic;
        public final long published;
        public final long created;
        public final boolean hidden;
        public boolean ai;

        Item(long id, long sourceId, String source, String link, String headline, String summary,
             String topic, long published, long created, boolean hidden) {
            this.id = id;
            this.sourceId = sourceId;
            this.source = source;
            this.link = link;
            this.headline = headline;
            this.summary = summary;
            this.topic = topic;
            this.published = published;
            this.created = created;
            this.hidden = hidden;
        }
    }

    public static final class Page {
        public final int total;
        public final List<Item> items;

        Page(int total, List<Item> items) {
            this.total = total;
            this.items = items;
        }
    }

    public static final class Status {
        public final boolean paused;
        public final boolean keyFound;
        public final String model;
        public final int usageCalls;
        public final long usageIn;
        public final long usageOut;
        public final int dailyCap;
        public final long lastRun;
        public final String lastRunError;
        public final int itemCount;
        public final List<Source> sources;
        public final String engineNote;

        Status(boolean paused, boolean keyFound, String model, int usageCalls, long usageIn, long usageOut,
               int dailyCap, long lastRun, String lastRunError, int itemCount, List<Source> sources, String engineNote) {
            this.paused = paused;
            this.keyFound = keyFound;
            this.model = model;
            this.usageCalls = usageCalls;
            this.usageIn = usageIn;
            this.usageOut = usageOut;
            this.dailyCap = dailyCap;
            this.lastRun = lastRun;
            this.lastRunError = lastRunError;
            this.itemCount = itemCount;
            this.sources = sources;
            this.engineNote = engineNote;
        }
    }

    private static final class SrcRec {
        final long id;
        final String name;
        final String url;
        boolean enabled = true;
        long lastFetch = 0;
        String lastError = "";

        SrcRec(long id, String name, String url) {
            this.id = id;
            this.name = name;
            this.url = url;
        }
    }

    private static final class ItemRec {
        final long id;
        final long sourceId;
        final String sourceName;
        final String link;
        final String headline;
        final String summary;
        final String topic;
        final long published;
        final long created;
        boolean hidden;
        final String key;
        boolean ai;

        ItemRec(long id, long sourceId, String sourceName, String link, String headline, String summary,
                String topic, long published, long created, String key) {
            this.id = id;
            this.sourceId = sourceId;
            this.sourceName = sourceName;
            this.link = link;
            this.headline = headline;
            this.summary = summary;
            this.topic = topic;
            this.published = published;
            this.created = created;
            this.key = key;
        }

        Item view() {
            Item v = new Item(id, sourceId, sourceName, link, headline, summary, topic, published, created, hidden);
            v.ai = ai;
            return v;
        }
    }

    private static final class Snap {
        final long id;
        final String name;
        final String url;

        Snap(long id, String name, String url) {
            this.id = id;
            this.name = name;
            this.url = url;
        }
    }

    public static final List<String> TOPICS = Collections.unmodifiableList(Arrays.asList(
            "world", "politics", "business", "tech", "science", "health", "sports", "entertainment", "other"));

    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long FRESH_MS = 3 * DAY;
    private static final long KEEP_MS = 30 * DAY;
    private static final long SHOW_MS = 14 * DAY;
    private static final int MAX_ITEMS = 400;
    private static final int MAX_SOURCES = 20;
    private static final int MAX_SKIPPED_KEYS = 500;

    private final Path file;
    private final LongSupplier clock;
    private final Fetcher fetcher;
    private final Summarizer summarizer;
    private final int dailyCap;
    private final int perSourceLimit;

    private final Map<Long, SrcRec> sources = new LinkedHashMap<Long, SrcRec>();
    private final List<ItemRec> items = new ArrayList<ItemRec>();
    private final LinkedHashSet<String> skipped = new LinkedHashSet<String>();
    private final Set<String> seen = new HashSet<String>();
    private long nextSourceId = 1;
    private long nextItemId = 1;
    private boolean paused = false;
    private String usageDay = "";
    private int usageCalls = 0;
    private long usageIn = 0;
    private long usageOut = 0;
    private long lastRun = 0;
    private String lastRunError = "";
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    public NewsService(Path file, LongSupplier clock, Fetcher fetcher, Summarizer summarizer,
                       int dailyCap, int perSourceLimit) {
        this.file = file;
        this.clock = clock;
        this.fetcher = fetcher;
        this.summarizer = summarizer;
        this.dailyCap = dailyCap;
        this.perSourceLimit = perSourceLimit;
    }

    public synchronized void init() throws IOException {
        if (Files.exists(file)) {
            load();
            removeDuplicateLinks();
        }
    }

    // ---- Sources ----

    public Source addSource(String rawName, String rawUrl) throws NewsException {
        String name = oneLine(rawName, 40);
        if (name.length() < 2) {
            throw new NewsException(400, "give the source a name (2 to 40 characters)");
        }
        String url = rawUrl == null ? "" : rawUrl.trim();
        checkUrlShape(url);
        List<RssParser.FeedItem> found;
        try {
            found = RssParser.parse(fetcher.fetch(url));
        } catch (IOException e) {
            throw new NewsException(400, "could not read that feed: " + oneLine(e.getMessage(), 120));
        }
        if (found.isEmpty()) {
            throw new NewsException(400, "no stories were found in that feed");
        }
        synchronized (this) {
            if (sources.size() >= MAX_SOURCES) {
                throw new NewsException(400, "you can have at most " + MAX_SOURCES + " sources");
            }
            for (SrcRec s : sources.values()) {
                if (s.url.equalsIgnoreCase(url)) {
                    throw new NewsException(400, "that feed is already added");
                }
            }
            SrcRec rec = new SrcRec(nextSourceId++, name, url);
            rec.lastFetch = clock.getAsLong();
            sources.put(rec.id, rec);
            saveQuiet();
            return viewOf(rec);
        }
    }

    private static void checkUrlShape(String url) throws NewsException {
        String l = url.toLowerCase(java.util.Locale.ROOT);
        if (url.length() > 500 || !(l.startsWith("http://") || l.startsWith("https://"))) {
            throw new NewsException(400, "enter a web address that starts with https://");
        }
        try {
            java.net.URI u = new java.net.URI(url);
            if (u.getHost() == null || u.getUserInfo() != null) {
                throw new NewsException(400, "that is not a usable feed address");
            }
        } catch (java.net.URISyntaxException e) {
            throw new NewsException(400, "that is not a valid web address");
        }
    }

    public synchronized void removeSource(long id) throws NewsException {
        if (sources.remove(id) == null) {
            throw new NewsException(404, "no such source");
        }
        Iterator<ItemRec> it = items.iterator();
        while (it.hasNext()) {
            ItemRec r = it.next();
            if (r.sourceId == id) {
                seen.remove(r.key);
                it.remove();
            }
        }
        saveQuiet();
    }

    public synchronized void setEnabled(long id, boolean enabled) throws NewsException {
        SrcRec s = sources.get(id);
        if (s == null) {
            throw new NewsException(404, "no such source");
        }
        s.enabled = enabled;
        saveQuiet();
    }

    public synchronized void setPaused(boolean value) {
        paused = value;
        saveQuiet();
    }

    public synchronized void setHidden(long itemId, boolean hidden) throws NewsException {
        for (ItemRec r : items) {
            if (r.id == itemId) {
                r.hidden = hidden;
                saveQuiet();
                return;
            }
        }
        throw new NewsException(404, "no such story");
    }

    // ---- Reading ----

    /** Visible stories in a random order that depends only on the seed. */
    public synchronized Page visibleItems(Set<String> topics, long seed, int offset, int limit) {
        long cutoff = clock.getAsLong() - SHOW_MS;
        List<ItemRec> list = new ArrayList<ItemRec>();
        for (ItemRec r : items) {
            if (r.hidden) {
                continue;
            }
            long when = r.published > 0 ? r.published : r.created;
            if (when < cutoff) {
                continue;
            }
            if (topics != null && !topics.isEmpty() && !topics.contains(r.topic)) {
                continue;
            }
            list.add(r);
        }
        Collections.shuffle(list, new Random(seed));
        int from = Math.max(0, Math.min(offset, list.size()));
        int to = Math.max(from, Math.min(list.size(), from + Math.max(0, limit)));
        List<Item> page = new ArrayList<Item>();
        for (int i = from; i < to; i++) {
            page.add(list.get(i).view());
        }
        return new Page(list.size(), page);
    }

    /** Newest first, visible stories only (for the RSS feed). */
    public synchronized List<Item> latestVisible(int limit) {
        long cutoff = clock.getAsLong() - SHOW_MS;
        List<Item> out = new ArrayList<Item>();
        for (int i = items.size() - 1; i >= 0 && out.size() < limit; i--) {
            ItemRec r = items.get(i);
            long when = r.published > 0 ? r.published : r.created;
            if (!r.hidden && when >= cutoff) {
                out.add(r.view());
            }
        }
        return out;
    }

    /** Newest first, hidden ones included (for the admin page). */
    public synchronized List<Item> allItems(int limit) {
        List<Item> out = new ArrayList<Item>();
        for (int i = items.size() - 1; i >= 0 && out.size() < limit; i--) {
            out.add(items.get(i).view());
        }
        return out;
    }

    public synchronized Status status() {
        rollDay();
        List<Source> list = new ArrayList<Source>();
        for (SrcRec s : sources.values()) {
            list.add(viewOf(s));
        }
        return new Status(paused, summarizer.aiReady(), summarizer.model(), usageCalls, usageIn, usageOut,
                dailyCap, lastRun, lastRunError, items.size(), list, summarizer.note());
    }

    private Source viewOf(SrcRec s) {
        int count = 0;
        for (ItemRec r : items) {
            if (r.sourceId == s.id) {
                count++;
            }
        }
        return new Source(s.id, s.name, s.url, s.enabled, s.lastFetch, s.lastError, count);
    }

    // ---- Refreshing ----

    /** Why a refresh cannot start right now, or null if it can. */
    public synchronized String refreshBlocker() {
        if (paused) {
            return "news is paused";
        }
        if (!summarizer.ready()) {
            return "no OpenAI key was found (set OPENAI_API_KEY and restart the server)";
        }
        if (refreshing.get()) {
            return "a refresh is already running";
        }
        return null;
    }

    /** Reads every enabled feed and summarises new stories. Returns a short message about what happened. */
    public String refreshAll() {
        String blocker = refreshBlocker();
        if (blocker != null) {
            return blocker;
        }
        if (!refreshing.compareAndSet(false, true)) {
            return "a refresh is already running";
        }
        try {
            return doRefresh();
        } finally {
            refreshing.set(false);
        }
    }

    private String doRefresh() {
        List<Snap> targets = new ArrayList<Snap>();
        synchronized (this) {
            for (SrcRec s : sources.values()) {
                if (s.enabled) {
                    targets.add(new Snap(s.id, s.name, s.url));
                }
            }
        }
        int added = 0;
        String abort = null;
        boolean hadFailures = false;
        for (Snap s : targets) {
            if (abort != null) {
                break;
            }
            List<RssParser.FeedItem> feed;
            try {
                feed = RssParser.parse(fetcher.fetch(s.url));
                setSourceStatus(s.id, "");
            } catch (IOException e) {
                String msg = oneLine(e.getMessage(), 160);
                setSourceStatus(s.id, msg.isEmpty() ? "could not read the feed" : msg);
                continue;
            }
            final long nowMs = clock.getAsLong();
            List<RssParser.FeedItem> fresh = new ArrayList<RssParser.FeedItem>();
            for (RssParser.FeedItem it : feed) {
                long when = it.published > 0 ? it.published : nowMs;
                if (when < nowMs - FRESH_MS) {
                    continue;
                }
                if (isSeen(keyOf(s.id, it)) || hasCanonLink(it.link)) {
                    continue;
                }
                fresh.add(it);
            }
            Collections.sort(fresh, new Comparator<RssParser.FeedItem>() {
                @Override
                public int compare(RssParser.FeedItem a, RssParser.FeedItem b) {
                    long wa = a.published > 0 ? a.published : nowMs;
                    long wb = b.published > 0 ? b.published : nowMs;
                    return Long.compare(wb, wa);
                }
            });
            int taken = 0;
            int failures = 0;
            for (RssParser.FeedItem it : fresh) {
                if (taken >= perSourceLimit) {
                    break;
                }
                if (hasCanonLink(it.link)) {
                    continue;
                }
                if (!reserveCall()) {
                    abort = "the daily limit of " + dailyCap + " AI summaries was reached";
                    break;
                }
                taken++;
                String key = keyOf(s.id, it);
                try {
                    Summary sum = summarizer.summarize(s.name, it.title, it.description);
                    if (sum == null) {
                        markSkipped(key);
                    } else if (addItem(s, it, sum, key)) {
                        added++;
                    }
                } catch (AuthException e) {
                    abort = "OpenAI rejected the request (" + oneLine(e.getMessage(), 120) + ")";
                    break;
                } catch (LimitException e) {
                    abort = "OpenAI rate limit or quota reached (" + oneLine(e.getMessage(), 120) + ")";
                    break;
                } catch (IOException e) {
                    hadFailures = true;
                    failures++;
                    if (failures >= 3) {
                        break;
                    }
                }
            }
        }
        if (abort == null && hadFailures) {
            abort = "some stories could not be summarised and will be retried";
        }
        finishRun(abort);
        return "added " + added + (added == 1 ? " story" : " stories") + (abort != null ? " - " + abort : "");
    }

    private synchronized void setSourceStatus(long id, String error) {
        SrcRec s = sources.get(id);
        if (s != null) {
            s.lastFetch = clock.getAsLong();
            s.lastError = error;
        }
    }

    private static final Set<String> TRACKING = new HashSet<String>(Arrays.asList(
            "ocid", "ncid", "cmp", "fbclid", "gclid", "mc_cid", "mc_eid", "ito", "taid", "rssfeed"));

    /**
     * The same story can reach us from two feeds with different tracking tags on its link. This gives a
     * form that is equal for both: no www., no trailing slash, tracking parameters removed, the rest sorted.
     */
    static String canonicalLink(String link) {
        if (link == null) {
            return "";
        }
        try {
            java.net.URI u = new java.net.URI(link.trim());
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(java.util.Locale.ROOT);
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            String path = u.getRawPath() == null ? "" : u.getRawPath();
            while (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            List<String> keep = new ArrayList<String>();
            String q = u.getRawQuery();
            if (q != null) {
                for (String part : q.split("&")) {
                    if (part.isEmpty()) {
                        continue;
                    }
                    int eq = part.indexOf('=');
                    String low = (eq >= 0 ? part.substring(0, eq) : part).toLowerCase(java.util.Locale.ROOT);
                    if (low.startsWith("utm_") || low.startsWith("at_") || low.startsWith("ns_") || TRACKING.contains(low)) {
                        continue;
                    }
                    keep.add(part);
                }
            }
            Collections.sort(keep);
            return host + path + (keep.isEmpty() ? "" : "?" + String.join("&", keep));
        } catch (java.net.URISyntaxException e) {
            return link.trim().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private synchronized boolean hasCanonLink(String link) {
        String canon = canonicalLink(link);
        for (ItemRec r : items) {
            if (canonicalLink(r.link).equals(canon)) {
                return true;
            }
        }
        return false;
    }

    /** Removes later copies of the same story (the same link apart from tracking tags). Keeps the oldest. */
    private void removeDuplicateLinks() {
        Set<String> links = new HashSet<String>();
        Iterator<ItemRec> it = items.iterator();
        boolean changed = false;
        while (it.hasNext()) {
            ItemRec r = it.next();
            if (!links.add(canonicalLink(r.link))) {
                it.remove();
                changed = true;
            }
        }
        if (changed) {
            saveQuiet();
        }
    }

    private synchronized boolean isSeen(String key) {
        return seen.contains(key);
    }

    private synchronized boolean reserveCall() {
        rollDay();
        if (usageCalls >= dailyCap) {
            return false;
        }
        usageCalls++;
        return true;
    }

    private void rollDay() {
        String today = Instant.ofEpochMilli(clock.getAsLong()).atZone(ZoneOffset.UTC).toLocalDate().toString();
        if (!today.equals(usageDay)) {
            usageDay = today;
            usageCalls = 0;
            usageIn = 0;
            usageOut = 0;
        }
    }

    private synchronized void markSkipped(String key) {
        seen.add(key);
        skipped.add(key);
        while (skipped.size() > MAX_SKIPPED_KEYS) {
            Iterator<String> it = skipped.iterator();
            seen.remove(it.next());
            it.remove();
        }
    }

    private synchronized boolean addItem(Snap s, RssParser.FeedItem it, Summary sum, String key) {
        if (!sources.containsKey(s.id)) {
            return false;
        }
        usageIn += Math.max(0, sum.inTokens);
        usageOut += Math.max(0, sum.outTokens);
        String topic = TOPICS.contains(sum.topic) ? sum.topic : "other";
        ItemRec rec = new ItemRec(nextItemId++, s.id, oneLine(s.name, 40), it.link,
                oneLine(sum.headline, 120), oneLine(sum.summary, 500), topic, it.published,
                clock.getAsLong(), key);
        rec.ai = sum.ai;
        items.add(rec);
        seen.add(key);
        while (items.size() > MAX_ITEMS) {
            ItemRec old = items.remove(0);
            seen.remove(old.key);
        }
        saveQuiet();
        return true;
    }

    private synchronized void finishRun(String abort) {
        lastRun = clock.getAsLong();
        lastRunError = abort == null ? "" : abort;
        long cutoff = lastRun - KEEP_MS;
        Iterator<ItemRec> it = items.iterator();
        while (it.hasNext()) {
            ItemRec r = it.next();
            if (r.created < cutoff) {
                seen.remove(r.key);
                it.remove();
            }
        }
        saveQuiet();
    }

    private static String keyOf(long sourceId, RssParser.FeedItem it) {
        String basis = sourceId + "|" + (it.guid.isEmpty() ? it.link : it.guid);
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(basis.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                sb.append(String.format("%02x", h[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One line, no tabs or control characters, at most max characters. */
    static String oneLine(String raw, int max) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            sb.append((ch < 0x20 || ch == 0x7f) ? ' ' : ch);
        }
        String s = sb.toString().trim();
        return s.length() > max ? s.substring(0, max).trim() : s;
    }

    // ---- Storage ----

    private void load() throws IOException {
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] p = line.split("\t", -1);
            try {
                switch (p[0]) {
                    case "N":
                        nextSourceId = Long.parseLong(p[1]);
                        nextItemId = Long.parseLong(p[2]);
                        break;
                    case "P":
                        paused = "1".equals(p[1]);
                        break;
                    case "U":
                        usageDay = p[1];
                        usageCalls = Integer.parseInt(p[2]);
                        usageIn = Long.parseLong(p[3]);
                        usageOut = Long.parseLong(p[4]);
                        break;
                    case "R":
                        lastRun = Long.parseLong(p[1]);
                        lastRunError = p.length > 2 ? p[2] : "";
                        break;
                    case "S": {
                        SrcRec s = new SrcRec(Long.parseLong(p[1]), p[2], p[3]);
                        s.enabled = "1".equals(p[4]);
                        s.lastFetch = Long.parseLong(p[5]);
                        s.lastError = p.length > 6 ? p[6] : "";
                        sources.put(s.id, s);
                        break;
                    }
                    case "I": {
                        ItemRec r = new ItemRec(Long.parseLong(p[1]), Long.parseLong(p[2]), p[3], p[4], p[5],
                                p[6], p[7], Long.parseLong(p[8]), Long.parseLong(p[9]), p[11]);
                        r.hidden = "1".equals(p[10]);
                        r.ai = p.length > 12 && "1".equals(p[12]);
                        items.add(r);
                        seen.add(r.key);
                        break;
                    }
                    case "K":
                        skipped.add(p[1]);
                        seen.add(p[1]);
                        break;
                    default:
                        break;
                }
            } catch (RuntimeException e) {
                // skip a damaged line
            }
        }
    }

    private void saveQuiet() {
        try {
            save();
        } catch (IOException e) {
            System.out.println("[news] could not save: " + e.getMessage());
        }
    }

    private void save() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# VStream news - keep private\n");
        sb.append("N\t").append(nextSourceId).append('\t').append(nextItemId).append('\n');
        sb.append("P\t").append(paused ? "1" : "0").append('\n');
        sb.append("U\t").append(usageDay).append('\t').append(usageCalls).append('\t')
                .append(usageIn).append('\t').append(usageOut).append('\n');
        sb.append("R\t").append(lastRun).append('\t').append(oneLine(lastRunError, 300)).append('\n');
        for (SrcRec s : sources.values()) {
            sb.append("S\t").append(s.id).append('\t').append(s.name).append('\t').append(s.url).append('\t')
                    .append(s.enabled ? "1" : "0").append('\t').append(s.lastFetch).append('\t')
                    .append(oneLine(s.lastError, 200)).append('\n');
        }
        for (ItemRec r : items) {
            sb.append("I\t").append(r.id).append('\t').append(r.sourceId).append('\t').append(r.sourceName)
                    .append('\t').append(r.link).append('\t').append(r.headline).append('\t')
                    .append(r.summary).append('\t').append(r.topic).append('\t').append(r.published)
                    .append('\t').append(r.created).append('\t').append(r.hidden ? "1" : "0")
                    .append('\t').append(r.key).append('\t').append(r.ai ? "1" : "0").append('\n');
        }
        for (String k : skipped) {
            sb.append("K\t").append(k).append('\n');
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, sb.toString().getBytes(StandardCharsets.UTF_8));
        try {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            // not a POSIX file system (for example Windows)
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
package com.vstream.Vidiserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Radio stations and their music. A station has a name, a DJ name and voice, and how often the DJ talks; each
 * song keeps its title, artist, length and how often it was played. Saved to radio.txt; the music files go in
 * radio-music/STATION-ID/.
 */
public final class RadioStore {

    public static final long MAX_SONG_BYTES = 60L * 1024 * 1024;
    public static final List<String> AUDIO_TYPES = java.util.Collections.unmodifiableList(
            java.util.Arrays.asList("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav"));

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_SUSPENDED = "suspended";
    /** Paying for a station: paid (until paidUntil, 0 = no end), a free trial (until paidUntil), or not paid. */
    public static final String BILL_PAID = "paid";
    public static final String BILL_TRIAL = "trial";
    public static final String BILL_UNPAID = "unpaid";
    /** Live DJ ports: one per station, from this range (the firewall opens it). */
    public static final int LIVE_PORT_MIN = Integer.getInteger("vstream.livePortMin", 8010);
    public static final int LIVE_PORT_MAX = Integer.getInteger("vstream.livePortMax", 8099);
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    public static final class Station {
        public long id;
        public String name = "";
        public String description = "";
        public String djName = "Alma";
        public String voice = "";
        public boolean enabled = true;
        /** About how many songs between DJ breaks (a little random). 0 = the DJ never talks. */
        public int talkEvery = 3;
        public boolean newsOn = true;
        public boolean shoutOn = true;
        public boolean introsOn = true;
        public long created;
        /** A line the DJ says now and then, e.g. "Get all my songs on Spotify - just search Nelson!". */
        public String promo = "";
        /** The account that runs this station ("" = the platform's own station, run by the admin). */
        public String owner = "";
        /** active (on air when enabled), pending (asked for, waiting for the admin) or suspended (by the admin). */
        public String status = STATUS_ACTIVE;
        /** Where a live DJ connects (Icecast source, e.g. from BUTT or Mixxx), and the password for it. */
        public int livePort;
        public String livePass = "";
        /** The station's own website (shown to listeners), "" = none. */
        public String website = "";
        /** paid, trial or unpaid (see BILL_...). */
        public String billing = BILL_PAID;
        /** When the paid time or the trial ends (ms); 0 = no end. */
        public long paidUntil;
        /** "month" or "year" for an online subscription, "" when the admin set it by hand. */
        public String plan = "";
        public String stripeCustomer = "";
        public String stripeSub = "";
        public boolean trialUsed;
        /** The paidUntil a "your time is running out" email was sent for (so it goes only once). */
        public long reminded;
        /** The plan (RadioBilling) with its limits; 0 = no plan, no limits. */
        public int planId;
        /** The station's own time zone (for DJ hours), e.g. "Africa/Nairobi". */
        public String timezone = "UTC";
        /** Data sent to listeners in month monthKey ("2026-10"). */
        public String monthKey = "";
        public long monthBytes;
        /** "2026-10:80" / "2026-10:100" - the data warning already sent this month. */
        public String xferWarned = "";
        /** A song is not played again within this many minutes (when there are enough other songs). */
        public int noRepeatMin = 60;
        /** Limits set by the admin for this station only (0 = the plan's; -1 = no limit, for DJs -1 = none). */
        public int limListeners;
        public int limKbps;
        public int limStorageMb;
        public int limTransferGb;
        public int limDjs;
        /** Contact details (admin "Stream" tab). */
        public String company = "";
        public String email = "";
        /** Alma says the artist's name in song intros (off: only the song title - names are often mispronounced). */
        public boolean sayArtist;
        /** Alma reads the next part of a story about every ... songs (0 = no stories). */
        public int storyEvery;
        /** Alma says the station's name over a song about every ... songs (0 = never). */
        public int idEvery = 4;
        /** The AI presenter (Alma) on this station at all - switched by the admin (Admin - Radio - Stations, AI). */
        public boolean aiOn = true;
        /** Alma tells the heart of a song (its message, found in its words) about every ... songs (0 = never). */
        public int msgEvery;

        public boolean hasOwnLimits() {
            return limListeners != 0 || limKbps != 0 || limStorageMb != 0 || limTransferGb != 0 || limDjs != 0;
        }

        /** On air: switched on by its owner, allowed by the admin, and paid for (or in a trial). */
        public boolean onAir() {
            return enabled && STATUS_ACTIVE.equals(status) && !BILL_UNPAID.equals(billing);
        }

        Station copy() {
            Station s = new Station();
            s.id = id;
            s.name = name;
            s.description = description;
            s.djName = djName;
            s.voice = voice;
            s.enabled = enabled;
            s.talkEvery = talkEvery;
            s.newsOn = newsOn;
            s.shoutOn = shoutOn;
            s.introsOn = introsOn;
            s.created = created;
            s.promo = promo;
            s.owner = owner;
            s.status = status;
            s.livePort = livePort;
            s.livePass = livePass;
            s.website = website;
            s.billing = billing;
            s.paidUntil = paidUntil;
            s.plan = plan;
            s.stripeCustomer = stripeCustomer;
            s.stripeSub = stripeSub;
            s.trialUsed = trialUsed;
            s.reminded = reminded;
            s.planId = planId;
            s.timezone = timezone;
            s.monthKey = monthKey;
            s.monthBytes = monthBytes;
            s.xferWarned = xferWarned;
            s.noRepeatMin = noRepeatMin;
            s.sayArtist = sayArtist;
            s.storyEvery = storyEvery;
            s.idEvery = idEvery;
            s.aiOn = aiOn;
            s.msgEvery = msgEvery;
            s.limListeners = limListeners;
            s.limKbps = limKbps;
            s.limStorageMb = limStorageMb;
            s.limTransferGb = limTransferGb;
            s.limDjs = limDjs;
            s.company = company;
            s.email = email;
            return s;
        }
    }

    public static final class Track {
        public long id;
        public long station;
        public String file = "";
        public String title = "";
        public String artist = "";
        public String album = "";
        public long durationMs;
        public long added;
        public long plays;
        public long lastPlayed;
        /** Where listeners can get the song (website, Spotify, Apple Music...). */
        public String link = "";
        public long taps;
        public long requests;
        public int likes;

        Track copy() {
            Track t = new Track();
            t.id = id;
            t.station = station;
            t.file = file;
            t.title = title;
            t.artist = artist;
            t.album = album;
            t.durationMs = durationMs;
            t.added = added;
            t.plays = plays;
            t.lastPlayed = lastPlayed;
            t.link = link;
            t.taps = taps;
            t.requests = requests;
            t.likes = likes;
            return t;
        }

        /** "Title by Artist", or just the title. */
        public String label() {
            return artist.isEmpty() ? title : title + " by " + artist;
        }
    }

    /** A remote DJ of a station: their own login for the live DJ port, and the days/hours they may go live. */
    public static final class Dj {
        public long id;
        public long station;
        public String name = "";
        public String login = "";
        public String password = "";
        public boolean enabled = true;
        /** Mon..Sun as 7 characters of 1/0. */
        public String days = "1111111";
        /** Allowed hours [from, to) in the station's time zone; 0-24 = any time. to < from wraps past midnight. */
        public int from;
        public int to = 24;
        public long created;
        public long lastLive;
        /** The station admin lets this DJ use the live studio (Live DJ Pro in the browser). */
        public boolean studio;

        Dj copy() {
            Dj d = new Dj();
            d.id = id;
            d.station = station;
            d.name = name;
            d.login = login;
            d.password = password;
            d.enabled = enabled;
            d.days = days;
            d.from = from;
            d.to = to;
            d.created = created;
            d.lastLive = lastLive;
            d.studio = studio;
            return d;
        }

        /** May go live at this moment (in the station's zone)? */
        public boolean allowedAt(java.time.ZonedDateTime t) {
            int dow = t.getDayOfWeek().getValue() - 1;
            int h = t.getHour();
            boolean dayOk = days.length() == 7 && days.charAt(dow) == '1';
            if (from == to || (from == 0 && to == 24)) {
                return dayOk;
            }
            if (from < to) {
                return dayOk && h >= from && h < to;
            }
            // wraps past midnight: e.g. 22-02 belongs to the day it started
            if (h >= from) {
                return dayOk;
            }
            int prev = (dow + 6) % 7;
            return h < to && days.length() == 7 && days.charAt(prev) == '1';
        }
    }

    /**
     * A playlist. general = everyday rotation (picked by weight); scheduled = takes over on its days between start and
     * end (minutes after midnight, station time zone; end before start wraps past midnight); interval = one song
     * from it every N songs (jingles, station IDs, ads).
     */
    public static final class Playlist {
        public static final String GENERAL = "general";
        public static final String SCHEDULED = "scheduled";
        public static final String INTERVAL = "interval";
        /** Plays its songs once, in order, straight away - then switches itself off. */
        public static final String IMMEDIATE = "immediate";
        /** Interval playlists: every N songs ("songs") or every N minutes ("minutes"). */
        public String unit = "songs";

        public long id;
        public long station;
        public String name = "";
        public String type = GENERAL;
        public boolean enabled = true;
        public int weight = 5;
        /** shuffle or sequential. */
        public boolean sequential;
        public String days = "1111111";
        public int start;
        public int end = 60;
        public int every = 4;
        public List<Long> tracks = new ArrayList<Long>();
        public long created;
        /**
         * How much plays each time. Interval: "" one song per break, "all" the whole list in order (an ad break).
         * Scheduled show: "" fills the show's time (the list repeats), "once" plays the list once, "one" plays one song.
         */
        public String style = "";
        /** Rotation playlists: only between start and end (when every rotation playlist is timed, the times are ignored). */
        public boolean timed;
        /** Interval playlists: also play during scheduled shows (Centova never did). */
        public boolean inShows = true;
        /**
         * Scheduled shows on dates instead of weekdays: "" (the days), "month:15", "month:first", "month:last",
         * "year:10-18" or "once:2026-10-18".
         */
        public String when = "";
        /**
         * Scheduled shows: "yes" = another show whose time comes takes over at once (the song is cut);
         * "no" = this show goes on to its own end, the other one waits.
         */
        public String interrupt = "yes";
        /** A remote DJ may take over (go live) while this playlist is on. */
        public boolean djOverride = true;
        /** Alma (the AI presenter) talks while this playlist plays. */
        public boolean ai = true;
        /** In the live studio: the station's admin and DJs get this playlist in Live DJ Pro, ready to play. */
        public boolean studio = false;

        Playlist copy() {
            Playlist p = new Playlist();
            p.id = id;
            p.station = station;
            p.name = name;
            p.type = type;
            p.enabled = enabled;
            p.weight = weight;
            p.sequential = sequential;
            p.days = days;
            p.start = start;
            p.end = end;
            p.every = every;
            p.unit = unit;
            p.tracks = new ArrayList<Long>(tracks);
            p.created = created;
            p.style = style;
            p.timed = timed;
            p.inShows = inShows;
            p.when = when;
            p.interrupt = interrupt;
            p.djOverride = djOverride;
            p.ai = ai;
            p.studio = studio;
            return p;
        }

        /** Whether a show that starts on this date plays (the weekdays, or the date rule). */
        public boolean dayMatches(java.time.LocalDate d) {
            String w = when == null ? "" : when;
            try {
                if (w.startsWith("month:")) {
                    String v = w.substring(6);
                    if ("first".equals(v)) {
                        return d.getDayOfMonth() == 1;
                    }
                    if ("last".equals(v)) {
                        return d.getDayOfMonth() == d.lengthOfMonth();
                    }
                    int day = Integer.parseInt(v);
                    return d.getDayOfMonth() == Math.min(day, d.lengthOfMonth());
                }
                if (w.startsWith("year:")) {
                    return d.toString().substring(5).equals(w.substring(5));
                }
                if (w.startsWith("once:")) {
                    return d.toString().equals(w.substring(5));
                }
                if (w.startsWith("every:")) {
                    // every:N:day|week|month:YYYY-MM-DD - from that date on, every N days / weeks / months
                    String[] f = w.split(":");
                    int n = Integer.parseInt(f[1]);
                    java.time.LocalDate from = java.time.LocalDate.parse(f[3]);
                    if (d.isBefore(from) || n < 1) {
                        return false;
                    }
                    switch (f[2]) {
                        case "day":
                            return java.time.temporal.ChronoUnit.DAYS.between(from, d) % n == 0;
                        case "week":
                            long days = java.time.temporal.ChronoUnit.DAYS.between(from, d);
                            return days % 7 == 0 && (days / 7) % n == 0;
                        case "month":
                            long months = java.time.temporal.ChronoUnit.MONTHS.between(from.withDayOfMonth(1), d.withDayOfMonth(1));
                            int day = Math.min(from.getDayOfMonth(), d.lengthOfMonth());
                            return months % n == 0 && d.getDayOfMonth() == day;
                        default:
                            return false;
                    }
                }
            } catch (RuntimeException e) {
                return false;
            }
            return days.length() == 7 && days.charAt(d.getDayOfWeek().getValue() - 1) == '1';
        }

        /** The day the show running at this moment started on, or null when none is on. */
        public java.time.LocalDate occurrence(java.time.ZonedDateTime t) {
            if (!SCHEDULED.equals(type) || !enabled || days.length() != 7) {
                return null;
            }
            int m = t.getHour() * 60 + t.getMinute();
            java.time.LocalDate today = t.toLocalDate();
            if (start < end) {
                return m >= start && m < end && dayMatches(today) ? today : null;
            }
            if (m >= start) {
                return dayMatches(today) ? today : null;
            }
            return m < end && dayMatches(today.minusDays(1)) ? today.minusDays(1) : null;
        }

        /** The rule's words for "when" ("" for weekdays). */
        public String whenText() {
            String w = when == null ? "" : when;
            if (w.startsWith("month:")) {
                String v = w.substring(6);
                return "first".equals(v) ? "on the 1st of every month" : "last".equals(v) ? "on the last day of every month" : "on day " + v + " of every month";
            }
            if (w.startsWith("year:")) {
                return "every year on " + w.substring(5);
            }
            if (w.startsWith("once:")) {
                return "once, on " + w.substring(5);
            }
            if (w.startsWith("every:")) {
                String[] f = w.split(":");
                if (f.length == 4) {
                    boolean one = "1".equals(f[1]);
                    return "every " + (one ? "" : f[1] + " ") + f[2] + (one ? "" : "s") + " from " + f[3];
                }
            }
            return "";
        }

        /** A scheduled show that is on at this moment. */
        public boolean onAt(java.time.ZonedDateTime t) {
            return occurrence(t) != null;
        }

        /** A timed rotation playlist that is in its hours now (untimed ones always are). */
        public boolean inHours(java.time.ZonedDateTime t) {
            if (!timed || start == end % 1440) {
                return true;
            }
            int m = t.getHour() * 60 + t.getMinute();
            int e = end % 1440;
            return start < e ? m >= start && m < e : m >= start || m < e;
        }
    }

    /** Station staff: roles the super-admin gives a member account on someone else's station. */
    public static final String ROLE_COADMIN = "coadmin";
    public static final String ROLE_PRESENTER = "presenter";

    /** A member account working on a station (given by the super-admin). */
    public static final class Staff {
        public long station;
        public String user = "";
        public String role = ROLE_PRESENTER;
        public long added;

        Staff copy() {
            Staff c = new Staff();
            c.station = station;
            c.user = user;
            c.role = role;
            c.added = added;
            return c;
        }
    }

    /** One live show: who, when it started and ended. */
    public static final class Show {
        public long station;
        public String dj = "";
        public long start;
        public long end;
    }

    private final Path file;
    private final Path musicDir;
    private final Map<Long, Station> stations = new LinkedHashMap<Long, Station>();
    private final Map<Long, Track> tracks = new LinkedHashMap<Long, Track>();
    private final Map<Long, java.util.Set<String>> likers = new java.util.HashMap<Long, java.util.Set<String>>();
    private final Map<Long, Dj> djs = new LinkedHashMap<Long, Dj>();
    private final Map<Long, Playlist> playlists = new LinkedHashMap<Long, Playlist>();
    private long nextPlaylist = 1;
    private final List<Show> shows = new ArrayList<Show>();
    private final List<Staff> staff = new ArrayList<Staff>();
    private long nextStation = 1;
    private long nextTrack = 1;
    private long nextDj = 1;
    private boolean dirty = false;

    public RadioStore(Path file, Path musicDir) {
        this.file = file;
        this.musicDir = musicDir;
    }

    public synchronized void init() throws IOException {
        stations.clear();
        tracks.clear();
        likers.clear();
        djs.clear();
        shows.clear();
        staff.clear();
        nextDj = 1;
        playlists.clear();
        nextPlaylist = 1;
        nextStation = 1;
        nextTrack = 1;
        Files.createDirectories(musicDir);
        if (Files.exists(file)) {
            load();
        }
    }

    /** Text safe for the file: one line, no tabs, trimmed and limited. */
    public static String clean(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= 0x20 || c == ' ') {
                sb.append(c);
            }
        }
        t = sb.toString().replaceAll(" {2,}", " ");
        return t.length() > max ? t.substring(0, max).trim() : t;
    }

    // ---- Stations ----

    public synchronized List<Station> stations() {
        List<Station> out = new ArrayList<Station>();
        for (Station s : stations.values()) {
            out.add(s.copy());
        }
        return out;
    }

    public synchronized Station station(long id) {
        Station s = stations.get(id);
        return s == null ? null : s.copy();
    }

    public synchronized Station createStation(String name, String description, long now) throws IOException {
        return createStation(name, description, "", STATUS_ACTIVE, now);
    }

    /** A new station; owner "" = the platform's own. It gets a free live DJ port and a password. */
    public synchronized Station createStation(String name, String description, String owner, String status, long now)
            throws IOException {
        Station spec = new Station();
        spec.name = name;
        spec.description = description;
        return createStation(spec, owner, status, now);
    }

    /**
     * A new station from spec: name, description, website, livePort (0 = the first free one), livePass ("" = a new
     * one), billing and paidUntil.
     */
    public synchronized Station createStation(Station spec, String owner, String status, long now) throws IOException {
        String n = clean(spec.name, 40);
        if (n.isEmpty()) {
            throw new IllegalArgumentException("give the station a name");
        }
        for (Station s : stations.values()) {
            if (s.name.equalsIgnoreCase(n)) {
                throw new IllegalArgumentException("there is already a station called " + s.name);
            }
        }
        int port = spec.livePort;
        if (port == 0) {
            port = freePort(-1);
        } else {
            checkPort(port, -1);
        }
        String pass = spec.livePass == null || spec.livePass.trim().isEmpty() ? newLivePassword() : checkLivePass(spec.livePass);
        Station s = new Station();
        s.id = nextStation++;
        s.name = n;
        s.description = clean(spec.description, 160);
        s.website = checkWebsite(spec.website);
        s.created = now;
        s.owner = clean(owner, 40);
        s.status = validStatus(status) ? status : STATUS_PENDING;
        s.livePort = port;
        s.livePass = pass;
        s.billing = validBilling(spec.billing) ? spec.billing : BILL_PAID;
        s.paidUntil = Math.max(0, spec.paidUntil);
        s.trialUsed = BILL_TRIAL.equals(s.billing);
        stations.put(s.id, s);
        Files.createDirectories(musicDir.resolve(String.valueOf(s.id)));
        dirty = true;
        flushIfDirty();
        return s.copy();
    }

    public static boolean validBilling(String b) {
        return BILL_PAID.equals(b) || BILL_TRIAL.equals(b) || BILL_UNPAID.equals(b);
    }

    /** "" or an http(s) address. */
    public static String checkWebsite(String w) {
        String t = clean(w, 200);
        if (t.isEmpty()) {
            return "";
        }
        if (!t.matches("(?i)https?://.*")) {
            t = "https://" + t;
        }
        if (!t.matches("(?i)https?://[A-Za-z0-9.-]+\\.[A-Za-z]{2,}(:[0-9]{1,5})?(/[^\\s<>\"]*)?")) {
            throw new IllegalArgumentException("the website should look like https://example.com");
        }
        return t;
    }

    /** A live DJ password: 8 to 40 letters and digits. */
    public static String checkLivePass(String p) {
        String t = p == null ? "" : p.trim();
        if (!t.matches("[A-Za-z0-9]{8,40}")) {
            throw new IllegalArgumentException("the stream password needs 8 to 40 letters and numbers (no spaces or symbols)");
        }
        return t;
    }

    private void checkPort(int port, long except) {
        if (port < LIVE_PORT_MIN || port > LIVE_PORT_MAX) {
            throw new IllegalArgumentException("the port must be between " + LIVE_PORT_MIN + " and " + LIVE_PORT_MAX);
        }
        for (Station o : stations.values()) {
            if (o.id != except && o.livePort == port) {
                throw new IllegalArgumentException("port " + port + " is already used by another station");
            }
        }
    }

    /** The live DJ ports nobody uses yet. */
    public synchronized List<Integer> freePorts() {
        java.util.Set<Integer> used = new java.util.HashSet<Integer>();
        for (Station o : stations.values()) {
            used.add(o.livePort);
        }
        List<Integer> out = new ArrayList<Integer>();
        for (int p = LIVE_PORT_MIN; p <= LIVE_PORT_MAX; p++) {
            if (!used.contains(p)) {
                out.add(p);
            }
        }
        return out;
    }

    /** Admin (or a payment): how the station is paid for. until 0 = no end. */
    public synchronized Station setBilling(long id, String billing, long until, String plan) {
        Station s = stations.get(id);
        if (s == null) {
            throw new IllegalArgumentException("no such station");
        }
        if (!validBilling(billing)) {
            throw new IllegalArgumentException("payment must be paid, trial or unpaid");
        }
        s.billing = billing;
        s.paidUntil = Math.max(0, until);
        if (plan != null) {
            s.plan = plan;
        }
        if (BILL_TRIAL.equals(billing)) {
            s.trialUsed = true;
        }
        dirty = true;
        return s.copy();
    }

    /** A Stripe payment came through: paid until the end of the period, and on air. */
    public synchronized Station setStripe(long id, String customer, String sub, long until, String plan) {
        Station s = stations.get(id);
        if (s == null) {
            throw new IllegalArgumentException("no such station");
        }
        s.billing = BILL_PAID;
        s.paidUntil = Math.max(0, until);
        s.plan = plan == null ? s.plan : plan;
        s.stripeCustomer = customer == null ? "" : customer;
        s.stripeSub = sub == null ? "" : sub;
        if (STATUS_PENDING.equals(s.status)) {
            s.status = STATUS_ACTIVE;
        }
        dirty = true;
        return s.copy();
    }

    public synchronized void setReminded(long id, long forUntil) {
        Station s = stations.get(id);
        if (s != null) {
            s.reminded = forUntil;
            dirty = true;
        }
    }

    public synchronized Station setWebsite(long id, String website) {
        Station s = stations.get(id);
        if (s == null) {
            throw new IllegalArgumentException("no such station");
        }
        s.website = checkWebsite(website);
        dirty = true;
        return s.copy();
    }

    public synchronized Station setLivePassword(long id, String pass) {
        Station s = stations.get(id);
        if (s == null) {
            throw new IllegalArgumentException("no such station");
        }
        s.livePass = checkLivePass(pass);
        dirty = true;
        return s.copy();
    }

    /** Saves the editable fields of a station (name, description, DJ, voice, talk settings, on/off). */
    public synchronized Station updateStation(Station changes) {
        Station s = stations.get(changes.id);
        if (s == null) {
            throw new IllegalArgumentException("no such station");
        }
        String n = clean(changes.name, 40);
        if (n.isEmpty()) {
            throw new IllegalArgumentException("give the station a name");
        }
        for (Station o : stations.values()) {
            if (o.id != s.id && o.name.equalsIgnoreCase(n)) {
                throw new IllegalArgumentException("there is already a station called " + o.name);
            }
        }
        s.name = n;
        s.description = clean(changes.description, 160);
        s.djName = clean(changes.djName, 40).isEmpty() ? "Alma" : clean(changes.djName, 40);
        s.voice = clean(changes.voice, 80);
        s.enabled = changes.enabled;
        s.talkEvery = Math.max(0, Math.min(20, changes.talkEvery));
        s.newsOn = changes.newsOn;
        s.shoutOn = changes.shoutOn;
        s.introsOn = changes.introsOn;
        s.sayArtist = changes.sayArtist;
        s.storyEvery = Math.max(0, Math.min(20, changes.storyEvery));
        s.idEvery = Math.max(0, Math.min(20, changes.idEvery));
        s.msgEvery = Math.max(0, Math.min(20, changes.msgEvery));
        s.promo = clean(changes.promo, 200);
        dirty = true;
        return s.copy();
    }

    /** The admin switches the AI presenter on or off for a whole station. */
    public synchronized Station setAiOn(long id, boolean on) {
        Station s = stations.get(id);
        if (s == null) {
            throw new IllegalArgumentException("no such station");
        }
        s.aiOn = on;
        dirty = true;
        return s.copy();
    }

    public static boolean validStatus(String s) {
        return STATUS_ACTIVE.equals(s) || STATUS_PENDING.equals(s) || STATUS_SUSPENDED.equals(s);
    }

    /** 12 easy-to-type letters and digits (no 0/O, 1/l). */
    public static String newLivePassword() {
        String abc = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            sb.append(abc.charAt(RANDOM.nextInt(abc.length())));
        }
        return sb.toString();
    }

    /** The lowest live port in the range that no other station uses (0 if all are taken). */
    private int freePort(long except) {
        java.util.Set<Integer> used = new java.util.HashSet<Integer>();
        for (Station o : stations.values()) {
            if (o.id != except) {
                used.add(o.livePort);
            }
        }
        for (int p = LIVE_PORT_MIN; p <= LIVE_PORT_MAX; p++) {
            if (!used.contains(p)) {
                return p;
            }
        }
        return 0;
    }

    /** Admin only: who runs the station, its status and its live DJ port. */
    public synchronized Station setAdminFields(long id, String owner, String status, int livePort) {
        Station s = stations.get(id);
        if (s == null) {
            throw new IllegalArgumentException("no such station");
        }
        if (status != null) {
            if (!validStatus(status)) {
                throw new IllegalArgumentException("status must be active, pending or suspended");
            }
            s.status = status;
        }
        if (owner != null) {
            s.owner = clean(owner, 40);
        }
        if (livePort != 0 && livePort != s.livePort) {
            if (livePort < LIVE_PORT_MIN || livePort > LIVE_PORT_MAX) {
                throw new IllegalArgumentException("the live port must be between " + LIVE_PORT_MIN + " and " + LIVE_PORT_MAX
                        + " (the range the firewall opens)");
            }
            for (Station o : stations.values()) {
                if (o.id != id && o.livePort == livePort) {
                    throw new IllegalArgumentException("port " + livePort + " is used by " + o.name);
                }
            }
            s.livePort = livePort;
        }
        dirty = true;
        return s.copy();
    }

    /** A new password for the live DJ connection. */
    public synchronized String resetLivePassword(long id) {
        Station s = stations.get(id);
        if (s == null) {
            throw new IllegalArgumentException("no such station");
        }
        s.livePass = newLivePassword();
        dirty = true;
        return s.livePass;
    }

    // ---- Plans, time zone, data used ----

    public synchronized Station setPlan(long id, int planId) {
        Station s = stations.get(id);
        if (s == null) {
            throw new IllegalArgumentException("no such station");
        }
        s.planId = Math.max(0, planId);
        dirty = true;
        return s.copy();
    }

    public synchronized Station setTimezone(long id, String zone) {
        Station s = stations.get(id);
        if (s == null) {
            throw new IllegalArgumentException("no such station");
        }
        String z = zone == null ? "" : zone.trim();
        try {
            java.time.ZoneId.of(z);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("unknown time zone: " + z + " (e.g. Africa/Nairobi, America/New_York, UTC)");
        }
        s.timezone = z;
        dirty = true;
        return s.copy();
    }

    /** Adds data sent to listeners; a new month starts from 0. Returns the month's total. */
    public synchronized long addTransfer(long id, long bytes, String month) {
        Station s = stations.get(id);
        if (s == null) {
            return 0;
        }
        if (!month.equals(s.monthKey)) {
            s.monthKey = month;
            s.monthBytes = 0;
        }
        s.monthBytes += Math.max(0, bytes);
        dirty = true;
        return s.monthBytes;
    }

    public synchronized void setXferWarned(long id, String mark) {
        Station s = stations.get(id);
        if (s != null) {
            s.xferWarned = mark;
            dirty = true;
        }
    }

    /** Bytes of music stored for the station. */
    public synchronized long storageBytes(long id) {
        long n = 0;
        Path dir = musicDir.resolve(String.valueOf(id));
        for (Track t : tracks.values()) {
            if (t.station == id) {
                try {
                    n += Files.size(dir.resolve(t.file));
                } catch (IOException e) {
                    // missing file
                }
            }
        }
        return n;
    }

    // ---- Playlists ----

    public synchronized List<Playlist> playlists(long station) {
        List<Playlist> out = new ArrayList<Playlist>();
        for (Playlist p : playlists.values()) {
            if (p.station == station) {
                Playlist c = p.copy();
                c.tracks.removeIf(id -> !tracks.containsKey(id));
                out.add(c);
            }
        }
        return out;
    }

    public synchronized Playlist playlist(long id) {
        Playlist p = playlists.get(id);
        if (p == null) {
            return null;
        }
        Playlist c = p.copy();
        c.tracks.removeIf(t -> !tracks.containsKey(t));
        return c;
    }

    /** Adds (id 0) or changes a playlist; checks every field. */
    public synchronized Playlist savePlaylist(Playlist in, long now) {
        if (!stations.containsKey(in.station)) {
            throw new IllegalArgumentException("no such station");
        }
        String n = clean(in.name, 40);
        if (n.isEmpty()) {
            throw new IllegalArgumentException("give the playlist a name");
        }
        if (!Playlist.GENERAL.equals(in.type) && !Playlist.SCHEDULED.equals(in.type) && !Playlist.INTERVAL.equals(in.type)
                && !Playlist.IMMEDIATE.equals(in.type)) {
            throw new IllegalArgumentException("unknown playlist type");
        }
        if (in.weight < 0 || in.weight > 10) {
            throw new IllegalArgumentException("weight is 0 to 10");
        }
        if (Playlist.SCHEDULED.equals(in.type)) {
            if (in.days == null || !in.days.matches("[01]{7}") || !in.days.contains("1")) {
                throw new IllegalArgumentException("pick at least one day for the show");
            }
            if (in.start < 0 || in.start > 1439 || in.end < 0 || in.end > 1440 || in.start == in.end % 1440) {
                throw new IllegalArgumentException("the show needs a start and a different end time");
            }
        }
        if (Playlist.GENERAL.equals(in.type) && in.timed && (in.start < 0 || in.start > 1439 || in.end < 0 || in.end > 1440
                || in.start == in.end % 1440)) {
            throw new IllegalArgumentException("the rotation hours need a start and a different end time");
        }
        String when = in.when == null ? "" : in.when.trim();
        if (!when.isEmpty() && !when.matches("month:([1-9]|[12][0-9]|3[01]|first|last)|year:(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])|once:20[0-9]{2}-[01][0-9]-[0-3][0-9]|every:([1-9]|[1-9][0-9]|[1-3][0-9][0-9]):(day|week|month):20[0-9]{2}-[01][0-9]-[0-3][0-9]")) {
            throw new IllegalArgumentException("unknown show date rule");
        }
        String style = in.style == null ? "" : in.style;
        if (!(style.isEmpty() || Playlist.INTERVAL.equals(in.type) && "all".equals(style)
                || Playlist.SCHEDULED.equals(in.type) && ("once".equals(style) || "one".equals(style)))) {
            style = "";
        }
        boolean minutes = "minutes".equals(in.unit);
        if (Playlist.INTERVAL.equals(in.type) && !minutes && (in.every < 1 || in.every > 50)) {
            throw new IllegalArgumentException("every 1 to 50 songs");
        }
        if (Playlist.INTERVAL.equals(in.type) && minutes && (in.every < 1 || in.every > 10080)) {
            throw new IllegalArgumentException("every 1 minute to 168 hours (a week)");
        }
        List<Long> ids = new ArrayList<Long>();
        for (Long t : in.tracks) {
            Track tr = tracks.get(t);
            if (tr != null && tr.station == in.station && !ids.contains(t)) {
                ids.add(t);
            }
        }
        if (ids.size() > 5000) {
            throw new IllegalArgumentException("5000 songs at most in one playlist");
        }
        Playlist p = in.id == 0 ? null : playlists.get(in.id);
        if (in.id != 0 && (p == null || p.station != in.station)) {
            throw new IllegalArgumentException("no such playlist");
        }
        if (p == null) {
            int have = 0;
            for (Playlist o : playlists.values()) {
                have += o.station == in.station ? 1 : 0;
            }
            if (have >= 100) {
                throw new IllegalArgumentException("100 playlists at most per station");
            }
            p = new Playlist();
            p.id = nextPlaylist++;
            p.station = in.station;
            p.created = now;
            playlists.put(p.id, p);
        }
        p.name = n;
        p.type = in.type;
        p.enabled = in.enabled;
        p.weight = in.weight;
        p.sequential = in.sequential;
        p.days = in.days == null || !in.days.matches("[01]{7}") ? "1111111" : in.days;
        p.start = in.start;
        p.end = in.end % 1440 == in.end ? in.end : 1440;
        p.every = in.every;
        p.unit = minutes ? "minutes" : "songs";
        p.style = style;
        p.timed = Playlist.GENERAL.equals(in.type) && in.timed;
        p.inShows = in.inShows;
        p.when = Playlist.SCHEDULED.equals(in.type) ? when : "";
        p.interrupt = "no".equals(in.interrupt) ? "no" : "yes";
        p.djOverride = in.djOverride;
        p.ai = in.ai;
        p.studio = in.studio;
        p.tracks = ids;
        dirty = true;
        return playlist(p.id);
    }

    public synchronized boolean deletePlaylist(long station, long id) {
        Playlist p = playlists.get(id);
        if (p == null || p.station != station) {
            return false;
        }
        playlists.remove(id);
        dirty = true;
        return true;
    }

    /** Puts a song into a playlist (e.g. new uploads). */
    public synchronized void addToPlaylist(long playlistId, long trackId) {
        Playlist p = playlists.get(playlistId);
        Track t = tracks.get(trackId);
        if (p != null && t != null && t.station == p.station && !p.tracks.contains(trackId)) {
            p.tracks.add(trackId);
            dirty = true;
        }
    }

    /** The admin's own limits for one station (0 = the plan's, -1 = no limit). */
    public synchronized Station setLimits(long id, int listeners, int kbps, int storageMb, int transferGb, int djs) {
        Station s = stations.get(id);
        if (s == null) {
            throw new IllegalArgumentException("no such station");
        }
        if (listeners < -1 || listeners > 100000 || storageMb < -1 || storageMb > 10000000 || transferGb < -1 || transferGb > 1000000
                || djs < -1 || djs > 3) {
            throw new IllegalArgumentException("a limit is out of range");
        }
        if (kbps != 0 && kbps != 32 && kbps != 48 && kbps != 64 && kbps != 96 && kbps != 128 && kbps != 160 && kbps != 192
                && kbps != 256 && kbps != 320) {
            throw new IllegalArgumentException("bitrate: 32, 48, 64, 96, 128, 160, 192, 256 or 320 kbps");
        }
        s.limListeners = listeners;
        s.limKbps = kbps;
        s.limStorageMb = storageMb;
        s.limTransferGb = transferGb;
        s.limDjs = djs;
        dirty = true;
        return s.copy();
    }

    public synchronized Station setContact(long id, String company, String email) {
        Station s = stations.get(id);
        if (s == null) {
            throw new IllegalArgumentException("no such station");
        }
        String e = clean(email, 120);
        if (!e.isEmpty() && !e.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw new IllegalArgumentException("that e-mail address does not look right");
        }
        s.company = clean(company, 80);
        s.email = e;
        dirty = true;
        return s.copy();
    }

    /** Switches a playlist on or off (an Immediate playlist switches itself off when it has played). */
    public synchronized boolean setPlaylistEnabled(long id, boolean on) {
        Playlist p = playlists.get(id);
        if (p == null) {
            return false;
        }
        if (p.enabled != on) {
            p.enabled = on;
            dirty = true;
        }
        return true;
    }

    public synchronized Station setNoRepeat(long id, int minutes) {
        Station s = stations.get(id);
        if (s == null) {
            throw new IllegalArgumentException("no such station");
        }
        if (minutes < 0 || minutes > 1440) {
            throw new IllegalArgumentException("no repeat: 0 to 1440 minutes");
        }
        s.noRepeatMin = minutes;
        dirty = true;
        return s.copy();
    }

    // ---- Remote DJs ----

    public synchronized List<Dj> djs(long station) {
        List<Dj> out = new ArrayList<Dj>();
        for (Dj d : djs.values()) {
            if (d.station == station) {
                out.add(d.copy());
            }
        }
        return out;
    }

    public synchronized Dj dj(long id) {
        Dj d = djs.get(id);
        return d == null ? null : d.copy();
    }

    /** Adds (id 0) or changes a DJ. max = how many DJs the station's plan allows. */
    public synchronized Dj saveDj(Dj in, int max, long now) {
        Station st = stations.get(in.station);
        if (st == null) {
            throw new IllegalArgumentException("no such station");
        }
        String name = clean(in.name, 40);
        if (name.isEmpty()) {
            throw new IllegalArgumentException("give the DJ a name");
        }
        String login = in.login == null ? "" : in.login.trim().toLowerCase(Locale.ROOT);
        if (!login.matches("[a-z0-9_.-]{3,30}")) {
            throw new IllegalArgumentException("the DJ login needs 3 to 30 letters, numbers, dots, dashes or underscores");
        }
        if (login.equals("source") || login.equals("admin")) {
            throw new IllegalArgumentException("choose another login - \"" + login + "\" is reserved");
        }
        String pass = checkLivePass(in.password);
        if (in.days == null || !in.days.matches("[01]{7}") || !in.days.contains("1")) {
            throw new IllegalArgumentException("pick at least one day");
        }
        if (in.from < 0 || in.from > 23 || in.to < 1 || in.to > 24) {
            throw new IllegalArgumentException("hours run from 0 to 24");
        }
        for (Dj o : djs.values()) {
            if (o.station == in.station && o.id != in.id && o.login.equals(login)) {
                throw new IllegalArgumentException("another DJ of this station already uses the login " + login);
            }
        }
        Dj d = in.id == 0 ? null : djs.get(in.id);
        if (in.id != 0 && (d == null || d.station != in.station)) {
            throw new IllegalArgumentException("no such DJ");
        }
        if (d == null) {
            int have = 0;
            for (Dj o : djs.values()) {
                have += o.station == in.station ? 1 : 0;
            }
            if (have >= max) {
                throw new IllegalArgumentException(max == 0 ? "this station's plan has no remote DJs - upgrade the plan to add DJs"
                        : "this station's plan allows " + max + " remote DJ" + (max == 1 ? "" : "s"));
            }
            d = new Dj();
            d.id = nextDj++;
            d.station = in.station;
            d.created = now;
            djs.put(d.id, d);
        }
        d.name = name;
        d.login = login;
        d.password = pass;
        d.enabled = in.enabled;
        d.days = in.days;
        d.from = in.from;
        d.to = in.to;
        d.studio = in.studio;
        dirty = true;
        return d.copy();
    }

    public synchronized boolean deleteDj(long station, long id) {
        Dj d = djs.get(id);
        if (d == null || d.station != station) {
            return false;
        }
        djs.remove(id);
        dirty = true;
        return true;
    }

    /** The DJ with this login and password (any case login), or null. */
    public synchronized Dj djLogin(long station, String login, String password) {
        if (login == null || password == null) {
            return null;
        }
        String l = login.trim().toLowerCase(Locale.ROOT);
        for (Dj d : djs.values()) {
            if (d.station == station && d.login.equals(l)
                    && java.security.MessageDigest.isEqual(d.password.getBytes(StandardCharsets.UTF_8),
                    password.getBytes(StandardCharsets.UTF_8))) {
                return d.copy();
            }
        }
        return null;
    }

    /** A live show ended (kept: the last 30 per station). */
    public synchronized void addShow(long station, String dj, long start, long end, long djId) {
        Show sh = new Show();
        sh.station = station;
        sh.dj = clean(dj, 60);
        sh.start = start;
        sh.end = end;
        shows.add(sh);
        int n = 0;
        for (int i = shows.size() - 1; i >= 0; i--) {
            if (shows.get(i).station == station && ++n > 30) {
                shows.remove(i);
            }
        }
        Dj d = djs.get(djId);
        if (d != null) {
            d.lastLive = end;
        }
        dirty = true;
    }

    /** The station's recent shows, newest first. */
    public synchronized List<Show> shows(long station) {
        List<Show> out = new ArrayList<Show>();
        for (int i = shows.size() - 1; i >= 0; i--) {
            if (shows.get(i).station == station) {
                out.add(shows.get(i));
            }
        }
        return out;
    }

    /** Stations run by this account. */
    public synchronized List<Station> ownedBy(String user) {
        List<Station> out = new ArrayList<Station>();
        for (Station s : stations.values()) {
            if (!s.owner.isEmpty() && s.owner.equalsIgnoreCase(user)) {
                out.add(s.copy());
            }
        }
        return out;
    }

    // ---- Station staff (the super-admin gives a member a role on a station) ----

    public static boolean validRole(String role) {
        return ROLE_COADMIN.equals(role) || ROLE_PRESENTER.equals(role);
    }

    /** Adds or changes a member's role on a station. */
    public synchronized Staff setStaff(long station, String user, String role, long now) {
        if (!stations.containsKey(station) || !validRole(role)) {
            return null;
        }
        String u = clean(user, 40);
        for (Staff f : staff) {
            if (f.station == station && f.user.equalsIgnoreCase(u)) {
                f.role = role;
                dirty = true;
                return f.copy();
            }
        }
        Staff f = new Staff();
        f.station = station;
        f.user = u;
        f.role = role;
        f.added = now;
        staff.add(f);
        dirty = true;
        return f.copy();
    }

    public synchronized boolean removeStaff(long station, String user) {
        String u = user == null ? "" : user.trim();
        boolean gone = staff.removeIf(f -> f.station == station && f.user.equalsIgnoreCase(u));
        dirty |= gone;
        return gone;
    }

    /** The station's staff (station 0 = every station's). */
    public synchronized List<Staff> staffOf(long station) {
        List<Staff> out = new ArrayList<Staff>();
        for (Staff f : staff) {
            if (station == 0 || f.station == station) {
                out.add(f.copy());
            }
        }
        return out;
    }

    /** The member's role on the station, or null. */
    public synchronized String staffRole(long station, String user) {
        for (Staff f : staff) {
            if (f.station == station && user != null && f.user.equalsIgnoreCase(user)) {
                return f.role;
            }
        }
        return null;
    }

    /** Stations this account owns or works on as staff (owned first). */
    public synchronized List<Station> manageableBy(String user) {
        List<Station> out = ownedBy(user);
        for (Staff f : staff) {
            Station s = stations.get(f.station);
            if (s != null && f.user.equalsIgnoreCase(user) && !s.owner.equalsIgnoreCase(user)) {
                out.add(s.copy());
            }
        }
        return out;
    }

    /** Deletes a station with all its songs (the files too). */
    public synchronized boolean deleteStation(long id) {
        Station s = stations.remove(id);
        if (s == null) {
            return false;
        }
        for (Track t : new ArrayList<Track>(tracks.values())) {
            if (t.station == id) {
                removeTrack(t);
            }
        }
        djs.values().removeIf(d -> d.station == id);
        playlists.values().removeIf(p -> p.station == id);
        shows.removeIf(sh -> sh.station == id);
        staff.removeIf(f -> f.station == id);
        try {
            Files.deleteIfExists(musicDir.resolve(String.valueOf(id)));
        } catch (IOException e) {
            // a file was left in the folder; harmless
        }
        dirty = true;
        return true;
    }

    // ---- The try-it station: a saved starting point it goes back to ----

    /**
     * Saves one station as it is now - settings, songs (with copies of the files), playlists and DJ logins - in dir
     * (dir/radio.txt and dir/music/ID/...). restoreStation puts it back exactly like this. Returns the songs' bytes.
     */
    public synchronized long exportStation(long id, Path dir) throws IOException {
        Station s = stations.get(id);
        if (s == null) {
            throw new IllegalArgumentException("no such station");
        }
        RadioStore out = new RadioStore(dir.resolve("radio.txt"), dir.resolve("music"));
        Files.createDirectories(out.musicDir.resolve(String.valueOf(id)));
        out.stations.put(id, s.copy());
        long bytes = 0;
        for (Track t : tracks.values()) {
            if (t.station != id) {
                continue;
            }
            Path from = pathOf(t);
            if (!Files.isRegularFile(from)) {
                continue; // the file is gone: the song cannot come back
            }
            Files.copy(from, out.pathOf(t), StandardCopyOption.REPLACE_EXISTING);
            bytes += Files.size(from);
            out.tracks.put(t.id, t.copy());
            java.util.Set<String> l = likers.get(t.id);
            if (l != null) {
                out.likers.put(t.id, new java.util.HashSet<String>(l));
            }
        }
        for (Playlist p : playlists.values()) {
            if (p.station == id) {
                out.playlists.put(p.id, p.copy());
            }
        }
        for (Dj d : djs.values()) {
            if (d.station == id) {
                out.djs.put(d.id, d.copy());
            }
        }
        out.save();
        return bytes;
    }

    /**
     * Puts a station back the way exportStation saved it: songs added since are deleted (with their files), deleted
     * ones come back, edited ones are as they were; the same for playlists and DJ logins; the settings too. What
     * listeners used this month stays. Returns a short line on what changed.
     */
    public synchronized String restoreStation(long id, Path dir) throws IOException {
        Station cur = stations.get(id);
        if (cur == null) {
            throw new IllegalArgumentException("no such station");
        }
        RadioStore saved = new RadioStore(dir.resolve("radio.txt"), dir.resolve("music"));
        if (!Files.isRegularFile(saved.file)) {
            throw new IllegalArgumentException("no starting point was saved");
        }
        saved.load();
        Station was = saved.stations.get(id);
        if (was == null) {
            throw new IllegalArgumentException("the saved starting point is for another station");
        }
        Station back = was.copy();
        back.monthKey = cur.monthKey;
        back.monthBytes = cur.monthBytes;
        back.xferWarned = cur.xferWarned;
        for (Station o : stations.values()) {
            if (o.id != id && o.name.equalsIgnoreCase(back.name)) {
                back.name = cur.name; // another station took the name meanwhile
            }
        }
        stations.put(id, back);
        // songs
        java.util.Set<Long> keep = new java.util.HashSet<Long>();
        Map<Long, Long> newId = new java.util.HashMap<Long, Long>();
        int removed = 0, returned = 0;
        Path dirOf = musicDir.resolve(String.valueOf(id));
        Files.createDirectories(dirOf);
        for (Track t : saved.tracks.values()) {
            Track other = tracks.get(t.id);
            long tid = t.id;
            if (other != null && other.station != id) {
                tid = nextTrack++; // the number was given to another station's song after a restart
            }
            newId.put(t.id, tid);
            keep.add(tid);
            Track now = tracks.get(tid);
            Path src = saved.pathOf(t);
            Track put = t.copy();
            put.id = tid;
            if (tid != t.id) {
                int dot = t.file.lastIndexOf('.');
                put.file = "song-" + tid + (dot < 0 ? "" : t.file.substring(dot));
            }
            Path dst = dirOf.resolve(put.file);
            boolean same = now != null && now.file.equals(put.file) && Files.isRegularFile(dst)
                    && Files.isRegularFile(src) && Files.size(dst) == Files.size(src);
            if (!same && Files.isRegularFile(src)) {
                Path part = dirOf.resolve(put.file + ".part");
                Files.copy(src, part, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(part, dst, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    Files.deleteIfExists(part); // the old file is open (on air): it stays, it is the same song
                }
            }
            if (now == null) {
                returned++;
            } else if (!now.file.equals(put.file)) {
                Path old = pathOf(now);
                try {
                    Files.deleteIfExists(old);
                } catch (IOException e) {
                    old.toFile().deleteOnExit();
                }
            }
            put.station = id;
            tracks.put(tid, put);
            java.util.Set<String> l = saved.likers.get(t.id);
            if (l == null) {
                likers.remove(tid);
            } else {
                likers.put(tid, new java.util.HashSet<String>(l));
            }
            put.likes = l == null ? 0 : l.size();
            nextTrack = Math.max(nextTrack, tid + 1);
        }
        for (Track t : new ArrayList<Track>(tracks.values())) {
            if (t.station == id && !keep.contains(t.id)) {
                Path f = pathOf(t);
                tracks.remove(t.id);
                likers.remove(t.id);
                try {
                    Files.deleteIfExists(f);
                } catch (IOException e) {
                    f.toFile().deleteOnExit(); // on air right now
                }
                removed++;
            }
        }
        // anything else left in the folder (half uploads)
        java.util.Set<String> files = new java.util.HashSet<String>();
        for (Track t : tracks.values()) {
            if (t.station == id) {
                files.add(t.file);
            }
        }
        try (java.util.stream.Stream<Path> list = Files.list(dirOf)) {
            for (Path f : (Iterable<Path>) list::iterator) {
                if (Files.isRegularFile(f) && !files.contains(f.getFileName().toString())) {
                    try {
                        Files.deleteIfExists(f);
                    } catch (IOException e) {
                        f.toFile().deleteOnExit();
                    }
                }
            }
        }
        // playlists and DJ logins
        playlists.values().removeIf(p -> p.station == id);
        for (Playlist p : saved.playlists.values()) {
            Playlist put = p.copy();
            Playlist other = playlists.get(p.id);
            if (other != null) {
                put.id = nextPlaylist++;
            }
            List<Long> ids = new ArrayList<Long>();
            for (Long t : p.tracks) {
                Long n = newId.get(t);
                if (n != null) {
                    ids.add(n);
                }
            }
            put.tracks = ids;
            put.station = id;
            playlists.put(put.id, put);
            nextPlaylist = Math.max(nextPlaylist, put.id + 1);
        }
        djs.values().removeIf(d -> d.station == id);
        for (Dj d : saved.djs.values()) {
            Dj put = d.copy();
            if (djs.containsKey(d.id)) {
                put.id = nextDj++;
            }
            put.station = id;
            djs.put(put.id, put);
            nextDj = Math.max(nextDj, put.id + 1);
        }
        shows.removeIf(sh -> sh.station == id);
        dirty = true;
        flushIfDirty();
        return removed + " song(s) removed, " + returned + " brought back";
    }

    /** The size of the songs saved in a starting point (dir from exportStation). */
    public static long exportedBytes(Path dir) {
        final long[] n = {0};
        Path m = dir.resolve("music");
        if (!Files.isDirectory(m)) {
            return 0;
        }
        try (java.util.stream.Stream<Path> w = Files.walk(m)) {
            w.filter(Files::isRegularFile).forEach(f -> {
                try {
                    n[0] += Files.size(f);
                } catch (IOException e) {
                    // skip
                }
            });
        } catch (IOException e) {
            // skip
        }
        return n[0];
    }

    // ---- Songs ----

    public synchronized List<Track> tracks(long station) {
        List<Track> out = new ArrayList<Track>();
        for (Track t : tracks.values()) {
            if (t.station == station) {
                out.add(t.copy());
            }
        }
        return out;
    }

    public synchronized int trackCount(long station) {
        int n = 0;
        for (Track t : tracks.values()) {
            if (t.station == station) {
                n++;
            }
        }
        return n;
    }

    public synchronized Track track(long id) {
        Track t = tracks.get(id);
        return t == null ? null : t.copy();
    }

    /** Where a song's file is on disk. */
    public Path pathOf(Track t) {
        return musicDir.resolve(String.valueOf(t.station)).resolve(t.file);
    }

    /** The file extension of an uploaded name, if it is a supported sound type; otherwise null. */
    public static String audioType(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        String ext = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return AUDIO_TYPES.contains(ext) ? ext : null;
    }

    /** Saves an uploaded song; title and artist come from its tags (or the file name). */
    public synchronized Track addTrack(long station, String originalName, String ext, byte[] data, String title,
                                       String artist, String album, long durationMs, long now) throws IOException {
        if (!stations.containsKey(station)) {
            throw new IllegalArgumentException("no such station");
        }
        Track t = new Track();
        t.id = nextTrack++;
        t.station = station;
        t.file = "song-" + t.id + "." + ext;
        Path dir = musicDir.resolve(String.valueOf(station));
        Files.createDirectories(dir);
        Files.write(dir.resolve(t.file), data);
        String fallback = originalName == null ? "" : originalName.replaceAll("\\.[A-Za-z0-9]{2,4}$", "")
                .replace('_', ' ');
        String a = clean(artist, 80);
        String ti = clean(title, 120);
        if (ti.isEmpty()) {
            // "Artist - Title.mp3" is a common way to name files
            int dash = fallback.indexOf(" - ");
            if (dash > 0 && a.isEmpty()) {
                a = clean(fallback.substring(0, dash), 80);
                ti = clean(fallback.substring(dash + 3), 120);
            } else {
                ti = clean(fallback, 120);
            }
        }
        t.title = ti.isEmpty() ? "Untitled" : ti;
        t.artist = a;
        t.album = clean(album, 120);
        t.durationMs = Math.max(0, durationMs);
        t.added = now;
        tracks.put(t.id, t);
        dirty = true;
        flushIfDirty();
        return t.copy();
    }

    /**
     * Puts a new file in place of a song's sound (same song: its playlists, likes and statistics stay).
     * Returns the song, or null when there is no such song.
     */
    public synchronized Track replaceTrackFile(long id, String ext, byte[] data, long durationMs) throws IOException {
        Track t = tracks.get(id);
        if (t == null || !AUDIO_TYPES.contains(ext)) {
            return null;
        }
        Path old = pathOf(t);
        // a new file name: the old file may be open right now (on air), and Windows does not let an open file be replaced
        String file = "song-" + t.id + "-" + Long.toString(System.currentTimeMillis(), 36) + "." + ext;
        Path dir = musicDir.resolve(String.valueOf(t.station));
        Files.createDirectories(dir);
        Path part = dir.resolve(file + ".part");
        Files.write(part, data);
        Files.move(part, dir.resolve(file), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        t.file = file;
        if (durationMs > 0) {
            t.durationMs = durationMs;
        }
        dirty = true;
        flushIfDirty();
        try {
            Files.deleteIfExists(old);
        } catch (IOException e) {
            old.toFile().deleteOnExit(); // still playing: removed when the server restarts
        }
        return t.copy();
    }

    /** Puts a song's own file in place of the stored copy (used when a file is converted). */
    public synchronized void setDuration(long id, long durationMs) {
        Track t = tracks.get(id);
        if (t != null && durationMs > 0) {
            t.durationMs = durationMs;
            dirty = true;
        }
    }

    public synchronized Track editTrack(long id, String title, String artist) {
        return editTrack(id, title, artist, null);
    }

    /** Changes title, artist and link (null = keep). Links must start with http:// or https://. */
    public synchronized Track editTrack(long id, String title, String artist, String link) {
        Track t = tracks.get(id);
        if (t == null) {
            throw new IllegalArgumentException("no such song");
        }
        String ti = clean(title, 120);
        if (!ti.isEmpty()) {
            t.title = ti;
        }
        if (artist != null) {
            t.artist = clean(artist, 80);
        }
        if (link != null) {
            String l = clean(link, 300);
            if (!l.isEmpty() && !l.matches("(?i)https?://\\S+")) {
                throw new IllegalArgumentException("the link must start with https:// (for example https://open.spotify.com/...)");
            }
            t.link = l;
        }
        dirty = true;
        return t.copy();
    }

    /** Likes or unlikes a song for a person. Returns the new number of likes. */
    public synchronized int like(long id, String user, boolean like) {
        Track t = tracks.get(id);
        if (t == null) {
            throw new IllegalArgumentException("no such song");
        }
        java.util.Set<String> set = likers.computeIfAbsent(id, k -> new java.util.HashSet<String>());
        String u = user.toLowerCase(Locale.ROOT);
        boolean changed = like ? set.add(u) : set.remove(u);
        t.likes = set.size();
        if (changed) {
            dirty = true;
        }
        return t.likes;
    }

    public synchronized boolean likes(long id, String user) {
        java.util.Set<String> set = likers.get(id);
        return set != null && user != null && set.contains(user.toLowerCase(Locale.ROOT));
    }

    /** Counts a tap on "Get this song" and returns the link (or ""). */
    public synchronized String tap(long id) {
        Track t = tracks.get(id);
        if (t == null) {
            return "";
        }
        if (!t.link.isEmpty()) {
            t.taps++;
            dirty = true;
        }
        return t.link;
    }

    public synchronized void countRequest(long id) {
        Track t = tracks.get(id);
        if (t != null) {
            t.requests++;
            dirty = true;
        }
    }

    public synchronized boolean deleteTrack(long id) {
        Track t = tracks.get(id);
        if (t == null) {
            return false;
        }
        removeTrack(t);
        dirty = true;
        return true;
    }

    private void removeTrack(Track t) {
        tracks.remove(t.id);
        likers.remove(t.id);
        try {
            Files.deleteIfExists(pathOf(t));
        } catch (IOException e) {
            // left behind; harmless
        }
    }

    public synchronized void countPlay(long id, long now) {
        Track t = tracks.get(id);
        if (t != null) {
            t.plays++;
            t.lastPlayed = now;
            dirty = true;
        }
    }

    // ---- Storage ----

    private void load() throws IOException {
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            try {
                if ("S".equals(f[0]) && f.length >= 13) {
                    Station s = new Station();
                    s.id = Long.parseLong(f[1]);
                    s.name = f[2];
                    s.description = f[3];
                    s.djName = f[4];
                    s.voice = f[5];
                    s.enabled = "1".equals(f[6]);
                    s.talkEvery = Integer.parseInt(f[7]);
                    s.newsOn = "1".equals(f[8]);
                    s.shoutOn = "1".equals(f[9]);
                    s.introsOn = "1".equals(f[10]);
                    s.created = Long.parseLong(f[11]);
                    if ("DJ VStream".equals(s.djName) || "DJ Memoryq".equals(s.djName)) {
                        // the presenter is Alma
                        s.djName = "Alma";
                        dirty = true;
                    }
                    if (f.length >= 14) {
                        s.promo = f[12];
                    }
                    if (f.length >= 18) {
                        s.owner = f[13];
                        s.status = validStatus(f[14]) ? f[14] : STATUS_ACTIVE;
                        s.livePort = Integer.parseInt(f[15]);
                        s.livePass = f[16];
                    }
                    if (f.length >= 26) {
                        s.website = f[17];
                        s.billing = validBilling(f[18]) ? f[18] : BILL_PAID;
                        s.paidUntil = Long.parseLong(f[19]);
                        s.plan = f[20];
                        s.stripeCustomer = f[21];
                        s.stripeSub = f[22];
                        s.trialUsed = "1".equals(f[23]);
                        s.reminded = Long.parseLong(f[24]);
                    }
                    if (f.length >= 32) {
                        s.planId = Integer.parseInt(f[25]);
                        s.timezone = f[26].isEmpty() ? "UTC" : f[26];
                        s.monthKey = f[27];
                        s.monthBytes = Long.parseLong(f[28]);
                        s.xferWarned = f[29];
                        if (f[30].matches("\\d{1,4}")) {
                            s.noRepeatMin = Integer.parseInt(f[30]);
                        }
                    }
                    if (f.length >= 35) {
                        String[] lim = f[31].split(",");
                        if (lim.length == 5) {
                            s.limListeners = Integer.parseInt(lim[0]);
                            s.limKbps = Integer.parseInt(lim[1]);
                            s.limStorageMb = Integer.parseInt(lim[2]);
                            s.limTransferGb = Integer.parseInt(lim[3]);
                            s.limDjs = Integer.parseInt(lim[4]);
                        }
                        s.company = f[32];
                        s.email = f[33];
                    }
                    if (f.length >= 38) {
                        s.sayArtist = "1".equals(f[34]);
                        s.storyEvery = Integer.parseInt(f[35]);
                        s.idEvery = Integer.parseInt(f[36]);
                    }
                    if (f.length >= 39) {
                        s.aiOn = !"0".equals(f[37]);
                    }
                    if (f.length >= 40) {
                        s.msgEvery = Integer.parseInt(f[38]);
                    }
                    stations.put(s.id, s);
                    nextStation = Math.max(nextStation, s.id + 1);
                } else if ("T".equals(f[0]) && f.length >= 12) {
                    Track t = new Track();
                    t.id = Long.parseLong(f[1]);
                    t.station = Long.parseLong(f[2]);
                    t.file = f[3];
                    t.title = f[4];
                    t.artist = f[5];
                    t.album = f[6];
                    t.durationMs = Long.parseLong(f[7]);
                    t.added = Long.parseLong(f[8]);
                    t.plays = Long.parseLong(f[9]);
                    t.lastPlayed = Long.parseLong(f[10]);
                    if (f.length >= 15) {
                        t.link = f[11];
                        t.taps = Long.parseLong(f[12]);
                        t.requests = Long.parseLong(f[13]);
                    }
                    tracks.put(t.id, t);
                    nextTrack = Math.max(nextTrack, t.id + 1);
                } else if ("L".equals(f[0]) && f.length >= 3) {
                    likers.computeIfAbsent(Long.parseLong(f[1]), k -> new java.util.HashSet<String>()).add(f[2]);
                } else if ("J".equals(f[0]) && f.length >= 13) {
                    Dj d = new Dj();
                    d.id = Long.parseLong(f[1]);
                    d.station = Long.parseLong(f[2]);
                    d.name = f[3];
                    d.login = f[4];
                    d.password = f[5];
                    d.enabled = "1".equals(f[6]);
                    d.days = f[7];
                    d.from = Integer.parseInt(f[8]);
                    d.to = Integer.parseInt(f[9]);
                    d.created = Long.parseLong(f[10]);
                    d.lastLive = Long.parseLong(f[11]);
                    // DJs saved before this setting keep the access they had; new ones get it when the admin ticks it
                    d.studio = f.length < 14 || "1".equals(f[12]);
                    djs.put(d.id, d);
                    nextDj = Math.max(nextDj, d.id + 1);
                } else if ("Q".equals(f[0]) && f.length >= 15) {
                    Playlist p = new Playlist();
                    p.id = Long.parseLong(f[1]);
                    p.station = Long.parseLong(f[2]);
                    p.name = f[3];
                    p.type = f[4];
                    p.enabled = "1".equals(f[5]);
                    p.weight = Integer.parseInt(f[6]);
                    p.sequential = "1".equals(f[7]);
                    p.days = f[8];
                    p.start = Integer.parseInt(f[9]);
                    p.end = Integer.parseInt(f[10]);
                    p.every = Integer.parseInt(f[11]);
                    for (String t : f[12].split(",")) {
                        if (!t.isEmpty()) {
                            p.tracks.add(Long.parseLong(t));
                        }
                    }
                    p.created = Long.parseLong(f[13]);
                    if (f.length >= 16 && "minutes".equals(f[14])) {
                        p.unit = "minutes";
                    }
                    if (f.length >= 20) {
                        p.style = f[15];
                        p.timed = "1".equals(f[16]);
                        p.inShows = !"0".equals(f[17]);
                        p.when = f[18];
                    }
                    if (f.length >= 24) {
                        p.interrupt = "no".equals(f[19]) ? "no" : "yes";
                        p.djOverride = !"0".equals(f[20]);
                        p.ai = !"0".equals(f[21]);
                        p.studio = "1".equals(f[22]);
                    }
                    playlists.put(p.id, p);
                    nextPlaylist = Math.max(nextPlaylist, p.id + 1);
                } else if ("F".equals(f[0]) && f.length >= 5) {
                    Staff sf = new Staff();
                    sf.station = Long.parseLong(f[1]);
                    sf.user = f[2];
                    sf.role = validRole(f[3]) ? f[3] : ROLE_PRESENTER;
                    sf.added = Long.parseLong(f[4]);
                    staff.add(sf);
                } else if ("W".equals(f[0]) && f.length >= 6) {
                    Show sh = new Show();
                    sh.station = Long.parseLong(f[1]);
                    sh.dj = f[2];
                    sh.start = Long.parseLong(f[3]);
                    sh.end = Long.parseLong(f[4]);
                    shows.add(sh);
                }
            } catch (RuntimeException e) {
                // skip a damaged line
            }
        }
        for (Map.Entry<Long, java.util.Set<String>> e : likers.entrySet()) {
            Track t = tracks.get(e.getKey());
            if (t != null) {
                t.likes = e.getValue().size();
            }
        }
        // stations from before live DJ ports: give each a port and a password
        for (Station s : stations.values()) {
            if (s.livePort == 0 || s.livePass.isEmpty()) {
                if (s.livePort == 0) {
                    s.livePort = freePort(s.id);
                }
                if (s.livePass.isEmpty()) {
                    s.livePass = newLivePassword();
                }
                dirty = true;
            }
        }
    }

    public synchronized void flushIfDirty() {
        if (!dirty) {
            return;
        }
        try {
            save();
        } catch (IOException e) {
            System.out.println("[radio] could not save: " + e.getMessage());
        }
    }

    private void save() throws IOException {
        StringBuilder sb = new StringBuilder("# Memoryq radio stations (S), songs (T) and likes (L) - keep private (live DJ passwords)\n");
        for (Station s : stations.values()) {
            sb.append("S\t").append(s.id).append('\t').append(s.name).append('\t').append(s.description).append('\t')
                    .append(s.djName).append('\t').append(s.voice).append('\t').append(s.enabled ? "1" : "0")
                    .append('\t').append(s.talkEvery).append('\t').append(s.newsOn ? "1" : "0").append('\t')
                    .append(s.shoutOn ? "1" : "0").append('\t').append(s.introsOn ? "1" : "0").append('\t')
                    .append(s.created).append('\t').append(s.promo).append('\t').append(s.owner).append('\t')
                    .append(s.status).append('\t').append(s.livePort).append('\t').append(s.livePass).append('\t')
                    .append(s.website).append('\t').append(s.billing).append('\t').append(s.paidUntil).append('\t')
                    .append(s.plan).append('\t').append(s.stripeCustomer).append('\t').append(s.stripeSub).append('\t')
                    .append(s.trialUsed ? "1" : "0").append('\t').append(s.reminded).append('\t')
                    .append(s.planId).append('\t').append(s.timezone).append('\t').append(s.monthKey).append('\t')
                    .append(s.monthBytes).append('\t').append(s.xferWarned).append('\t').append(s.noRepeatMin)
                    .append('\t').append(s.limListeners).append(',').append(s.limKbps).append(',').append(s.limStorageMb).append(',')
                    .append(s.limTransferGb).append(',').append(s.limDjs).append('\t').append(s.company).append('\t').append(s.email)
                    .append('\t').append(s.sayArtist ? "1" : "0").append('\t').append(s.storyEvery).append('\t').append(s.idEvery)
                    .append('\t').append(s.aiOn ? "1" : "0").append('\t').append(s.msgEvery).append("\tend\n");
        }
        for (Track t : tracks.values()) {
            sb.append("T\t").append(t.id).append('\t').append(t.station).append('\t').append(t.file).append('\t')
                    .append(t.title).append('\t').append(t.artist).append('\t').append(t.album).append('\t')
                    .append(t.durationMs).append('\t').append(t.added).append('\t').append(t.plays).append('\t')
                    .append(t.lastPlayed).append('\t').append(t.link).append('\t').append(t.taps).append('\t')
                    .append(t.requests).append("\tend\n");
        }
        for (Map.Entry<Long, java.util.Set<String>> e : likers.entrySet()) {
            if (!tracks.containsKey(e.getKey())) {
                continue;
            }
            for (String u : e.getValue()) {
                sb.append("L\t").append(e.getKey()).append('\t').append(u).append('\n');
            }
        }
        for (Dj d : djs.values()) {
            sb.append("J\t").append(d.id).append('\t').append(d.station).append('\t').append(d.name).append('\t')
                    .append(d.login).append('\t').append(d.password).append('\t').append(d.enabled ? "1" : "0").append('\t')
                    .append(d.days).append('\t').append(d.from).append('\t').append(d.to).append('\t').append(d.created)
                    .append('\t').append(d.lastLive).append('\t').append(d.studio ? "1" : "0").append("\tend\n");
        }
        for (Playlist p : playlists.values()) {
            StringBuilder ids = new StringBuilder();
            for (Long t : p.tracks) {
                if (tracks.containsKey(t)) {
                    ids.append(ids.length() > 0 ? "," : "").append(t);
                }
            }
            sb.append("Q\t").append(p.id).append('\t').append(p.station).append('\t').append(p.name).append('\t')
                    .append(p.type).append('\t').append(p.enabled ? "1" : "0").append('\t').append(p.weight).append('\t')
                    .append(p.sequential ? "1" : "0").append('\t').append(p.days).append('\t').append(p.start).append('\t')
                    .append(p.end).append('\t').append(p.every).append('\t').append(ids).append('\t').append(p.created)
                    .append('\t').append(p.unit).append('\t').append(p.style).append('\t').append(p.timed ? "1" : "0")
                    .append('\t').append(p.inShows ? "1" : "0").append('\t').append(p.when)
                    .append('\t').append(p.interrupt).append('\t').append(p.djOverride ? "1" : "0").append('\t').append(p.ai ? "1" : "0")
                    .append('\t').append(p.studio ? "1" : "0").append("\tend\n");
        }
        for (Show sh : shows) {
            sb.append("W\t").append(sh.station).append('\t').append(sh.dj).append('\t').append(sh.start).append('\t')
                    .append(sh.end).append("\tend\n");
        }
        for (Staff f : staff) {
            if (stations.containsKey(f.station)) {
                sb.append("F\t").append(f.station).append('\t').append(f.user).append('\t').append(f.role).append('\t')
                        .append(f.added).append("\tend\n");
            }
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, sb.toString().getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        dirty = false;
    }
}
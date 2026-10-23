package com.vstream.Vidiserver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Runs the radio stations: one {@link RadioEngine} per station that is on and has songs. It gives the engines
 * the songs, the news, the best chat posts for shout-outs and the DJ's voice.
 */
public final class RadioService {

    private static final long SHOUT_WINDOW = 12L * 3600 * 1000;
    private static final Pattern LINK = Pattern.compile("(?i)https?://|www\\.");

    private final RadioStore store;
    private final AudioTools tools;
    private final NewsService news;
    private final ChatStore chat;
    private final ProfileStore profiles;
    private final RadioWriter writer;
    private final LongSupplier clock;
    private final RadioScheduler scheduler;

    /** Playlists, shows and jingles: chooses the next song. */
    public RadioScheduler scheduler() {
        return scheduler;
    }
    private final Random random;
    private final Map<Long, Running> running = new LinkedHashMap<Long, Running>();
    private final Map<Long, LiveInput> liveDoors = new HashMap<Long, LiveInput>();
    private final Map<Long, LiveInput.LiveSource> liveWaiting = new HashMap<Long, LiveInput.LiveSource>();
    private final Map<Long, String> liveNames = new HashMap<Long, String>();
    private volatile RadioStats stats;
    private volatile boolean liveEnabled = false;
    private final Map<Long, java.util.Deque<RadioWriter.Request>> requests = new HashMap<Long, java.util.Deque<RadioWriter.Request>>();
    public static final int MAX_REQUESTS = 10;

    private static final class Running {
        RadioEngine engine;
        RadioEngine.Broadcast cast;
        Thread thread;
        int kbps;
    }

    /** planId -> the plan (its stream quality and number of DJs); null = no plan / no limits. */
    private volatile java.util.function.IntFunction<RadioBilling.Plan> plans = id -> null;

    public void setPlans(java.util.function.IntFunction<RadioBilling.Plan> p) {
        this.plans = p == null ? id -> null : p;
    }

    /** The stream quality for a station: its plan's, or the server default (128). */
    int kbpsFor(long id) {
        RadioStore.Station s = store.station(id);
        RadioBilling.Plan p = s == null ? null : RadioBilling.Plan.effective(plans.apply(s.planId), s);
        return p != null ? p.kbps : Integer.getInteger("vstream.radioKbps", 128);
    }

    /** How many remote DJs the station may use (its plan's; 3 without a plan). */
    public int maxDjs(long id) {
        RadioStore.Station s = store.station(id);
        RadioBilling.Plan p = s == null ? null : RadioBilling.Plan.effective(plans.apply(s.planId), s);
        return p != null ? p.djs : RadioBilling.MAX_DJS;
    }

    public RadioService(RadioStore store, AudioTools tools, NewsService news, ChatStore chat, ProfileStore profiles,
                        RadioWriter.Ai ai, LongSupplier clock, Random random) {
        this.store = store;
        this.tools = tools;
        this.news = news;
        this.chat = chat;
        this.profiles = profiles;
        this.writer = new RadioWriter(ai, random);
        this.clock = clock;
        this.scheduler = new RadioScheduler(store, clock, new Random(random.nextLong()));
        this.random = random;
    }

    private volatile RadioStories stories;

    /** Alma's stories (books read between songs). */
    public void setStories(RadioStories s) {
        this.stories = s;
    }

    public RadioStories stories() {
        return stories;
    }

    public RadioStore store() {
        return store;
    }

    /** Daily numbers per station (null in tests that do not need them). */
    public void setStats(RadioStats s) {
        this.stats = s;
    }

    public RadioStats stats() {
        return stats;
    }

    private volatile RadioListenLog listenLog;

    /** The detailed record behind Statistics (listening sessions, songs, events). */
    public void setListenLog(RadioListenLog l) {
        this.listenLog = l;
    }

    public RadioListenLog listenLog() {
        return listenLog;
    }

    void logEvent(long station, String kind, String text) {
        RadioListenLog l = listenLog;
        if (l != null) {
            l.event(station, kind, text);
        }
    }

    /** Opens each station's live DJ port (off in tests). */
    public void setLiveEnabled(boolean on) {
        this.liveEnabled = on;
    }

    public synchronized LiveInput liveDoor(long id) {
        return liveDoors.get(id);
    }

    public AudioTools tools() {
        return tools;
    }

    public boolean aiReady() {
        return writer.aiReady();
    }

    /** Starts stations that should be on air and stops the others. */
    public synchronized void refresh() {
        Set<Long> want = new HashSet<Long>();
        if (tools.ready()) {
            for (RadioStore.Station s : store.stations()) {
                if (s.onAir() && (store.trackCount(s.id) > 0 || liveWaiting.containsKey(s.id))) {
                    want.add(s.id);
                }
            }
        }
        syncLiveDoors();
        for (Long id : new ArrayList<Long>(running.keySet())) {
            Running r = running.get(id);
            if (!want.contains(id) || !r.engine.running() || (r.kbps != kbpsFor(id) && !r.engine.liveOnAir())) {
                stop(id);
            }
        }
        for (Long id : want) {
            if (!running.containsKey(id)) {
                start(id);
            }
        }
    }

    /** Per station: the song picked last and its playlist; the playlist of the song playing now (0 = none). */
    private final Map<Long, long[]> pickedFrom = new java.util.concurrent.ConcurrentHashMap<Long, long[]>();
    private final Map<Long, Long> playingFrom = new java.util.concurrent.ConcurrentHashMap<Long, Long>();

    /** The AI presenter may talk now: on for the station, and on for the playlist playing / about to play. */
    boolean aiAllowed(long station, RadioStore.Station s) {
        if (!s.aiOn) {
            return false;
        }
        RadioStore.Playlist p = scheduler.currentPlaylistObj(station);
        return p == null || p.ai;
    }

    /**
     * "Interrupt": the song playing comes from a scheduled show that may be interrupted ("yes"), and another show is
     * now due (its time came, or this one ran over its end) - the song fades out at once and the other show starts.
     * A show that may not be interrupted ("no") goes on to its own end; at its end its last song finishes.
     */
    boolean cutOverrunningShow(long station) {
        Long fromId = playingFrom.get(station);
        if (fromId == null || fromId == 0) {
            return false;
        }
        RadioStore.Playlist from = store.playlist(fromId);
        if (from == null || !RadioStore.Playlist.SCHEDULED.equals(from.type) || !"yes".equals(from.interrupt)) {
            return false;
        }
        RadioStore.Playlist next = scheduler.showOnNow(station);
        if (next == null || next.id == from.id) {
            return false;
        }
        playingFrom.put(station, 0L);
        RadioEngine e = engine(station);
        if (e == null || e.liveOnAir()) {
            return false;
        }
        e.skip();
        logEvent(station, "server", next.name + " starts now - " + from.name + " was interrupted");
        return true;
    }

    public void tick() {
        refresh();
        for (Long sid : new ArrayList<Long>(playingFrom.keySet())) {
            try {
                cutOverrunningShow(sid);
            } catch (RuntimeException e) {
                System.out.println("[radio] " + e);
            }
        }
        store.flushIfDirty();
        RadioStats st = stats;
        if (st != null) {
            Map<Long, Integer> counts = new HashMap<Long, Integer>();
            Set<Long> live = new HashSet<Long>();
            synchronized (this) {
                for (Map.Entry<Long, Running> e : running.entrySet()) {
                    counts.put(e.getKey(), e.getValue().cast.listenerCount());
                    if (e.getValue().engine.liveOnAir()) {
                        live.add(e.getKey());
                    }
                }
            }
            st.sample(counts, live);
            st.flushIfDirty();
        }
    }

    // ---- Live DJ doors (one port per station) ----

    /** Opens the ports of stations that may go live and closes the others (or ones whose port changed). */
    private void syncLiveDoors() {
        Map<Long, Integer> want = new HashMap<Long, Integer>();
        if (liveEnabled && tools.ready()) {
            for (RadioStore.Station s : store.stations()) {
                if (s.onAir() && s.livePort > 0) {
                    want.put(s.id, s.livePort);
                }
            }
        }
        for (Long id : new ArrayList<Long>(liveDoors.keySet())) {
            LiveInput d = liveDoors.get(id);
            Integer port = want.get(id);
            if (port == null || port != d.port()) {
                d.stop();
                liveDoors.remove(id);
            }
        }
        for (Map.Entry<Long, Integer> e : want.entrySet()) {
            if (!liveDoors.containsKey(e.getKey())) {
                LiveInput d = new LiveInput(e.getKey(), e.getValue(), liveCallbacks);
                String err = d.start();
                if (err != null) {
                    System.out.println("[radio] live DJ door for station " + e.getKey() + ": " + err);
                }
                liveDoors.put(e.getKey(), d);
            }
        }
    }

    /** For tests: the checks the live DJ door uses. */
    LiveInput.Station liveCallbacks() {
        return liveCallbacks;
    }

    private final LiveInput.Station liveCallbacks = new LiveInput.Station() {
        @Override
        public String password(long stationId) {
            RadioStore.Station s = store.station(stationId);
            return s == null || !s.onAir() ? null : s.livePass;
        }

        @Override
        public LiveInput.Login login(long stationId, String user, String pass, String softwareName) {
            RadioStore.Station s = store.station(stationId);
            if (s == null || !s.onAir()) {
                return new LiveInput.Login("", 0, "This station is off air - live DJs can connect when it is on air.");
            }
            String u = user == null ? "" : user.trim();
            if (u.isEmpty() || u.equalsIgnoreCase("source")) {
                boolean ok = pass != null && java.security.MessageDigest.isEqual(s.livePass.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        pass.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (!ok) {
                    return null;
                }
                return new LiveInput.Login(softwareName == null || softwareName.isEmpty() ? s.djName : softwareName, 0,
                        studioBusy(stationId) ? "Another DJ is live on this station right now (Live DJ Pro)." : null);
            }
            RadioStore.Dj d = store.djLogin(stationId, u, pass);
            if (d == null) {
                return null;
            }
            String no = djRefusal(stationId, d.id);
            if (no == null && studioBusy(stationId)) {
                no = "Another DJ is live on this station right now (Live DJ Pro).";
            }
            return new LiveInput.Login(d.name, d.id, no);
        }

        @Override
        public void showEnded(long stationId, long djId, String name, long start, long end) {
            if (end - start >= 30000) {
                store.addShow(stationId, name, start, end, djId);
            }
        }

        @Override
        public Process decoder() throws IOException {
            return tools.liveDecoder();
        }

        @Override
        public void connected(long stationId, LiveInput.LiveSource source, String djName) {
            RadioEngine e;
            synchronized (RadioService.this) {
                liveWaiting.put(stationId, source);
                liveNames.put(stationId, djName == null ? "" : djName);
                if (running.containsKey(stationId)) {
                    e = running.get(stationId).engine;
                } else {
                    e = null;
                    refresh(); // starts the station, which picks up the waiting DJ
                }
            }
            if (e != null) {
                e.goLive(source, djName);
            }
            RadioStore.Station s = store.station(stationId);
            System.out.println("[radio] live DJ on air: " + (s == null ? "station " + stationId : s.name)
                    + (djName == null || djName.isEmpty() ? "" : " (" + djName + ")"));
            logEvent(stationId, "live", (djName == null || djName.isEmpty() ? "A live DJ" : djName) + " went on air - AutoDJ waits");
        }

        @Override
        public void disconnected(long stationId, LiveInput.LiveSource source) {
            synchronized (RadioService.this) {
                if (liveWaiting.get(stationId) == source) {
                    liveWaiting.remove(stationId);
                }
            }
            System.out.println("[radio] live DJ left station " + stationId);
            logEvent(stationId, "live", "The live DJ left - AutoDJ is back");
        }
    };

    /** Why this remote DJ may not go live now (switched off, not in the plan, outside their hours), or null. */
    public String djRefusal(long stationId, long djId) {
        RadioStore.Station s = store.station(stationId);
        RadioStore.Dj d = store.dj(djId);
        if (s == null || d == null || d.station != stationId) {
            return "This DJ login no longer exists.";
        }
        if (!d.enabled) {
            return "Your DJ login is switched off - ask the station owner.";
        }
        List<RadioStore.Dj> all = store.djs(stationId);
        int index = 0;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id == d.id) {
                index = i;
            }
        }
        if (index >= maxDjs(stationId)) {
            return "The station's plan does not include this DJ any more.";
        }
        java.time.ZoneId zone;
        try {
            zone = java.time.ZoneId.of(s.timezone);
        } catch (RuntimeException e) {
            zone = java.time.ZoneOffset.UTC;
        }
        java.time.ZonedDateTime t = java.time.Instant.ofEpochMilli(clock.getAsLong()).atZone(zone);
        if (!d.allowedAt(t)) {
            return "Outside your DJ hours (" + hours(d) + ", " + s.timezone + ").";
        }
        RadioStore.Playlist show = scheduler.showOnNow(stationId);
        if (show != null && !show.djOverride) {
            return show.name + " is on now and cannot be taken over by a DJ - try again after it ends.";
        }
        return null;
    }

    // ---- Live DJ Pro (the browser console) ----

    private final Map<Long, LiveInput.LiveSource> studioLive = new HashMap<Long, LiveInput.LiveSource>();

    /** A Live DJ Pro session is on air on this station. */
    public synchronized boolean studioBusy(long id) {
        LiveInput.LiveSource src = studioLive.get(id);
        return src != null && !src.finished();
    }

    /** Someone is live (a DJ connected to the door, or a Live DJ Pro session). */
    public synchronized boolean liveBusy(long id) {
        LiveInput door = liveDoors.get(id);
        return studioBusy(id) || (door != null && door.connected());
    }

    /** The console's sound goes on air (the engine fades AutoDJ out; AutoDJ waits in the background). */
    public void studioConnected(long id, LiveInput.LiveSource src, String name) {
        synchronized (this) {
            studioLive.put(id, src);
        }
        liveCallbacks.connected(id, src, name);
    }

    /** The console went off air: AutoDJ comes back. */
    public void studioDisconnected(long id, LiveInput.LiveSource src) {
        src.endInput();
        synchronized (this) {
            if (studioLive.get(id) == src) {
                studioLive.remove(id);
            }
        }
        liveCallbacks.disconnected(id, src);
    }

    /** "Mon-Fri 20:00-23:00" style. */
    static String hours(RadioStore.Dj d) {
        String[] names = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
        StringBuilder sb = new StringBuilder();
        if ("1111111".equals(d.days)) {
            sb.append("every day");
        } else {
            for (int i = 0; i < 7; i++) {
                if (d.days.charAt(i) == '1') {
                    sb.append(sb.length() > 0 ? "," : "").append(names[i]);
                }
            }
        }
        if (!(d.from == 0 && d.to == 24)) {
            sb.append(String.format(java.util.Locale.ROOT, " %02d:00-%02d:00", d.from, d.to % 24));
        }
        return sb.toString();
    }

    private void start(final long id) {
        Running r = new Running();
        r.kbps = kbpsFor(id);
        r.cast = new RadioEngine.Broadcast(tools, r.kbps);
        final RadioEngine.Broadcast cast = r.cast;
        r.engine = new RadioEngine(id, contentFor(id, cast), cast, true, new Random(random.nextLong()));
        r.engine.setWriter(writer);
        LiveInput.LiveSource waiting = liveWaiting.get(id);
        if (waiting != null && !waiting.finished()) {
            r.engine.goLive(waiting, liveNames.get(id));
        }
        r.thread = new Thread(r.engine, "radio-" + id);
        r.thread.setDaemon(true);
        r.thread.start();
        running.put(id, r);
        RadioStore.Station s = store.station(id);
        System.out.println("[radio] on air: " + (s == null ? "station " + id : s.name));
        logEvent(id, "server", "Stream started (" + r.kbps + " kbps)");
    }

    /** Stops a station's stream and starts it again (Server - Restart). */
    public synchronized void restart(long id) {
        logEvent(id, "server", "Restarted from the Studio");
        stop(id);
        refresh();
    }

    private void stop(long id) {
        Running r = running.remove(id);
        if (r != null) {
            logEvent(id, "server", "Stream stopped");
            r.engine.stop();
            r.thread.interrupt();
            r.cast.close();
        }
    }

    public synchronized void stopAll() {
        for (Long id : new ArrayList<Long>(running.keySet())) {
            stop(id);
        }
        for (LiveInput d : liveDoors.values()) {
            d.stop();
        }
        liveDoors.clear();
    }

    public synchronized RadioEngine engine(long id) {
        Running r = running.get(id);
        return r == null ? null : r.engine;
    }

    public synchronized RadioEngine.Broadcast broadcast(long id) {
        Running r = running.get(id);
        return r == null ? null : r.cast;
    }

    public int listeners(long id) {
        RadioEngine.Broadcast b = broadcast(id);
        return b == null ? 0 : b.listenerCount();
    }

    // ---- What the engine plays and says ----

    private RadioEngine.Content contentFor(final long id, final RadioEngine.Broadcast cast) {
        return new RadioEngine.Content() {
            @Override
            public RadioStore.Station station() {
                RadioStore.Station s = store.station(id);
                if (s != null && !aiAllowed(id, s)) {
                    // the AI presenter is off (for the station, or for this playlist): songs only, no talk
                    s.talkEvery = 0;
                    s.introsOn = false;
                    s.storyEvery = 0;
                    s.idEvery = 0;
                }
                return s;
            }

            @Override
            public List<RadioStore.Track> tracks() {
                return store.tracks(id);
            }

            @Override
            public RadioEngine.Source open(RadioStore.Track t) throws IOException {
                return new RadioEngine.ProcessSource(tools.decode(store.pathOf(t)));
            }

            @Override
            public short[] speak(String text, String voice) {
                return normalize(tools.speak(text, voice, false));
            }

            @Override
            public short[] speak(String text, String voice, boolean premium) {
                return normalize(tools.speak(text, voice, premium));
            }

            @Override
            public RadioStories.Part nextStory() {
                RadioStories sto = stories;
                return sto == null ? null : sto.next(id);
            }

            @Override
            public void storyAired(RadioStories.Part p) {
                RadioStories sto = stories;
                if (sto != null) {
                    sto.aired(p);
                }
            }

            @Override
            public List<NewsService.Item> news() {
                return news == null ? new ArrayList<NewsService.Item>() : news.latestVisible(30);
            }

            @Override
            public RadioWriter.Shout bestShout(Set<String> used) {
                return RadioService.this.bestShout(used);
            }

            @Override
            public int listeners() {
                return cast.listenerCount();
            }

            @Override
            public void played(RadioStore.Track t) {
                long[] pf = pickedFrom.get(id);
                playingFrom.put(id, pf != null && pf[0] == t.id ? pf[1] : 0L);
                store.countPlay(t.id, clock.getAsLong());
                RadioListenLog ll = listenLog;
                if (ll != null) {
                    ll.played(id, t.id, t.title, t.artist, t.durationMs, cast.listenerCount(), "autodj");
                }
                RadioStats st = stats;
                if (st != null) {
                    st.played(id);
                }
            }

            @Override
            public String voiceProblem() {
                return tools.lastVoiceError();
            }

            @Override
            public RadioWriter.Request takeRequest() {
                if (scheduler.breakDue(id)) {
                    return null; // the ad break / station ID first, then the request
                }
                RadioWriter.Request r = RadioService.this.takeRequest(id);
                if (r != null) {
                    scheduler.countSong(id);
                }
                return r;
            }

            @Override
            public RadioStore.Track chooseNext(long currentId) {
                RadioStore.Track t = scheduler.next(id, currentId);
                RadioStore.Playlist from = scheduler.currentPlaylistObj(id);
                if (t != null) {
                    pickedFrom.put(id, new long[]{t.id, from == null ? 0 : from.id});
                }
                return t;
            }

            @Override
            public boolean lastPickWasBreak() {
                return scheduler.lastPickWasBreak(id);
            }
        };
    }

    /**
     * A listener asks for a song. One waiting request per person per station, ten at most per station, and a song
     * can only be waiting once. Returns the place in the queue (1 = next).
     */
    public synchronized int request(long station, long trackId, String user, String name) {
        RadioStore.Track t = store.track(trackId);
        if (t == null || t.station != station) {
            throw new IllegalArgumentException("that song is not on this station");
        }
        java.util.Deque<RadioWriter.Request> q = requests.computeIfAbsent(station, k -> new java.util.ArrayDeque<RadioWriter.Request>());
        for (RadioWriter.Request r : q) {
            if (r.user.equalsIgnoreCase(user)) {
                throw new IllegalArgumentException("you already have a request waiting - it plays soon");
            }
            if (r.trackId == trackId) {
                throw new IllegalArgumentException("someone already asked for that song - it plays soon");
            }
        }
        if (q.size() >= MAX_REQUESTS) {
            throw new IllegalArgumentException("lots of requests right now - try again in a few songs");
        }
        q.addLast(new RadioWriter.Request(trackId, user, name));
        store.countRequest(trackId);
        return q.size();
    }

    public synchronized List<RadioWriter.Request> requests(long station) {
        java.util.Deque<RadioWriter.Request> q = requests.get(station);
        return q == null ? new ArrayList<RadioWriter.Request>() : new ArrayList<RadioWriter.Request>(q);
    }

    synchronized RadioWriter.Request takeRequest(long station) {
        java.util.Deque<RadioWriter.Request> q = requests.get(station);
        return q == null ? null : q.pollFirst();
    }

    /** A person's stage name (or username) for the DJ to say. */
    public String displayName(String user) {
        ProfileStore.Profile p = profiles == null ? null : profiles.get(user);
        return p != null && p.stageName != null && !p.stageName.isEmpty() ? p.stageName : user;
    }

    /** How many news stories the DJ can talk about now. */
    public int newsAvailable() {
        return news == null ? 0 : news.latestVisible(30).size();
    }

    /** Whether there is a chat post for a shout-out now. */
    public boolean shoutAvailable() {
        return bestShout(new HashSet<String>()) != null;
    }

    /** Says a test sentence with a station's DJ voice. Returns the sound, or null (see tools().lastVoiceError()). */
    public short[] testVoice(RadioStore.Station st) {
        String dj = st == null ? "Alma" : st.djName;
        String name = st == null ? "Memoryq radio" : st.name;
        return normalize(tools.speak("Hey hey, hello everybody! This is " + dj + ", live on " + name
                + ". How are you feeling today? Stay with me, because we have great music coming up for you."
                + " Let's go!", st == null ? "" : st.voice, true));
    }

    /** Brings speech to a steady loudness so the DJ is as loud as the music. */
    static short[] normalize(short[] s) {
        if (s == null || s.length == 0) {
            return s;
        }
        int peak = 1;
        for (short v : s) {
            peak = Math.max(peak, Math.abs((int) v));
        }
        double k = Math.min(4.0, 26000.0 / peak);
        short[] out = new short[s.length];
        for (int i = 0; i < s.length; i++) {
            int v = (int) Math.round(s[i] * k);
            out[i] = (short) Math.max(-32768, Math.min(32767, v));
        }
        return out;
    }

    /**
     * The chat post most worth a shout-out: from a public group in the last 12 hours, scored by reactions and
     * replies. Posts with links, very short or very long ones are skipped.
     */
    public RadioWriter.Shout bestShout(Set<String> used) {
        if (chat == null) {
            return null;
        }
        long since = clock.getAsLong() - SHOUT_WINDOW;
        RadioWriter.Shout best = null;
        int bestScore = 0;
        for (ChatStore.RoomInfo room : chat.listGroups()) {
            if (room.priv) {
                continue;
            }
            List<ChatStore.Message> msgs = chat.messages(room.id, 0, 300);
            Map<Long, Integer> replies = new HashMap<Long, Integer>();
            Map<Long, Set<String>> people = new HashMap<Long, Set<String>>();
            for (ChatStore.Message m : msgs) {
                if (m.replyTo > 0 && !m.deleted) {
                    replies.merge(m.replyTo, 1, Integer::sum);
                    people.computeIfAbsent(m.replyTo, k -> new HashSet<String>()).add(m.user.toLowerCase());
                }
            }
            for (ChatStore.Message m : msgs) {
                if (m.deleted || m.time < since || m.text == null) {
                    continue;
                }
                String text = m.text.trim();
                if (text.length() < 12 || text.length() > 280 || LINK.matcher(text).find()
                        || used.contains(m.user + "|" + text)) {
                    continue;
                }
                int reactions = 0;
                Set<String> who = new HashSet<String>();
                who.add(m.user.toLowerCase());
                if (m.reactions != null) {
                    for (Map.Entry<String, List<String>> e : m.reactions.entrySet()) {
                        reactions += e.getValue().size();
                        for (String u : e.getValue()) {
                            who.add(u.toLowerCase());
                        }
                    }
                }
                int r = replies.getOrDefault(m.id, 0);
                if (people.containsKey(m.id)) {
                    who.addAll(people.get(m.id));
                }
                int score = reactions * 3 + r * 2 + 1 + Math.min(3, text.length() / 40);
                if (score > bestScore) {
                    bestScore = score;
                    RadioWriter.Shout s = new RadioWriter.Shout();
                    s.user = m.user;
                    ProfileStore.Profile p = profiles == null ? null : profiles.get(m.user);
                    s.name = p != null && p.stageName != null && !p.stageName.isEmpty() ? p.stageName : m.user;
                    s.room = room.title;
                    s.text = text;
                    s.replies = r;
                    s.reactions = reactions;
                    s.people = who.size();
                    best = s;
                }
            }
        }
        return best;
    }
}
package com.vstream.Vidiserver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * One radio station on air. It plays the station's songs in shuffled order with crossfades, and every few songs
 * (a little at random) the DJ talks: the top news with some analysis, or a shout-out to a chat post, then
 * introduces the next song while that song rises from the background, like a real DJ.
 *
 * The sound is mixed here (raw 44.1 kHz stereo samples) and handed to a {@link Sink}: on the server that is FFmpeg
 * making an MP3 stream for the listeners; in tests it collects the samples.
 */
public final class RadioEngine implements Runnable {

    public static final int RATE = AudioTools.RATE;
    private static final int BLOCK = 2048;
    private static final int FADE = RATE * 4;
    private static final int SKIP_FADE = RATE * 3 / 2;
    private static final int GAP = RATE * 2 / 5;
    private static final double BED = 0.18;
    /** The song's level while Alma says the station's name over it. */
    private static final double DUCK = 0.32;

    /** Raw stereo samples, read in frames (one frame = left + right). */
    public interface Source {
        /** Fills buf with up to frames frames; returns how many, or -1 at the end. */
        int read(short[] buf, int frames) throws IOException;

        void close();
    }

    /** Where the mixed sound goes. */
    public interface Sink {
        void write(short[] buf, int frames) throws IOException;
    }

    /** What the engine needs from the rest of the server. */
    public interface Content {
        RadioStore.Station station();

        List<RadioStore.Track> tracks();

        Source open(RadioStore.Track t) throws IOException;

        /** Speaks text; null when there is no voice. */
        short[] speak(String text, String voice);

        /** Speaks text; premium voices (OpenAI) may be used when premium is true (someone is listening). */
        default short[] speak(String text, String voice, boolean premium) {
            return speak(text, voice);
        }

        List<NewsService.Item> news();

        RadioWriter.Shout bestShout(Set<String> alreadyUsed);

        int listeners();

        void played(RadioStore.Track t);

        /** The next song from the station's playlists, or null to shuffle all songs. currentId = playing now (0). */
        default RadioStore.Track chooseNext(long currentId) {
            return null;
        }

        /** The next listener request for this station, or null. */
        default RadioWriter.Request takeRequest() {
            return null;
        }

        /** The next part of the station's story (Alma's stories), or null when there is none. */
        default RadioStories.Part nextStory() {
            return null;
        }

        /** A story part was read on air (the book moves on). */
        default void storyAired(RadioStories.Part p) {
        }

        /** Starts finding out what this song is about (its message), in the background. */
        default void prepareSongMessage(RadioStore.Track t) {
        }

        /** The song's message for the presenter (waits up to waitMs for it), or null. */
        default String songMessage(RadioStore.Track t, long waitMs) {
            return null;
        }

        /** Whether the last song chosen is part of an ad break / station ID (a request must not push it out). */
        default boolean lastPickWasBreak() {
            return false;
        }

        /** Why the voice failed last time ("" if unknown). */
        default String voiceProblem() {
            return "";
        }
    }

    /** One line of the station's log: a song that started or something the DJ said. */
    public static final class Event {
        public final long time;
        public final String kind;
        public final String text;
        public final long trackId;
        public final long durationMs;

        Event(long time, String kind, String text, long trackId, long durationMs) {
            this.time = time;
            this.kind = kind;
            this.text = text;
            this.trackId = trackId;
            this.durationMs = durationMs;
        }
    }

    private static final class Voice {
        final Source src;
        final RadioStore.Track track;
        final long total;
        long frames;
        double gain;
        double target;
        double step;
        boolean ended;
        final short[] buf = new short[BLOCK * 2];

        Voice(Source src, RadioStore.Track track, double gain) {
            this.src = src;
            this.track = track;
            this.total = track == null ? 0 : track.durationMs * RATE / 1000;
            this.gain = gain;
            this.target = gain;
        }

        void fadeTo(double to, int overFrames) {
            target = to;
            step = overFrames <= 0 ? 1 : Math.abs(to - gain) / overFrames;
            if (step == 0) {
                step = 1;
            }
        }

        long left() {
            return total <= 0 ? Long.MAX_VALUE : total - frames;
        }
    }

    private static final class MemorySource implements Source {
        private final short[] data;
        private int pos;

        MemorySource(short[] data) {
            this.data = data;
        }

        @Override
        public int read(short[] buf, int frames) {
            int avail = (data.length - pos) / 2;
            if (avail <= 0) {
                return -1;
            }
            int n = Math.min(frames, avail);
            System.arraycopy(data, pos, buf, 0, n * 2);
            pos += n * 2;
            return n;
        }

        @Override
        public void close() {
        }
    }

    /** What the DJ will say at the next break. */
    private static final class Break {
        short[] main;
        String mainKind = "";
        String mainText = "";
        short[] intro;
        String introText = "";
        RadioStories.Part story;
    }

    private final Content content;
    private final Sink sink;
    private final boolean realtime;
    private final Random random;
    private final long stationId;
    private final ExecutorService prep = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "radio-dj");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean running = true;
    private volatile boolean skip = false;
    private volatile boolean talkNow = false;
    private volatile boolean talkImmediately = false;
    private volatile boolean quietNotes = false;
    private final List<Voice> voices = new ArrayList<Voice>();

    private Voice current;
    private RadioStore.Track previous;
    private RadioStore.Track planned;
    private Future<Break> plannedTalk;
    private boolean transitioning;
    private int songsSinceTalk;
    private int songsSinceStory;
    private int songsSinceMsg;
    /** Ad / station ID songs seen in breaks: never talked about. */
    private final java.util.Set<Long> breakSongs = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Long, Boolean>());
    private int songsSinceId;
    // Alma over a song: the line being prepared, her voice while she talks, and the song under it
    private Future<short[]> idPrep;
    private long idFor;
    private String idText = "";
    private Voice idVoice;
    private Voice idSong;
    private int talkThreshold = -1;
    private String lastTalkKind = "shout";
    private final Deque<Long> bag = new ArrayDeque<Long>();
    private final Set<Long> known = new HashSet<Long>();
    private final Set<Long> usedNews = new HashSet<Long>();
    private final Set<String> usedShouts = new HashSet<String>();

    // talk phase
    private volatile boolean talking;
    private Voice speech;
    private Break talkBreak;
    private int talkStage;
    private int gapLeft;
    private Voice bed;

    private final Deque<Event> log = new ArrayDeque<Event>();
    private volatile Event nowSong;
    private volatile Event nowTalk;
    private volatile RadioStore.Track upNext;
    private volatile long framesOut;

    public RadioEngine(long stationId, Content content, Sink sink, boolean realtime, Random random) {
        this.stationId = stationId;
        this.content = content;
        this.sink = sink;
        this.realtime = realtime;
        this.random = random;
    }

    public long stationId() {
        return stationId;
    }

    public void stop() {
        running = false;
        prep.shutdownNow();
    }

    public boolean running() {
        return running;
    }

    /** Fades out the current song and moves on. */
    public void skip() {
        skip = true;
    }

    /** The DJ talks at the next change of song. */
    public void talkNow() {
        talkNow = true;
    }

    private volatile boolean requestArrived = false;

    /** A listener requested a song: it becomes the next song (unless a request is already next). */
    public void requestArrived() {
        requestArrived = true;
    }

    /** The DJ talks right away: as soon as the talk is ready, the song fades out. */
    public void talkImmediately() {
        talkImmediately = true;
        talkNow = true;
    }

    // ---- Live DJ ----

    private volatile Source liveIn;
    private volatile String liveName = "";
    private Voice live;
    /** While a live DJ is on: the playlist goes on silently underneath, and comes back where it is when the DJ leaves. */
    private Voice background;
    private RadioStore.Track backgroundNext;

    /** A live DJ connected: the playlist fades out and the DJ fades in until the source ends. */
    public void goLive(Source source, String djName) {
        liveName = djName == null ? "" : djName.trim();
        liveIn = source;
    }

    /** The live DJ left (the source also ends by itself once everything sent has played). */
    public void endLive(Source source) {
        // nothing to do: the source runs dry and the engine goes back to the playlist
    }

    /** True while a live DJ is on air. */
    public boolean liveOnAir() {
        return liveIn != null;
    }

    /** Keeps notes out of the server window (used by tests). */
    public void setQuietNotes(boolean quiet) {
        quietNotes = quiet;
    }

    public Event nowSong() {
        return nowSong;
    }

    /** What the DJ is saying right now, or null. */
    public Event nowTalk() {
        return talking ? nowTalk : null;
    }

    public RadioStore.Track upNext() {
        return upNext;
    }

    /** Seconds of sound produced so far. */
    public double seconds() {
        return framesOut / (double) RATE;
    }

    public List<Event> recent(int max) {
        synchronized (log) {
            List<Event> out = new ArrayList<Event>(log);
            Collections.reverse(out);
            return out.size() > max ? out.subList(0, max) : out;
        }
    }

    private void addEvent(String kind, String text, RadioStore.Track t) {
        Event e = new Event(System.currentTimeMillis(), kind, text, t == null ? 0 : t.id, t == null ? 0 : t.durationMs);
        synchronized (log) {
            log.addLast(e);
            while (log.size() > 40) {
                log.removeFirst();
            }
        }
        if ("song".equals(kind)) {
            nowSong = e;
        } else if ("note".equals(kind)) {
            if (!quietNotes) {
                System.out.println("[radio] station " + stationId + ": " + text);
            }
        } else {
            nowTalk = e;
        }
    }

    // ---- Main loop ----

    @Override
    public void run() {
        short[] mix = new short[BLOCK * 2];
        int[] acc = new int[BLOCK * 2];
        long start = System.nanoTime();
        long written = 0;
        try {
            while (running) {
                step();
                int n = mixBlock(acc, mix);
                sink.write(mix, n);
                written += n;
                framesOut = written;
                if (realtime) {
                    long due = start + written * 1_000_000_000L / RATE - 400_000_000L;
                    long wait = due - System.nanoTime();
                    if (wait > 0) {
                        Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
                    } else if (wait < -3_000_000_000L) {
                        // fell far behind (the PC was busy or asleep): carry on from now
                        start = System.nanoTime() - written * 1_000_000_000L / RATE;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            System.out.println("[radio] station " + stationId + " stopped: " + e.getMessage());
        } finally {
            running = false;
            for (Voice v : voices) {
                v.src.close();
            }
            voices.clear();
            prep.shutdownNow();
        }
    }

    /** Decides what plays: starts songs, crossfades, DJ breaks. Called before each block. */
    private void step() {
        if (stepLive()) {
            return;
        }
        if (talking) {
            stepTalk();
            return;
        }
        if (current == null) {
            RadioStore.Track t = planned != null ? planned : pickNext();
            planned = null;
            plannedTalk = null;
            if (t == null) {
                return;
            }
            current = startSong(t, 0, RATE / 2);
            current.fadeTo(1, RATE / 2);
            return;
        }
        if (requestArrived && planned != null && plannedRequestBy.isEmpty() && content.lastPickWasBreak()) {
            requestArrived = false; // the break's song stays next; the request plays when the break is over
        } else if (requestArrived && !transitioning && plannedRequestBy.isEmpty()) {
            // put the planned song back and plan again so the request is next
            requestArrived = false;
            if (planned != null) {
                bag.addFirst(planned.id);
            }
            if (plannedTalk != null) {
                plannedTalk.cancel(false);
            }
            planned = null;
            plannedTalk = null;
        } else if (requestArrived && !plannedRequestBy.isEmpty()) {
            requestArrived = false;
        }
        if (planned == null && plannedTalk == null) {
            plan();
        }
        stepId();
        if (talkNow && !transitioning && planned != null) {
            // "DJ talks next": get the talk ready for the end of this song
            talkNow = false;
            final RadioStore.Station st = content.station();
            if (st != null) {
                final RadioStore.Track nx = planned;
                final RadioStore.Track pv = current.track;
                final boolean ai = content.listeners() > 0;
                final String by = plannedRequestBy;
                final boolean story = st.storyEvery > 0 && songsSinceStory >= st.storyEvery && content.nextStory() != null;
                plannedTalk = prep.submit(() -> prepare(st, nx, pv, true, ai, by, story));
                addEvent("note", talkImmediately ? "The DJ is getting ready to talk now..."
                        : "The DJ is getting ready to talk after this song.", null);
            }
        }
        if (talkImmediately && !transitioning && plannedTalk != null && plannedTalk.isDone()) {
            // the talk is ready: fade the song out now and talk
            talkImmediately = false;
            skip = true;
        }
        boolean skipping = skip;
        if (skipping) {
            skip = false;
            current.fadeTo(0, SKIP_FADE);
        }
        if (!transitioning && (skipping || current.left() <= FADE || current.ended)) {
            transitioning = true;
            if (planned != null && current.track != null && planned.id == current.track.id
                    && content.tracks().size() > 1) {
                // more songs arrived since this was planned: don't repeat the same one
                planned = pickNext();
                upNext = planned;
                plannedTalk = null;
            }
            Break b = null;
            if (plannedTalk != null && plannedTalk.isDone()) {
                try {
                    b = plannedTalk.get();
                } catch (Exception e) {
                    b = null;
                }
            } else if (plannedTalk != null && !talkImmediately) {
                // the voice was not ready in time: talk after the next song instead
                plannedTalk.cancel(false);
                talkNow = true;
                addEvent("note", "The DJ was not ready in time - talking after the next song.", null);
            }
            if (b != null && (b.main != null || b.intro != null)) {
                current.fadeTo(0, skipping ? SKIP_FADE : (int) Math.min(FADE, Math.max(RATE, current.left())));
                talkBreak = b;
                talkStage = 0;
                talking = true;
                if (b.main != null && !"message".equals(b.mainKind)) {
                    songsSinceTalk = 0;
                    talkThreshold = -1;
                }
                return;
            }
            // no talk ready: plain crossfade into the next song
            RadioStore.Track nextTrack = planned != null ? planned : pickNext();
            previous = current.track;
            if (!skipping) {
                current.fadeTo(0, current.ended ? 1 : FADE);
            }
            if (nextTrack != null) {
                Voice next = startSong(nextTrack, 0, FADE);
                next.fadeTo(1, skipping ? SKIP_FADE : (current.ended ? RATE / 4 : FADE));
                current = next;
            } else {
                current = null;
            }
            planned = null;
            plannedTalk = null;
            transitioning = false;
        }
    }

    /** Handles a live DJ. Returns true while the live DJ has the air. */
    private boolean stepLive() {
        Source in = liveIn;
        if (in != null && live == null) {
            // the DJ takes over: everything else fades out; the song goes on silently in the background
            for (Voice v : voices) {
                v.fadeTo(0, SKIP_FADE);
            }
            background = current != null && current.track != null && !current.ended ? current : null;
            if (plannedTalk != null) {
                plannedTalk.cancel(false);
            }
            backgroundNext = planned; // the song that was coming next plays next underneath
            planned = null;
            plannedTalk = null;
            upNext = null;
            talking = false;
            talkBreak = null;
            speech = null;
            bed = null;
            transitioning = false;
            current = null;
            live = new Voice(in, null, 0);
            live.fadeTo(1, SKIP_FADE);
            voices.add(live);
            addEvent("song", "LIVE" + (liveName.isEmpty() ? "" : ": " + liveName), null);
            addEvent("note", "A live DJ is on air" + (liveName.isEmpty() ? "" : " (" + liveName + ")"), null);
            return true;
        }
        if (live != null) {
            skip = false;
            talkNow = false;
            talkImmediately = false;
            requestArrived = false;
            if (!live.ended && voices.contains(live)) {
                stepBackground();
                return true;
            }
            // the DJ has gone: back to the playlist (unless a new DJ is already waiting)
            if (liveIn == live.src) {
                liveIn = null;
            }
            live = null;
            if (background != null && !background.ended && voices.contains(background) && (background.total <= 0 || background.left() > RATE * 5L)) {
                // the playlist was going on silently: it fades back in where it is now
                current = background;
                current.fadeTo(1, FADE);
                addEvent("song", current.track.label(), current.track);
            }
            background = null;
            if (current == null && backgroundNext != null) {
                planned = backgroundNext; // the DJ left before the song underneath ended: the song that was next comes now
            }
            backgroundNext = null;
            addEvent("note", "The live DJ has left - back to the playlist.", null);
            return false;
        }
        return false;
    }

    /** During a live show: when the silent song ends, the next one from the playlists starts, silently too. */
    private void stepBackground() {
        if (background != null && !background.ended && voices.contains(background) && (background.total <= 0 || background.left() > 0)) {
            return;
        }
        RadioStore.Track t = backgroundNext != null ? backgroundNext : pickNext();
        backgroundNext = null;
        if (t == null) {
            background = null;
            return;
        }
        Source src;
        try {
            src = content.open(t);
        } catch (IOException e) {
            background = null;
            return;
        }
        background = new Voice(src, t, 0);
        voices.add(background);
    }

    private void stepTalk() {
        // stage 0: wait for the song to fade out; 1: main talk; 2: gap; 3: intro over the next song; 4: done
        if (talkStage == 0) {
            if (current == null || current.ended || (current.gain <= 0.001 && current.target <= 0.001)) {
                if (current != null) {
                    current.ended = true;
                    previous = current.track;
                }
                if (talkBreak.main != null) {
                    speech = startSpeech(talkBreak.main);
                    addEvent(talkBreak.mainKind, talkBreak.mainText, null);
                    if (talkBreak.story != null) {
                        content.storyAired(talkBreak.story);
                        songsSinceStory = 0;
                    }
                    talkStage = 1;
                } else {
                    talkStage = 2;
                    gapLeft = 0;
                }
            }
            return;
        }
        if (talkStage == 1) {
            if (speech.ended) {
                talkStage = 2;
                gapLeft = GAP;
            }
            return;
        }
        if (talkStage == 2) {
            gapLeft -= BLOCK;
            if (gapLeft > 0) {
                return;
            }
            RadioStore.Track next = planned != null ? planned : pickNext();
            planned = next;
            if (next == null) {
                finishTalk(null);
                return;
            }
            bed = startSong(next, 0, RATE);
            if (talkBreak.intro != null) {
                bed.fadeTo(BED, RATE);
                speech = startSpeech(talkBreak.intro);
                addEvent("intro", talkBreak.introText, null);
                talkStage = 3;
            } else {
                bed.fadeTo(1, RATE * 2);
                finishTalk(bed);
            }
            return;
        }
        if (talkStage == 3 && speech.ended) {
            bed.fadeTo(1, RATE * 2);
            finishTalk(bed);
        }
    }

    /**
     * Now and then, while a song plays, Alma says which station this is over it (the song dips, then comes back) -
     * like a presenter. Not over short tracks (jingles, ads), and not near the start or end of a song.
     */
    private void stepId() {
        if (idVoice != null) {
            if (idVoice.ended || !voices.contains(idVoice)) {
                if (idSong != null && idSong == current && !transitioning && current.target > 0.01 && current.target < 1) {
                    current.fadeTo(1, RATE);
                }
                idVoice = null;
                idSong = null;
            }
            return;
        }
        RadioStore.Station st = content.station();
        if (st == null || st.idEvery <= 0 || songsSinceId < st.idEvery || transitioning || current == null || current.track == null
                || current.track.durationMs < 90000 || current.frames < RATE * 20L || current.left() < RATE * 45L) {
            return;
        }
        if (idPrep == null) {
            idFor = current.track.id;
            idText = writer().stationId(st.djName, st.name, current.track, st.sayArtist);
            final String text = idText;
            final boolean ai = content.listeners() > 0;
            idPrep = prep.submit(() -> content.speak(text, st.voice, ai));
            return;
        }
        if (!idPrep.isDone()) {
            return;
        }
        short[] v = null;
        try {
            v = idPrep.get();
        } catch (Exception e) {
            v = null;
        }
        idPrep = null;
        songsSinceId = 0;
        if (v == null || v.length == 0 || current.track.id != idFor) {
            return;
        }
        current.fadeTo(DUCK, RATE / 2);
        idVoice = startSpeech(v);
        idSong = current;
        addEvent("id", idText, null);
    }

    private void finishTalk(Voice nextSong) {
        talking = false;
        talkBreak = null;
        speech = null;
        bed = null;
        current = nextSong;
        planned = null;
        plannedTalk = null;
        transitioning = false;
    }

    private Voice startSpeech(short[] samples) {
        Voice v = new Voice(new MemorySource(samples), null, 1.0);
        voices.add(v);
        return v;
    }

    private Voice startSong(RadioStore.Track t, double gain, int unused) {
        Source src;
        try {
            src = content.open(t);
        } catch (IOException e) {
            System.out.println("[radio] could not play " + t.label() + ": " + e.getMessage());
            src = new MemorySource(new short[0]);
        }
        Voice v = new Voice(src, t, gain);
        voices.add(v);
        songsSinceTalk++;
        songsSinceStory++;
        songsSinceMsg++;
        songsSinceId++;
        content.played(t);
        addEvent("song", t.label(), t);
        nowTalk = null;
        return v;
    }

    /** The next song from a shuffled bag; every song plays once before any repeats. */
    private RadioStore.Track pickNext() {
        List<RadioStore.Track> all = content.tracks();
        if (all.isEmpty()) {
            return null;
        }
        RadioStore.Track chosen = content.chooseNext(current != null && current.track != null ? current.track.id : 0);
        if (chosen != null) {
            upNext = null;
            return chosen;
        }
        java.util.Map<Long, RadioStore.Track> byId = new java.util.HashMap<Long, RadioStore.Track>();
        for (RadioStore.Track t : all) {
            byId.put(t.id, t);
            if (known.add(t.id) && !bag.isEmpty()) {
                // a song uploaded while on air joins this round at a random place
                List<Long> rest = new ArrayList<Long>(bag);
                rest.add(random.nextInt(rest.size() + 1), t.id);
                bag.clear();
                bag.addAll(rest);
            }
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            while (!bag.isEmpty()) {
                RadioStore.Track t = byId.get(bag.pollFirst());
                if (t != null) {
                    if (current != null && current.track != null && t.id == current.track.id && all.size() > 1) {
                        bag.addLast(t.id);
                        continue;
                    }
                    upNext = null;
                    return t;
                }
            }
            List<Long> ids = new ArrayList<Long>(byId.keySet());
            Collections.shuffle(ids, random);
            bag.addAll(ids);
        }
        return all.get(0);
    }

    /** Picks the next song and, when it is time, prepares what the DJ says before it. */
    private volatile String plannedRequestBy = "";

    /** Who requested the song that is up next ("" if nobody). */
    public String upNextRequestedBy() {
        return plannedRequestBy;
    }

    private void plan() {
        plannedRequestBy = "";
        RadioWriter.Request req = content.takeRequest();
        planned = null;
        if (req != null) {
            for (RadioStore.Track t : content.tracks()) {
                if (t.id == req.trackId) {
                    planned = t;
                    plannedRequestBy = req.name;
                }
            }
        }
        if (planned == null) {
            planned = pickNext();
        }
        upNext = planned;
        if (planned == null) {
            return;
        }
        RadioStore.Station st = content.station();
        if (st == null) {
            return;
        }
        if (talkThreshold < 0) {
            talkThreshold = Math.max(1, st.talkEvery + random.nextInt(3) - 1);
        }
        boolean isBreak = plannedRequestBy.isEmpty() && content.lastPickWasBreak();
        if (isBreak) {
            breakSongs.add(planned.id);
        } else if (st.msgEvery > 0 && messageWorthy(planned)) {
            content.prepareSongMessage(planned); // find out what it is about while this song plays
        }
        if (isBreak && !talkNow) {
            return; // ads and station IDs play straight, without an introduction
        }
        boolean due = st.talkEvery > 0 && songsSinceTalk >= talkThreshold;
        boolean storyDue = st.storyEvery > 0 && songsSinceStory >= st.storyEvery && content.nextStory() != null;
        boolean forced = talkNow;
        talkNow = false;
        boolean msgDue = st.msgEvery > 0 && songsSinceMsg >= st.msgEvery
                && (messageWorthy(planned) || messageWorthy(current == null ? null : current.track));
        boolean introOnly = !due && !forced && !storyDue && st.introsOn
                && (random.nextInt(4) == 0 || !plannedRequestBy.isEmpty());
        if (!due && !forced && !introOnly && !storyDue && !msgDue) {
            return;
        }
        final RadioStore.Track next = planned;
        final RadioStore.Track prev = current == null ? null : current.track;
        final boolean full = due || forced;
        final boolean ai = content.listeners() > 0;
        final String by = plannedRequestBy;
        plannedTalk = prep.submit(() -> prepare(st, next, prev, full, ai, by, storyDue, msgDue));
    }

    /** A real song (not an ad, jingle or station ID): at least 2 minutes long and not from a break. */
    boolean messageWorthy(RadioStore.Track t) {
        return t != null && t.durationMs >= 120000L && !breakSongs.contains(t.id);
    }

    private Break prepare(RadioStore.Station st, RadioStore.Track next, RadioStore.Track prev, boolean full, boolean ai,
                          String requestedBy, boolean story) {
        return prepare(st, next, prev, full, ai, requestedBy, story, false);
    }

    private Break prepare(RadioStore.Station st, RadioStore.Track next, RadioStore.Track prev, boolean full, boolean ai,
                          String requestedBy, boolean story, boolean msg) {
        RadioWriter writer = writer();
        Break b = new Break();
        String said = "";
        // the heart of a song: said after it (about the song that is ending) or before the next one - at random
        String msgBefore = null;
        if (msg && !story && !full) {
            boolean afterFirst = random.nextBoolean();
            for (int round = 0; round < 2 && said.isEmpty() && msgBefore == null; round++) {
                boolean after = (round == 0) == afterFirst;
                RadioStore.Track t = after ? prev : next;
                if (!messageWorthy(t)) {
                    continue;
                }
                String m = content.songMessage(t, after ? 0 : 90000L);
                if (m == null || m.isEmpty()) {
                    continue;
                }
                if (after) {
                    said = writer.messageAfter(t, m, st.sayArtist);
                    b.mainKind = "message";
                } else {
                    msgBefore = writer.messageBefore(t, m, requestedBy, st.sayArtist);
                }
                songsSinceMsg = 0;
            }
        }
        if (story) {
            // Alma's story: the next part of the book, then a song, then more of the story at a later break
            RadioStories.Part p = content.nextStory();
            if (p != null) {
                said = writer.story(p, st.djName, st.name);
                if (!said.isEmpty()) {
                    b.mainKind = "story";
                    b.story = p;
                }
            }
        }
        if (full && said.isEmpty()) {
            String first = "news".equals(lastTalkKind) ? "shout" : "news";
            for (String kind : new String[]{first, "news".equals(first) ? "shout" : "news"}) {
                if ("news".equals(kind) && st.newsOn) {
                    List<NewsService.Item> items = RadioWriter.chooseNews(content.news(), usedNews, 3);
                    if (items.size() >= 1) {
                        said = writer.news(items, st.djName, st.name, ai);
                        if (!said.isEmpty()) {
                            for (NewsService.Item it : items) {
                                usedNews.add(it.id);
                            }
                            b.mainKind = "news";
                            break;
                        }
                    }
                } else if ("shout".equals(kind) && st.shoutOn) {
                    RadioWriter.Shout s = content.bestShout(usedShouts);
                    if (s != null) {
                        said = writer.shout(s, st.djName, ai);
                        if (!said.isEmpty()) {
                            usedShouts.add(s.user + "|" + s.text);
                            b.mainKind = "shout";
                            break;
                        }
                    }
                }
            }
        }
        if (!said.isEmpty()) {
            if (!"story".equals(b.mainKind) && !"message".equals(b.mainKind)) {
                lastTalkKind = b.mainKind;
            }
            b.mainText = said;
            b.main = content.speak(said, st.voice, ai);
            if (b.main == null) {
                b.mainText = "";
                b.story = null;
                addEvent("note", "The DJ could not speak: " + content.voiceProblem(), null);
            }
        } else if (full) {
            if (!st.newsOn && !st.shoutOn) {
                addEvent("note", "News and shout-outs are switched off for this station - only song intros.", null);
            } else {
                addEvent("note", "Nothing to talk about yet: no news stories from the last 14 days"
                        + " and no public chat posts from the last 12 hours.", null);
            }
        }
        if (st.introsOn || b.main != null || msgBefore != null) {
            String intro = msgBefore != null ? msgBefore
                    : b.story != null && b.main != null ? writer.storyBridge(next, b.story, st.sayArtist)
                    : "message".equals(b.mainKind) && b.main != null ? writer.intro(next, null, st.name, false, requestedBy, st.promo, st.sayArtist)
                    : writer.intro(next, prev, st.name, "news".equals(b.mainKind) && b.main != null, requestedBy, st.promo, st.sayArtist);
            short[] voice = content.speak(intro, st.voice, ai);
            if (voice != null) {
                b.intro = voice;
                b.introText = intro;
            } else if (b.main == null) {
                addEvent("note", "The DJ could not speak: " + content.voiceProblem(), null);
            }
        }
        return b;
    }

    private volatile RadioWriter writer;

    public void setWriter(RadioWriter w) {
        this.writer = w;
    }

    private RadioWriter writer() {
        RadioWriter w = writer;
        return w != null ? w : new RadioWriter(null, random);
    }

    // ---- Mixing ----

    private int mixBlock(int[] acc, short[] out) {
        java.util.Arrays.fill(acc, 0);
        for (int i = voices.size() - 1; i >= 0; i--) {
            Voice v = voices.get(i);
            if (v.ended) {
                v.src.close();
                voices.remove(i);
                continue;
            }
            int got;
            try {
                got = v.src.read(v.buf, BLOCK);
            } catch (IOException e) {
                got = -1;
            }
            if (got <= 0) {
                v.ended = true;
                continue;
            }
            for (int f = 0; f < got; f++) {
                if (v.gain != v.target) {
                    if (v.gain < v.target) {
                        v.gain = Math.min(v.target, v.gain + v.step);
                    } else {
                        v.gain = Math.max(v.target, v.gain - v.step);
                    }
                }
                acc[2 * f] += (int) (v.buf[2 * f] * v.gain);
                acc[2 * f + 1] += (int) (v.buf[2 * f + 1] * v.gain);
            }
            v.frames += got;
            if (got < BLOCK) {
                // short read: keep reading next time; the decoder may just be slow
                if (v.track == null) {
                    v.ended = v.src instanceof MemorySource && ((MemorySource) v.src).pos >= ((MemorySource) v.src).data.length;
                }
            }
            if (v.target <= 0 && v.gain <= 0.0005 && v != current && v != background) {
                v.ended = true;
            }
        }
        for (int i = 0; i < BLOCK * 2; i++) {
            int s = acc[i];
            out[i] = (short) (s > 32767 ? 32767 : (s < -32768 ? -32768 : s));
        }
        return BLOCK;
    }

    // ---- Song sources ----

    /** Plays a song through an FFmpeg decoder process. */
    public static final class ProcessSource implements Source {
        private final Process process;
        private final InputStream in;
        private byte[] raw = new byte[BLOCK * 4];

        public ProcessSource(Process process) {
            this.process = process;
            this.in = process.getInputStream();
            try {
                process.getOutputStream().close();
            } catch (IOException e) {
                // not needed
            }
        }

        @Override
        public int read(short[] buf, int frames) throws IOException {
            int want = frames * 4;
            if (raw.length < want) {
                raw = new byte[want];
            }
            int have = 0;
            while (have < want) {
                int n = in.read(raw, have, want - have);
                if (n < 0) {
                    break;
                }
                have += n;
            }
            int got = have / 4;
            if (got == 0) {
                return -1;
            }
            for (int i = 0; i < got * 2; i++) {
                buf[i] = (short) ((raw[2 * i] & 0xff) | (raw[2 * i + 1] << 8));
            }
            return got;
        }

        @Override
        public void close() {
            process.destroy();
            try {
                in.close();
            } catch (IOException e) {
                // closing
            }
        }
    }

    // ---- The MP3 stream for listeners ----

    /**
     * Turns the mixed sound into MP3 with FFmpeg and hands it to every listener. A new listener first gets the
     * last few seconds so playback starts at once.
     */
    public static final class Broadcast implements Sink {
        private static final int BURST = 64 * 1024;

        private final AudioTools tools;
        private final int kbps;
        private Process encoder;
        private OutputStream toEncoder;
        private byte[] pcm = new byte[BLOCK * 4];
        private final byte[] recent = new byte[BURST];
        private int recentLen;
        private final List<BlockingQueue<byte[]>> listeners = new CopyOnWriteArrayList<BlockingQueue<byte[]>>();

        public Broadcast(AudioTools tools, int kbps) {
            this.tools = tools;
            this.kbps = kbps;
        }

        private void ensureEncoder() throws IOException {
            if (encoder != null && encoder.isAlive()) {
                return;
            }
            encoder = tools.encoder(kbps);
            toEncoder = encoder.getOutputStream();
            final InputStream out = encoder.getInputStream();
            Thread t = new Thread(() -> {
                byte[] b = new byte[4096];
                try {
                    int n;
                    while ((n = out.read(b)) > 0) {
                        byte[] chunk = java.util.Arrays.copyOf(b, n);
                        synchronized (recent) {
                            if (n >= BURST) {
                                System.arraycopy(chunk, n - BURST, recent, 0, BURST);
                                recentLen = BURST;
                            } else {
                                int keep = Math.min(recentLen, BURST - n);
                                System.arraycopy(recent, recentLen - keep, recent, 0, keep);
                                System.arraycopy(chunk, 0, recent, keep, n);
                                recentLen = keep + n;
                            }
                        }
                        for (BlockingQueue<byte[]> q : listeners) {
                            if (!q.offer(chunk)) {
                                q.clear();
                                q.offer(new byte[0]);
                                listeners.remove(q);
                            }
                        }
                    }
                } catch (IOException e) {
                    // encoder closed
                }
            }, "radio-mp3");
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void write(short[] buf, int frames) throws IOException {
            ensureEncoder();
            int n = frames * 2;
            if (pcm.length < n * 2) {
                pcm = new byte[n * 2];
            }
            for (int i = 0; i < n; i++) {
                pcm[2 * i] = (byte) buf[i];
                pcm[2 * i + 1] = (byte) (buf[i] >> 8);
            }
            try {
                toEncoder.write(pcm, 0, n * 2);
                toEncoder.flush();
            } catch (IOException e) {
                encoder.destroy();
                encoder = null;
            }
        }

        public int listenerCount() {
            return listeners.size();
        }

        /** Sends the stream to one listener until they disconnect (blocks). */
        public void serve(OutputStream os) throws IOException {
            BlockingQueue<byte[]> q = new ArrayBlockingQueue<byte[]>(400);
            byte[] start;
            synchronized (recent) {
                start = java.util.Arrays.copyOf(recent, recentLen);
            }
            listeners.add(q);
            try {
                if (start.length > 0) {
                    os.write(start);
                    os.flush();
                }
                while (true) {
                    byte[] chunk = q.poll(20, TimeUnit.SECONDS);
                    if (chunk == null || chunk.length == 0) {
                        return;
                    }
                    os.write(chunk);
                    os.flush();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                listeners.remove(q);
            }
        }

        public void close() {
            for (BlockingQueue<byte[]> q : listeners) {
                q.offer(new byte[0]);
            }
            if (encoder != null) {
                encoder.destroy();
            }
        }
    }
}
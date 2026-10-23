package com.vstream.Vidiserver;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.LongSupplier;

/**
 * Chooses a station's next song from its playlists:
 *   0. an "Immediate" playlist that was switched on (plays its songs once, in order, then switches itself off),
 *   1. a scheduled show that is on now (its own playlist),
 *   2. otherwise an "every N songs" / "every N minutes" playlist that is due (jingles, station IDs, ads): one song, or
 *      the whole list in order (an ad break - nothing else plays until the break is over); playlists set to skip shows
 *      wait until the show is over,
 *   3. otherwise a rotation playlist, picked by weight (timed ones only in their hours).
 * A show set to play its list once (or one song) gives the air back to the rotation when it has played it.
 * While a break is due, listener requests wait (breaks come first, as in Centova Cast).
 * Inside a playlist songs play in order or shuffled; a song is not repeated within the station's no-repeat minutes
 * while others are available. Stations without playlists return null (the engine then shuffles all songs).
 * Listener requests are handled before this by the engine.
 */
public final class RadioScheduler {

    private final RadioStore store;
    private final LongSupplier clock;
    private final Random random;

    /** Per station: songs since each interval playlist last played, where each in-order playlist is, the show on. */
    private static final class State {
        final Map<Long, Integer> sinceInterval = new HashMap<Long, Integer>();
        final Map<Long, Integer> position = new HashMap<Long, Integer>();
        final Map<Long, Long> lastPlayed = new HashMap<Long, Long>();
        /** "every N minutes" playlists: when each last played (first seen = now, so nothing fires at start-up). */
        final Map<Long, Long> intervalAt = new HashMap<Long, Long>();
        String show = "";
        String playlist = "";
        /** The ad break playing now (an interval playlist that plays all its songs): its id and the next place. */
        long breakList = 0;
        /** The last song chosen came from an Immediate, interval or ad-break playlist. */
        boolean lastBreak = false;
        int breakPos = 0;
        /** Shows that play their list once: which run (show id + start day) and the songs still to play. */
        final Map<Long, String> showRun = new HashMap<Long, String>();
        final Map<Long, List<Long>> showLeft = new HashMap<Long, List<Long>>();
    }

    private final Map<Long, State> states = new HashMap<Long, State>();

    public RadioScheduler(RadioStore store, LongSupplier clock, Random random) {
        this.store = store;
        this.clock = clock;
        this.random = random;
    }

    /** The scheduled show on now ("" if none) - what was last picked from. */
    public synchronized String currentShow(long station) {
        State st = states.get(station);
        return st == null ? "" : st.show;
    }

    /** The playlist the last song came from ("" when the engine chose). */
    public synchronized String currentPlaylist(long station) {
        State st = states.get(station);
        return st == null ? "" : st.playlist;
    }

    /** The playlist the last song came from (null when the engine chose, or a request). */
    public RadioStore.Playlist currentPlaylistObj(long station) {
        String name = currentPlaylist(station);
        if (name.isEmpty()) {
            return null;
        }
        for (RadioStore.Playlist p : store.playlists(station)) {
            if (p.name.equals(name)) {
                return p;
            }
        }
        return null;
    }

    /** The scheduled show on at this moment (by the clock, not by what was picked), or null. */
    public synchronized RadioStore.Playlist showOnNow(long station) {
        RadioStore.Station s = store.station(station);
        if (s == null) {
            return null;
        }
        ZonedDateTime t = now(s);
        State st = states.get(station);
        String playing = st == null ? "" : st.show;
        RadioStore.Playlist best = null;
        for (RadioStore.Playlist p : store.playlists(station)) {
            if (p.tracks.isEmpty() || !p.onAt(t)) {
                continue;
            }
            if ("no".equals(p.interrupt) && p.name.equals(playing)) {
                return p; // it is on and may not be interrupted: it goes on to its own end
            }
            if (best == null || p.start > best.start) {
                best = p; // when shows overlap, the one that started last wins
            }
        }
        return best;
    }

    /** The next song, or null to let the engine shuffle all songs. currentId = the song playing now (0 = none). */
    public synchronized RadioStore.Track next(long station, long currentId) {
        RadioStore.Track t = choose(station, currentId);
        State st = states.get(station);
        if (st != null) {
            RadioStore.Playlist from = null;
            for (RadioStore.Playlist p : store.playlists(station)) {
                if (p.name.equals(st.playlist)) {
                    from = p;
                }
            }
            st.lastBreak = t != null && from != null && (RadioStore.Playlist.INTERVAL.equals(from.type) || RadioStore.Playlist.IMMEDIATE.equals(from.type));
        }
        return t;
    }

    /** Whether the last song chosen is from an Immediate, interval or ad-break playlist. */
    public synchronized boolean lastPickWasBreak(long station) {
        State st = states.get(station);
        return st != null && st.lastBreak;
    }

    private RadioStore.Track choose(long station, long currentId) {
        RadioStore.Station s = store.station(station);
        if (s == null) {
            return null;
        }
        List<RadioStore.Playlist> lists = store.playlists(station);
        State st = states.computeIfAbsent(station, k -> new State());
        Map<Long, RadioStore.Track> byId = new HashMap<Long, RadioStore.Track>();
        for (RadioStore.Track t : store.tracks(station)) {
            byId.put(t.id, t);
        }
        long now = clock.getAsLong();

        // 0. an Immediate playlist: its songs in order, once, then it switches itself off
        for (RadioStore.Playlist p : lists) {
            if (!RadioStore.Playlist.IMMEDIATE.equals(p.type) || !p.enabled) {
                continue;
            }
            int pos = st.position.getOrDefault(p.id, -1) + 1;
            RadioStore.Track t = null;
            while (pos < p.tracks.size() && t == null) {
                t = byId.get(p.tracks.get(pos));
                if (t == null) {
                    pos++;
                }
            }
            if (t == null) {
                st.position.remove(p.id);
                store.setPlaylistEnabled(p.id, false);
                continue;
            }
            st.position.put(p.id, pos);
            if (pos >= p.tracks.size() - 1) {
                st.position.remove(p.id);
                store.setPlaylistEnabled(p.id, false); // the last one: done after this song
            }
            st.playlist = p.name;
            st.lastPlayed.put(t.id, now);
            return t;
        }

        // 1. a scheduled show
        ZonedDateTime zt = now(s);
        RadioStore.Playlist show = showOnNow(station);
        RadioStore.Track fromShow = null;
        if (show != null && !show.style.isEmpty()) {
            // a show that plays its list once (or one song): when that is done the rotation comes back
            String run = show.id + ":" + show.occurrence(zt);
            if (!run.equals(st.showRun.get(show.id))) {
                st.showRun.put(show.id, run);
                List<Long> q = new ArrayList<Long>();
                for (Long id : show.tracks) {
                    if (byId.containsKey(id)) {
                        q.add(id);
                    }
                }
                if (!show.sequential || "one".equals(show.style)) {
                    java.util.Collections.shuffle(q, random);
                }
                st.showLeft.put(show.id, "one".equals(show.style) && q.size() > 1 ? new ArrayList<Long>(q.subList(0, 1)) : q);
            }
            List<Long> left = st.showLeft.get(show.id);
            while (left != null && !left.isEmpty() && fromShow == null) {
                fromShow = byId.get(left.remove(0));
            }
            if (fromShow == null) {
                show = null; // its songs have played
            }
        }
        st.show = show == null ? "" : show.name;

        // 2a. an ad break that is playing: its next song
        if (st.breakList != 0) {
            RadioStore.Playlist b = store.playlist(st.breakList);
            RadioStore.Track t = null;
            if (b != null && b.enabled && (show == null || b.inShows)) {
                while (st.breakPos < b.tracks.size() && t == null) {
                    t = byId.get(b.tracks.get(st.breakPos++));
                }
            }
            if (t != null) {
                if (fromShow != null) {
                    st.showLeft.get(show.id).add(0, fromShow.id); // the show's song waits for the break
                }
                endBreakIfDone(st, b, byId, now);
                st.playlist = b.name;
                st.lastPlayed.put(t.id, now);
                return t;
            }
            st.breakList = 0;
            if (b != null) {
                st.intervalAt.put(b.id, now); // "every N minutes" counts from the end of the break
            }
        }

        // 2. an interval playlist that is due (counts songs of the main music only)
        for (RadioStore.Playlist p : lists) {
            if (!due(st, p, show, now, true)) {
                continue;
            }
            RadioStore.Track t = null;
            if ("all".equals(p.style)) {
                int pos = 0;
                while (pos < p.tracks.size() && t == null) {
                    t = byId.get(p.tracks.get(pos++));
                }
                if (t != null) {
                    st.breakList = p.id;
                    st.breakPos = pos;
                    endBreakIfDone(st, p, byId, now);
                }
            } else {
                t = fromList(st, p, byId, s, currentId, now);
            }
            if (t != null) {
                if (fromShow != null) {
                    st.showLeft.get(show.id).add(0, fromShow.id);
                }
                st.sinceInterval.put(p.id, 0);
                st.intervalAt.put(p.id, now);
                st.playlist = p.name;
                st.lastPlayed.put(t.id, now);
                return t;
            }
        }

        if (fromShow != null) {
            countMain(st, lists);
            st.playlist = show.name;
            st.lastPlayed.put(fromShow.id, now);
            return fromShow;
        }
        RadioStore.Playlist from = show;
        if (from == null) {
            // 3. rotation by weight (timed playlists only in their hours - unless every one is timed)
            List<RadioStore.Playlist> rot = new ArrayList<RadioStore.Playlist>();
            boolean anyAllDay = false;
            for (RadioStore.Playlist p : lists) {
                if (RadioStore.Playlist.GENERAL.equals(p.type) && p.enabled && p.weight > 0 && !p.tracks.isEmpty() && !p.timed) {
                    anyAllDay = true;
                }
            }
            int total = 0;
            for (RadioStore.Playlist p : lists) {
                if (RadioStore.Playlist.GENERAL.equals(p.type) && p.enabled && p.weight > 0 && !p.tracks.isEmpty() && (!anyAllDay || p.inHours(zt))) {
                    rot.add(p);
                    total += p.weight;
                }
            }
            if (rot.isEmpty()) {
                st.playlist = "";
                countMain(st, lists);
                if (lists.stream().noneMatch(p -> p.enabled && !p.tracks.isEmpty())) {
                    return null; // no playlists in use: the engine shuffles everything
                }
                // only interval playlists (or shows that are off): main music = all songs
                RadioStore.Track t = pick(new ArrayList<Long>(byId.keySet()), byId, s, currentId, now, st, false, -1);
                if (t != null) {
                    st.lastPlayed.put(t.id, now);
                }
                return t;
            }
            int r = random.nextInt(total);
            for (RadioStore.Playlist p : rot) {
                r -= p.weight;
                if (r < 0) {
                    from = p;
                    break;
                }
            }
        }
        RadioStore.Track t = fromList(st, from, byId, s, currentId, now);
        countMain(st, lists);
        st.playlist = t == null ? "" : from.name;
        if (t != null) {
            st.lastPlayed.put(t.id, now);
        }
        return t;
    }

    /** After the last song of a break has been chosen, the break is over (requests can follow straight away). */
    private static void endBreakIfDone(State st, RadioStore.Playlist b, Map<Long, RadioStore.Track> byId, long now) {
        for (int i = st.breakPos; i < b.tracks.size(); i++) {
            if (byId.containsKey(b.tracks.get(i))) {
                return;
            }
        }
        st.breakList = 0;
        st.intervalAt.put(b.id, now);
    }

    /** Whether an interval playlist is due now (look = true may note when an "every N minutes" one was first seen). */
    private static boolean due(State st, RadioStore.Playlist p, RadioStore.Playlist show, long now, boolean note) {
        if (!RadioStore.Playlist.INTERVAL.equals(p.type) || !p.enabled || p.tracks.isEmpty() || (show != null && !p.inShows)) {
            return false;
        }
        if ("minutes".equals(p.unit)) {
            Long at = st.intervalAt.get(p.id);
            if (at == null) {
                if (note) {
                    st.intervalAt.put(p.id, now);
                }
                return false;
            }
            return now - at >= p.every * 60000L;
        }
        return st.sinceInterval.getOrDefault(p.id, 0) >= p.every;
    }

    /**
     * Whether an Immediate playlist, an ad break or a due interval playlist comes next - listener requests wait for it
     * (Centova Cast also plays interval playlists before requests).
     */
    public synchronized boolean breakDue(long station) {
        State st = states.get(station);
        if (st == null) {
            return false;
        }
        if (st.breakList != 0) {
            return true;
        }
        RadioStore.Playlist show = showOnNow(station);
        long now = clock.getAsLong();
        for (RadioStore.Playlist p : store.playlists(station)) {
            if (RadioStore.Playlist.IMMEDIATE.equals(p.type) && p.enabled && !p.tracks.isEmpty()) {
                return true;
            }
            if (due(st, p, show, now, false)) {
                return true;
            }
        }
        return false;
    }

    /** A song that did not come from here (a listener request) still counts for the "every N songs" playlists. */
    public synchronized void countSong(long station) {
        State st = states.get(station);
        if (st != null) {
            countMain(st, store.playlists(station));
        }
    }

    private static void countMain(State st, List<RadioStore.Playlist> lists) {
        for (RadioStore.Playlist p : lists) {
            if (RadioStore.Playlist.INTERVAL.equals(p.type)) {
                st.sinceInterval.merge(p.id, 1, Integer::sum);
            }
        }
    }

    private RadioStore.Track fromList(State st, RadioStore.Playlist p, Map<Long, RadioStore.Track> byId,
                                      RadioStore.Station s, long currentId, long now) {
        return pick(p.tracks, byId, s, currentId, now, st, p.sequential, p.id);
    }

    /** In order: the next one after the last; shuffled: a random one not played recently (else the least recent). */
    private RadioStore.Track pick(List<Long> ids, Map<Long, RadioStore.Track> byId, RadioStore.Station s, long currentId,
                                  long now, State st, boolean sequential, long listId) {
        List<Long> usable = new ArrayList<Long>();
        for (Long id : ids) {
            if (byId.containsKey(id)) {
                usable.add(id);
            }
        }
        if (usable.isEmpty()) {
            return null;
        }
        if (sequential) {
            int pos = st.position.getOrDefault(listId, -1);
            for (int i = 1; i <= usable.size(); i++) {
                int at = (pos + i) % usable.size();
                long id = usable.get(at);
                if (id != currentId || usable.size() == 1) {
                    st.position.put(listId, at);
                    return byId.get(id);
                }
            }
        }
        long window = s.noRepeatMin * 60000L;
        List<Long> fresh = new ArrayList<Long>();
        for (Long id : usable) {
            if (id == currentId && usable.size() > 1) {
                continue;
            }
            long last = Math.max(st.lastPlayed.getOrDefault(id, 0L), byId.get(id).lastPlayed);
            if (window <= 0 || last == 0 || now - last >= window) {
                fresh.add(id);
            }
        }
        if (!fresh.isEmpty()) {
            return byId.get(fresh.get(random.nextInt(fresh.size())));
        }
        // everything was played recently: the one played longest ago
        long bestId = -1;
        long bestTime = Long.MAX_VALUE;
        for (Long id : usable) {
            if (id == currentId && usable.size() > 1) {
                continue;
            }
            long last = Math.max(st.lastPlayed.getOrDefault(id, 0L), byId.get(id).lastPlayed);
            if (last < bestTime) {
                bestTime = last;
                bestId = id;
            }
        }
        return byId.get(bestId);
    }

    private ZonedDateTime now(RadioStore.Station s) {
        ZoneId z;
        try {
            z = ZoneId.of(s.timezone);
        } catch (RuntimeException e) {
            z = ZoneOffset.UTC;
        }
        return Instant.ofEpochMilli(clock.getAsLong()).atZone(z);
    }

    /** Forget a station's counters (e.g. when it is deleted). */
    public synchronized void forget(long station) {
        states.remove(station);
    }
}
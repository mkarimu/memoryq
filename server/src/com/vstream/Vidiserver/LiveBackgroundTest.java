package com.vstream.Vidiserver;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** While a live DJ is on, the playlist goes on silently underneath and comes back where it is when the DJ leaves. */
public final class LiveBackgroundTest {

    private static int failures = 0;
    private static final int R = RadioEngine.RATE;

    /** A song: a steady level for its length. */
    static final class Level implements RadioEngine.Source {
        final short value;
        long left;

        Level(int value, long frames) {
            this.value = (short) value;
            this.left = frames;
        }

        @Override
        public int read(short[] buf, int frames) {
            if (left <= 0) {
                return -1;
            }
            int n = (int) Math.min(frames, left);
            for (int i = 0; i < n * 2; i++) {
                buf[i] = value;
            }
            left -= n;
            return n;
        }

        @Override
        public void close() {
        }
    }

    public static void main(String[] args) throws Exception {
        final List<RadioStore.Track> tracks = new ArrayList<RadioStore.Track>();
        for (int i = 1; i <= 3; i++) {
            RadioStore.Track t = new RadioStore.Track();
            t.id = i;
            t.title = "Song " + (char) ('A' + i - 1);
            t.durationMs = 30000;
            tracks.add(t);
        }
        final RadioStore.Station st = new RadioStore.Station();
        st.id = 1;
        st.name = "Test FM";
        st.talkEvery = 0;
        st.introsOn = false;
        st.idEvery = 0;
        final int[] next = {0};
        RadioEngine.Content c = new RadioEngine.Content() {
            @Override
            public RadioStore.Station station() {
                return st;
            }

            @Override
            public List<RadioStore.Track> tracks() {
                return tracks;
            }

            @Override
            public RadioEngine.Source open(RadioStore.Track t) {
                return new Level((int) t.id * 1000, t.durationMs * R / 1000);
            }

            @Override
            public short[] speak(String text, String voice) {
                return new short[0];
            }

            @Override
            public List<NewsService.Item> news() {
                return new ArrayList<NewsService.Item>();
            }

            @Override
            public RadioWriter.Shout bestShout(java.util.Set<String> used) {
                return null;
            }

            @Override
            public int listeners() {
                return 0;
            }

            @Override
            public void played(RadioStore.Track t) {
            }

            @Override
            public RadioStore.Track chooseNext(long currentId) {
                return tracks.get(next[0]++ % tracks.size());
            }
        };
        final RadioEngine[] holder = new RadioEngine[1];
        final int[] at = new int[60]; // the level heard at each second
        final RadioEngine.Source dj = new Level(7, 25L * R); // a live DJ for 25 seconds
        RadioEngine.Sink sink = new RadioEngine.Sink() {
            long frames;
            boolean live;

            @Override
            public void write(short[] buf, int n) {
                long sec = frames / R;
                if (sec < at.length && (frames % R) < n) {
                    at[(int) sec] = buf[0];
                }
                frames += n;
                if (!live && frames >= 10L * R) {
                    live = true;
                    holder[0].goLive(dj, "Kali");
                }
                if (frames >= 50L * R) {
                    holder[0].stop();
                }
            }
        };
        RadioEngine e = new RadioEngine(1, c, sink, false, new Random(1));
        e.setQuietNotes(true);
        holder[0] = e;
        Thread th = new Thread(e);
        th.start();
        th.join(60000);
        StringBuilder trace = new StringBuilder();
        for (int i = 0; i < 50; i += 2) {
            trace.append(i).append("s:").append(at[i]).append(' ');
        }
        System.out.println("      " + trace);
        java.util.List<RadioEngine.Event> evs = e.recent(40);
        java.util.Collections.reverse(evs);
        for (RadioEngine.Event ev : evs) {
            System.out.println("      " + ev.kind + ": " + ev.text);
        }
        checkTrue("song A before the DJ", at[5] == 1000);
        checkTrue("only the DJ is heard while live (A, then B, play silently)", at[15] == 7 && at[25] == 7 && at[32] == 7);
        checkTrue("when the DJ leaves, song B comes back where it is - not a new song", at[41] == 2000 && at[45] == 2000);
        boolean resumedB = false;
        for (RadioEngine.Event ev : e.recent(40)) {
            resumedB |= "song".equals(ev.kind) && ev.text.contains("Song B");
        }
        checkTrue("the Studio shows song B again", resumedB);
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
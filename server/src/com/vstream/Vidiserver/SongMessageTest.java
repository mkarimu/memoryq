package com.vstream.Vidiserver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Alma tells what a song is about: worked out once ahead of time, said before or after the song, never for jingles. */
public final class SongMessageTest {

    private static int failures = 0;
    private static final int R = RadioEngine.RATE;

    /** Pretends to be OpenAI: the "words" of a song are the file's text; "NONE" when there are none. */
    static final class FakeAi implements RadioWriter.Ai {
        final AtomicInteger listened = new AtomicInteger();

        @Override
        public boolean ready() {
            return true;
        }

        @Override
        public String transcribe(Path audio) throws IOException {
            listened.incrementAndGet();
            return new String(Files.readAllBytes(audio), "UTF-8");
        }

        @Override
        public String write(String instructions, String input) {
            if (!instructions.contains("heart")) {
                return "";
            }
            if (input.contains("love")) {
                return "Love is patient, and the people who stay with us through hard times are the real treasure.";
            }
            return "NONE";
        }
    }

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace();
            System.out.println("FAILED: " + t);
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        Path dir = Files.createTempDirectory("memoryq-songmsg");
        RadioStore store = new RadioStore(dir.resolve("radio.txt"), dir.resolve("music"));
        store.init();
        long now = 1792700000000L;
        RadioStore.Station st = store.createStation("Heart FM", "", "", RadioStore.STATUS_ACTIVE, now);
        String words = "when the night is long and the road is hard you stay with me my love you never leave my side we hold on together";
        RadioStore.Track love = store.addTrack(st.id, "love.mp3", "mp3", words.getBytes("UTF-8"), "Stay With Me", "Amina", "", 200000, now);
        RadioStore.Track instr = store.addTrack(st.id, "beat.mp3", "mp3", "".getBytes("UTF-8"), "Night Drive", "DJ K", "", 180000, now);
        RadioStore.Track jingle = store.addTrack(st.id, "id.mp3", "mp3", "heart fm your love station love love love love love love love".getBytes("UTF-8"),
                "Station ID", "", "", 15000, now);
        FakeAi ai = new FakeAi();

        System.out.println("-- Listening to a song once --");
        SongMessages sm = new SongMessages(dir.resolve("radio-messages.txt"), store, null, ai, () -> 1792700000000L, 300);
        sm.init();
        sm.request(love);
        String m = sm.await(love, 5000);
        checkTrue("the message of a song with words (" + m + ")", m != null && m.contains("people who stay with us"));
        sm.request(love);
        Thread.sleep(200);
        checkTrue("worked out once only", ai.listened.get() == 1);
        sm.request(instr);
        checkTrue("an instrumental has none", sm.await(instr, 5000) == null && "".equals(sm.known(instr.id)));
        sm.request(jingle);
        checkTrue("short tracks (jingles, IDs) are never listened to", sm.await(jingle, 300) == null && sm.known(jingle.id) == null);
        checkTrue("the words of the song are not kept - only the message",
                !new String(Files.readAllBytes(dir.resolve("radio-messages.txt")), "UTF-8").contains("night is long"));
        SongMessages again = new SongMessages(dir.resolve("radio-messages.txt"), store, null, ai, () -> 1792700000000L, 300);
        again.init();
        checkTrue("kept across a restart", again.known(love.id) != null && again.known(love.id).contains("treasure"));

        System.out.println("-- On air: before or after the song, at random --");
        boolean sawBefore = false, sawAfter = false, aboutJingle = false;
        for (int seed = 1; seed <= 8 && !(sawBefore && sawAfter); seed++) {
            List<RadioEngine.Event> ev = play(store, st.id, love, instr, jingle, sm, seed);
            for (RadioEngine.Event e : ev) {
                if ("message".equals(e.kind) && e.text.contains("treasure")) {
                    sawAfter = true;
                    aboutJingle |= e.text.contains("Station ID");
                }
                if ("intro".equals(e.kind) && e.text.contains("treasure")) {
                    sawBefore = true;
                    aboutJingle |= e.text.contains("Station ID");
                }
            }
        }
        checkTrue("Alma says it before the song (in the intro)", sawBefore);
        checkTrue("and after the song (\"That was ...\")", sawAfter);
        checkTrue("never about the jingle", !aboutJingle);
        RadioStore.Station off = store.station(st.id);
        checkTrue("off by default for stations", off.msgEvery == 0);
        off.msgEvery = 3;
        store.updateStation(off);
        store.flushIfDirty();
        RadioStore reread = new RadioStore(dir.resolve("radio.txt"), dir.resolve("music"));
        reread.init();
        checkTrue("the setting is kept", reread.station(st.id).msgEvery == 3);

        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** About six songs of air time on a station that tells a song's message after every song. */
    private static List<RadioEngine.Event> play(RadioStore store, long sid, RadioStore.Track love, RadioStore.Track instr,
                                                RadioStore.Track jingle, SongMessages sm, int seed) throws Exception {
        final RadioStore.Station st = store.station(sid);
        st.talkEvery = 0;
        st.introsOn = false;
        st.idEvery = 0;
        st.msgEvery = 1;
        final List<RadioStore.Track> order = new ArrayList<RadioStore.Track>();
        for (int i = 0; i < 5; i++) {
            order.add(love);
            order.add(jingle);
            order.add(instr);
        }
        final int[] next = {0};
        RadioEngine.Content c = new RadioEngine.Content() {
            @Override
            public RadioStore.Station station() {
                return st;
            }

            @Override
            public List<RadioStore.Track> tracks() {
                List<RadioStore.Track> all = new ArrayList<RadioStore.Track>();
                all.add(love);
                all.add(instr);
                all.add(jingle);
                return all;
            }

            @Override
            public RadioEngine.Source open(RadioStore.Track t) {
                return new LiveBackgroundTest.Level(1000, t.durationMs * R / 1000);
            }

            @Override
            public short[] speak(String text, String voice) {
                return new short[R / 2];
            }

            @Override
            public List<NewsService.Item> news() {
                return new ArrayList<NewsService.Item>();
            }

            @Override
            public RadioWriter.Shout bestShout(Set<String> used) {
                return null;
            }

            @Override
            public int listeners() {
                return 1;
            }

            @Override
            public void played(RadioStore.Track t) {
            }

            @Override
            public RadioStore.Track chooseNext(long currentId) {
                return order.get(next[0]++ % order.size());
            }

            @Override
            public void prepareSongMessage(RadioStore.Track t) {
                sm.request(t);
            }

            @Override
            public String songMessage(RadioStore.Track t, long waitMs) {
                return sm.await(t, waitMs);
            }
        };
        final RadioEngine[] holder = new RadioEngine[1];
        RadioEngine.Sink sink = new RadioEngine.Sink() {
            long frames;

            @Override
            public void write(short[] buf, int n) {
                frames += n;
                if (frames >= 1500L * R) {
                    holder[0].stop();
                }
            }
        };
        RadioEngine e = new RadioEngine(sid, c, sink, false, new Random(seed));
        e.setQuietNotes(true);
        holder[0] = e;
        Thread th = new Thread(e);
        th.start();
        th.join(120000);
        return e.recent(200);
    }

    private static void checkTrue(String name, boolean ok) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + name);
        if (!ok) {
            failures++;
        }
    }
}
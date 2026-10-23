package com.vstream.Vidiserver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Alma: song titles without artists' names, story time (books read part by part between songs), the station name over songs. */
public final class RadioStoryTest {

    private static int failures = 0;
    private static final int R = RadioEngine.RATE;

    public static void main(String[] args) throws Exception {
        System.out.println("-- Song intros without the artist's name --");
        RadioWriter w = new RadioWriter(null, new Random(3));
        RadioStore.Track next = new RadioStore.Track();
        next.title = "Malaika";
        next.artist = "Fadhili William";
        RadioStore.Track prev = new RadioStore.Track();
        prev.title = "Sina Makosa";
        prev.artist = "Les Wanyika";
        boolean anyArtist = false, allTitle = true;
        for (int i = 0; i < 30; i++) {
            String s = w.intro(next, prev, "Hits FM", false, "", "", false);
            anyArtist |= s.contains("Fadhili") || s.contains("Wanyika");
            allTitle &= s.contains("Malaika");
        }
        checkTrue("only the song's title is said", !anyArtist && allTitle);
        checkTrue("with 'say artists' on, the artist is said", w.intro(next, null, "Hits FM", false, "", "", true).contains("Fadhili William"));
        RadioStore.Station fresh = new RadioStore.Station();
        checkTrue("new stations: titles only, presenter Alma, station name every 4 songs, no stories",
                !fresh.sayArtist && "Alma".equals(fresh.djName) && fresh.idEvery == 4 && fresh.storyEvery == 0);

        System.out.println("-- A book in parts --");
        StringBuilder book = new StringBuilder();
        for (int p = 1; p <= 6; p++) {
            for (int s = 0; s < 12; s++) {
                book.append("Paragraph ").append(p).append(" sentence ").append(s).append(" tells of the village by the lake. ");
            }
            book.append("\n\n");
        }
        book.append("A short line.\n\n");
        StringBuilder huge = new StringBuilder();
        for (int s = 0; s < 80; s++) {
            huge.append("This very long paragraph goes on and on, sentence ").append(s).append(". ");
        }
        book.append(huge).append("\n\nThe end of the story.");
        List<String> parts = RadioStories.split(book.toString());
        boolean sizes = true;
        for (String p : parts) {
            int n = RadioStories.words(p);
            sizes &= n >= 5 && n <= RadioStories.MAX_WORDS + 40;
        }
        checkTrue("parts of about a paragraph (" + parts.size() + " parts)", parts.size() >= 8 && sizes);
        checkTrue("paragraphs are kept whole and in order", parts.get(0).startsWith("Paragraph 1 sentence 0") && parts.get(0).endsWith("lake.")
                && parts.get(1).startsWith("Paragraph 2"));
        checkTrue("a very long paragraph is split at sentence ends", parts.stream().anyMatch(p -> p.startsWith("This very long") && p.endsWith(".")));
        checkTrue("a tiny last paragraph joins the one before", parts.get(parts.size() - 1).endsWith("The end of the story."));

        Path dir = Files.createTempDirectory("memoryq-stories");
        RadioStories st = new RadioStories(dir);
        st.init();
        checkTrue("a book needs a title", refused(() -> st.add(1, " ", "", "text", 0)));
        checkTrue("and some text", refused(() -> st.add(1, "Empty", "", "  ", 0)));
        RadioStories.Book b1 = st.add(1, "The Village by the Lake", "Wanjiru", book.toString(), 1);
        RadioStories.Book b2 = st.add(1, "Second Book", "", "One small story. It ends quickly.", 2);
        st.add(2, "Other station's book", "", "Not for station one.", 3);
        RadioStories.Part p0 = st.next(1);
        checkTrue("the first book is read first, from the start", p0 != null && p0.book == b1.id && p0.first() && p0.text.equals(parts.get(0)));
        checkTrue("nothing moves until a part was read on air", st.next(1).index == 0);
        st.aired(p0);
        checkTrue("then the next part", st.next(1).index == 1 && !st.next(1).first());
        st.update(b1.id, null, null, null, b1.parts - 1, -1);
        RadioStories.Part last = st.next(1);
        checkTrue("the last part is known", last.last());
        st.aired(last);
        checkTrue("a finished book: the next book follows", st.next(1).book == b2.id && st.next(1).first() && st.next(1).last());
        st.update(b2.id, null, null, false, -1, -1);
        checkTrue("switched-off books are not read", st.next(1) == null);
        RadioStories again = new RadioStories(dir);
        again.init();
        checkTrue("the place in each book is kept", again.book(b1.id).finished() && !again.book(b2.id).enabled
                && again.books(1).size() == 2 && again.books(2).size() == 1);
        again.update(b1.id, null, null, null, 0, -1);
        checkTrue("start again", again.next(1).book == b1.id && again.next(1).index == 0);

        System.out.println("-- What Alma says --");
        String first = w.story(p0, "Alma", "Hits FM");
        checkTrue("a new book: its title, author, then the text as written", first.contains("The Village by the Lake") && first.contains("Wanjiru")
                && first.contains("Paragraph 1 sentence 0 tells of the village"));
        RadioStories.Part mid = again.next(1);
        mid.index = 2;
        String more = w.story(mid, "Alma", "Hits FM");
        checkTrue("later parts: back to the story", more.contains("The Village by the Lake") && !more.contains("begin"));
        String bridge = w.storyBridge(next, mid, false);
        checkTrue("then: let's listen to the song before we continue", bridge.contains("Malaika") && bridge.contains("The Village by the Lake")
                && !bridge.contains("Fadhili"));
        checkTrue("at the end of a book she says it ended", w.story(last, "Alma", "Hits FM").contains("end"));
        String id = w.stationId("Alma", "Hits FM", next, false);
        checkTrue("over a song: the station's name", id.contains("Hits FM") && !id.contains("Fadhili"));

        System.out.println("-- On air: story breaks and the station name over songs --");
        engine(again);

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL CHECKS PASSED");
        } else {
            System.out.println(failures + " CHECK(S) FAILED");
            System.exit(1);
        }
        System.exit(0);
    }

    private static void engine(final RadioStories stories) throws Exception {
        final RadioStore.Station st = new RadioStore.Station();
        st.id = 1;
        st.name = "Hits FM";
        st.talkEvery = 0;
        st.introsOn = false;
        st.storyEvery = 2;
        st.idEvery = 1;
        final List<RadioStore.Track> tracks = new ArrayList<RadioStore.Track>();
        for (int i = 1; i <= 4; i++) {
            RadioStore.Track tr = new RadioStore.Track();
            tr.id = i;
            tr.station = 1;
            tr.title = "Song " + i;
            tr.artist = "Singer Number" + i;
            tr.durationMs = 120000;
            tracks.add(tr);
        }
        final List<String> spoken = Collections.synchronizedList(new ArrayList<String>());
        final int before = stories.next(1).index;
        RadioEngine.Content c = new RadioEngine.Content() {
            public RadioStore.Station station() {
                return st;
            }

            public List<RadioStore.Track> tracks() {
                return tracks;
            }

            public RadioEngine.Source open(RadioStore.Track tr) {
                final long frames = tr.durationMs * R / 1000;
                return new RadioEngine.Source() {
                    long left = frames;

                    public int read(short[] buf, int n) {
                        if (left <= 0) {
                            return -1;
                        }
                        int k = (int) Math.min(n, left);
                        java.util.Arrays.fill(buf, 0, k * 2, (short) 10000);
                        left -= k;
                        return k;
                    }

                    public void close() {
                    }
                };
            }

            public short[] speak(String text, String voice) {
                spoken.add(text);
                return new short[R * 4 * 2];
            }

            public List<NewsService.Item> news() {
                return new ArrayList<NewsService.Item>();
            }

            public RadioWriter.Shout bestShout(Set<String> used) {
                return null;
            }

            public int listeners() {
                return 0;
            }

            public void played(RadioStore.Track t) {
            }

            public RadioStories.Part nextStory() {
                return stories.next(1);
            }

            public void storyAired(RadioStories.Part p) {
                stories.aired(p);
            }
        };
        final RadioEngine[] holder = new RadioEngine[1];
        final List<Short> levels = Collections.synchronizedList(new ArrayList<Short>());
        RadioEngine.Sink sink = new RadioEngine.Sink() {
            long frames;

            public void write(short[] buf, int n) {
                frames += n;
                levels.add(buf[0]);
                if (frames >= (long) R * 600) {
                    holder[0].stop();
                }
            }
        };
        RadioEngine e = new RadioEngine(1, c, sink, false, new Random(5));
        e.setQuietNotes(true);
        holder[0] = e;
        Thread th = new Thread(e);
        th.start();
        th.join(120000);
        List<RadioEngine.Event> ev = e.recent(40);
        int storyBreaks = 0, ids = 0;
        boolean bridged = false;
        for (RadioEngine.Event x : ev) {
            storyBreaks += "story".equals(x.kind) ? 1 : 0;
            ids += "id".equals(x.kind) ? 1 : 0;
            bridged |= "intro".equals(x.kind) && x.text.contains("continue with our story") || "intro".equals(x.kind) && x.text.contains("more of");
        }
        checkTrue("10 minutes on air: story breaks every 2 songs (" + storyBreaks + ")", storyBreaks >= 2);
        checkTrue("after the story part: the song, then more of the story", bridged);
        checkTrue("the book moved on as parts were read", stories.next(1) != null && stories.next(1).index >= before + 2);
        checkTrue("Alma said the station's name over songs (" + ids + " times)", ids >= 2);
        boolean dipped = false;
        synchronized (levels) {
            for (short v : levels) {
                dipped |= v > 1000 && v < 6000; // the song (10000) dipped under her voice
            }
        }
        checkTrue("the song dips while she talks over it, then comes back", dipped && levels.get(levels.size() - 1) >= 9000);
        boolean artistSaid = false;
        synchronized (spoken) {
            for (String s : spoken) {
                artistSaid |= s.contains("Singer Number");
            }
        }
        checkTrue("no artists' names were said", !artistSaid);
    }

    private interface Action {
        void run() throws Exception;
    }

    private static boolean refused(Action a) {
        try {
            a.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void checkTrue(String name, boolean ok) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + name);
        if (!ok) {
            failures++;
        }
    }
}
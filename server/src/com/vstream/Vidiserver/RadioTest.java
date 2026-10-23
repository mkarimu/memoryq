package com.vstream.Vidiserver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Tests the radio: stations and songs, what the DJ writes, the mixing (crossfades, talk, music bed) and the stream. */
public class RadioTest {

    private static int failures = 0;
    private static final AtomicLong t = new AtomicLong(1760000000000L);

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("vstream-radio");
        storeTests(dir);
        writerTests();
        voiceShapeTests();
        shoutTests(dir);
        engineTests();
        ffmpegTests(dir);
        System.out.println();
        if (failures == 0) {
            System.out.println("All radio tests passed.");
        } else {
            System.out.println(failures + " radio test(s) FAILED.");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) {
            failures++;
        }
    }

    // ---- Stations and songs ----

    private static void storeTests(Path dir) throws Exception {
        System.out.println("-- Stations and songs --");
        RadioStore st = new RadioStore(dir.resolve("radio.txt"), dir.resolve("music"));
        st.init();
        RadioStore.Station pop = st.createStation("Pop Hits", "Today's pop", t.get());
        check("a station is created", pop.id == 1 && pop.enabled && pop.talkEvery == 3);
        boolean dup = false;
        try {
            st.createStation("pop hits", "", t.get());
        } catch (IllegalArgumentException e) {
            dup = true;
        }
        check("two stations cannot share a name", dup);
        check("sound types are recognised", "mp3".equals(RadioStore.audioType("Song.MP3")) && RadioStore.audioType("x.exe") == null);
        RadioStore.Track a = st.addTrack(pop.id, "Backstreet Boys - I Want It That Way.mp3", "mp3", new byte[2000],
                "", "", "", 213000, t.get());
        check("artist and title come from 'Artist - Title' file names",
                "Backstreet Boys".equals(a.artist) && "I Want It That Way".equals(a.title));
        RadioStore.Track b = st.addTrack(pop.id, "track01.mp3", "mp3", new byte[2000], "Real Title", "Real Artist",
                "Album", 180000, t.get());
        check("tags win over the file name", "Real Title".equals(b.title) && "Real Artist".equals(b.artist));
        check("the song file is saved", Files.size(st.pathOf(b)) == 2000);
        st.editTrack(b.id, "New\tTitle", "New Artist");
        check("titles are cleaned when edited", "New Title".equals(st.track(b.id).title));
        RadioStore.Station ch = st.station(pop.id);
        ch.djName = "DJ Nova";
        ch.talkEvery = 99;
        st.updateStation(ch);
        check("talk every is limited to 20", st.station(pop.id).talkEvery == 20);
        st.countPlay(a.id, t.get());
        st.flushIfDirty();
        RadioStore again = new RadioStore(dir.resolve("radio.txt"), dir.resolve("music"));
        again.init();
        check("stations and songs are saved and loaded", again.stations().size() == 1
                && again.tracks(pop.id).size() == 2 && again.track(a.id).plays == 1
                && "DJ Nova".equals(again.station(pop.id).djName));
        Path f = st.pathOf(b);
        st.deleteTrack(b.id);
        check("deleting a song removes its file", !Files.exists(f) && st.trackCount(pop.id) == 1);
        // promotion: links, likes, taps, requests
        boolean badLink = false;
        try {
            st.editTrack(a.id, null, null, "javascript:alert(1)");
        } catch (IllegalArgumentException e) {
            badLink = true;
        }
        check("song links must be web links", badLink);
        st.editTrack(a.id, null, null, "https://open.spotify.com/track/abc");
        check("a song link is saved", "https://open.spotify.com/track/abc".equals(st.track(a.id).link));
        check("likes are counted once per person", st.like(a.id, "bob", true) == 1 && st.like(a.id, "BOB", true) == 1
                && st.like(a.id, "carol", true) == 2);
        check("a person can unlike", st.like(a.id, "carol", false) == 1 && !st.likes(a.id, "carol") && st.likes(a.id, "bob"));
        check("a tap on 'Get this song' returns the link and is counted",
                st.tap(a.id).startsWith("https://open.spotify") && st.track(a.id).taps == 1);
        RadioStore.Station promo = st.station(pop.id);
        promo.promo = "Get all my songs on Spotify, just search Nelson!";
        st.updateStation(promo);
        st.flushIfDirty();
        RadioStore again2 = new RadioStore(dir.resolve("radio.txt"), dir.resolve("music"));
        again2.init();
        check("links, likes, taps and the promo line are saved", again2.track(a.id).likes == 1 && again2.likes(a.id, "bob")
                && again2.track(a.id).taps == 1 && again2.track(a.id).link.contains("spotify")
                && again2.station(pop.id).promo.contains("Spotify"));
        RadioService rs = new RadioService(st, null, null, null, null, null, t::get, new Random(1));
        RadioStore.Track c = st.addTrack(pop.id, "c.mp3", "mp3", new byte[2000], "Song C", "Me", "", 100000, t.get());
        check("a listener can request a song", rs.request(pop.id, a.id, "bob", "Bob") == 1);
        boolean twice = false;
        try {
            rs.request(pop.id, c.id, "bob", "Bob");
        } catch (IllegalArgumentException e) {
            twice = true;
        }
        check("one waiting request per person", twice);
        boolean same = false;
        try {
            rs.request(pop.id, a.id, "carol", "Carol");
        } catch (IllegalArgumentException e) {
            same = true;
        }
        check("a song can only be waiting once", same);
        check("requests are counted for the stats", st.track(a.id).requests == 1);
        RadioWriter.Request first = rs.takeRequest(pop.id);
        check("requests come out in order with the listener's name", first != null && first.trackId == a.id
                && "Bob".equals(first.name) && rs.takeRequest(pop.id) == null);
        st.deleteStation(pop.id);
        check("deleting a station removes its songs", st.stations().isEmpty() && st.track(a.id) == null);
    }

    // ---- What the DJ says ----

    private static NewsService.Item item(long id, String source, String headline, String summary) {
        return new NewsService.Item(id, 1, source, "https://example.com/" + id, headline, summary, "world",
                t.get(), t.get(), false);
    }

    private static void writerTests() throws Exception {
        System.out.println("-- The DJ's words --");
        List<NewsService.Item> latest = new ArrayList<NewsService.Item>();
        latest.add(item(1, "CNN", "Storm hits the coast", "A strong storm reached the coast on Monday. More follows."));
        latest.add(item(2, "CNN", "Markets rise", "Stocks went up."));
        latest.add(item(3, "BBC", "New space telescope images", "Scientists shared new pictures of distant galaxies."));
        latest.add(item(4, "Reuters", "Elections next month", "Voters prepare for the vote."));
        Set<Long> used = new HashSet<Long>();
        List<NewsService.Item> three = RadioWriter.chooseNews(latest, used, 3);
        check("three stories are chosen", three.size() == 3);
        check("they come from different sources first", three.get(0).id == 1 && three.get(1).id == 3 && three.get(2).id == 4);
        used.add(1L);
        used.add(3L);
        check("stories already read are skipped", RadioWriter.chooseNews(latest, used, 3).get(0).id == 2);

        RadioWriter w = new RadioWriter(null, new Random(1));
        String news = w.news(three, "DJ Nova", "Pop Hits", true);
        check("the built-in news names the sources", news.contains("CNN") && news.contains("BBC") && news.contains("Reuters"));
        check("the built-in news tells listeners where to read more", news.contains("visit"));
        check("the news has no links", !news.contains("http") && !news.contains("example.com"));

        RadioWriter.Shout s = new RadioWriter.Shout();
        s.user = "zainab_n60";
        s.name = "DJ Zainab";
        s.room = "General";
        s.text = "Who else is dancing in the kitchen right now? \uD83D\uDE02 https://x.y/z";
        s.replies = 3;
        s.people = 4;
        String shout = w.shout(s, "DJ Nova", false);
        check("the shout-out names the person and the room", shout.contains("DJ Zainab") && shout.contains("General"));
        check("the shout-out quotes the message without links or emojis",
                shout.contains("dancing in the kitchen") && !shout.contains("http") && !shout.contains("\uD83D\uDE02"));

        RadioStore.Track next = new RadioStore.Track();
        next.title = "I Want It That Way";
        next.artist = "Backstreet Boys";
        String intro = w.intro(next, null, "Pop Hits", true);
        check("the intro names the song and artist", intro.contains("I Want It That Way") && intro.contains("Backstreet Boys"));
        check("after the news the DJ says it is back to the music", intro.toLowerCase().contains("music"));
        String req = w.intro(next, null, "Pop Hits", false, "Velvet Rhythm", "");
        check("a request names the listener who asked", req.contains("Velvet Rhythm") && req.contains("I Want It That Way"));
        boolean promoSaid = false;
        for (int i = 0; i < 12; i++) {
            if (w.intro(next, null, "Pop Hits", false, "", "Get my songs on Spotify!").contains("Spotify")) {
                promoSaid = true;
            }
        }
        check("the station's promo line is said now and then", promoSaid);

        final List<String> asked = new ArrayList<String>();
        RadioWriter ai = new RadioWriter(new RadioWriter.Ai() {
            @Override
            public boolean ready() {
                return true;
            }

            @Override
            public String write(String instructions, String input) {
                asked.add(instructions + "\n" + input);
                return "**CNN** reports a storm. Why it matters: be safe! Visit https://cnn.com for more.";
            }
        }, new Random(2));
        String aiNews = ai.news(three, "DJ Nova", "Pop Hits", true);
        check("with AI, the AI text is used (cleaned for the voice)", aiNews.startsWith("CNN reports a storm")
                && !aiNews.contains("*") && !aiNews.contains("https"));
        check("the AI is given the stories and asked for analysis", asked.size() == 1
                && asked.get(0).contains("Storm hits the coast") && asked.get(0).contains("analysis"));
        String noListeners = ai.news(three, "DJ Nova", "Pop Hits", false);
        check("without listeners the AI is not used (no cost)", asked.size() == 1 && noListeners.contains("visit"));
        RadioWriter broken = new RadioWriter(new RadioWriter.Ai() {
            @Override
            public boolean ready() {
                return true;
            }

            @Override
            public String write(String instructions, String input) throws IOException {
                throw new IOException("offline");
            }
        }, new Random(3));
        check("if the AI fails, the built-in news is used", broken.news(three, "DJ", "S", true).contains("visit"));
        String json = "{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hello \\\"there\\\"\\nfriends \\u00e9\"}]}]}";
        check("OpenAI answers are read", "Hello \"there\"\nfriends \u00e9".equals(RadioWriter.OpenAi.outputText(json)));
    }

    // ---- The voice's rise and fall ----

    private static void voiceShapeTests() {
        System.out.println("-- The DJ's rise and fall --");
        List<String> parts = AudioTools.sentences("Hello everybody! This is DJ Nova. Ok. How are you today? Back to the music.");
        check("text is split into sentences (short bits joined to the next)", parts.size() == 4
                && parts.get(2).equals("Ok. How are you today?"));
        Random r = new Random(1);
        AudioTools.Prosody first = AudioTools.prosody("Hello everybody!", 0, 4, r);
        AudioTools.Prosody ask = AudioTools.prosody("How are you today?", 2, 4, r);
        AudioTools.Prosody last = AudioTools.prosody("Back to the music.", 3, 4, r);
        check("an exclamation is brighter and quicker", first.pitch > 1.03 && first.tempo > 1.04);
        check("a question lifts", ask.pitch > 1.02);
        check("the last line settles and has no pause after it", last.pitch < 1.0 && last.pauseMs == 0);
        Set<Integer> pauses = new HashSet<Integer>();
        for (int i = 0; i < 6; i++) {
            pauses.add(AudioTools.prosody("A plain sentence here.", 1, 4, r).pauseMs);
        }
        check("pauses between sentences vary", pauses.size() >= 3);
        check("the voice filter keeps the speed while changing pitch",
                AudioTools.prosodyFilter(new AudioTools.Prosody(1.04, 1.04, 0)).contains("atempo=1.0000"));
    }

    // ---- Shout-outs from chats ----

    private static void shoutTests(Path dir) throws Exception {
        System.out.println("-- Shout-outs --");
        ChatStore cs = new ChatStore(dir.resolve("chat.txt"), t::get);
        cs.init();
        ChatStore.RoomInfo g = cs.createGroup("Music Lovers", "alice", true);
        ChatStore.RoomInfo secret = cs.createGroup("Secret Club", "alice", true, true);
        cs.post(g.id, "alice", "Good morning everyone", 0);
        ChatStore.Message best = cs.post(g.id, "bob", "This song takes me back to summer 2005, what a vibe!", 0);
        cs.post(g.id, "carol", "Same here, that was the best summer", best.id);
        cs.post(g.id, "dave", "Totally agree with you", best.id);
        cs.react(g.id, best.id, "alice", ChatStore.REACTIONS.get(0));
        cs.post(g.id, "eve", "Check this out https://spam.example/x amazing", 0);
        ChatStore.Message priv = cs.post(secret.id, "alice", "A private message that is very popular indeed", 0);
        for (String u : new String[]{"bob", "carol", "dave"}) {
            cs.react(secret.id, priv.id, u, ChatStore.REACTIONS.get(0));
        }
        ProfileStore profiles = new ProfileStore(dir.resolve("profiles.txt"), dir.resolve("photos"));
        profiles.init();
        RadioService rs = new RadioService(new RadioStore(dir.resolve("r2.txt"), dir.resolve("m2")), null, null, cs,
                profiles, null, t::get, new Random(4));
        RadioWriter.Shout s = rs.bestShout(new HashSet<String>());
        check("the most talked-about public post is chosen", s != null && "bob".equals(s.user));
        check("its replies, people and reactions are counted", s != null && s.replies == 2 && s.people == 4 && s.reactions == 1);
        check("the room name is given", s != null && "Music Lovers".equals(s.room));
        Set<String> used = new HashSet<String>();
        used.add(s.user + "|" + s.text);
        RadioWriter.Shout s2 = rs.bestShout(used);
        check("a post is not shouted out twice", s2 != null && !"bob".equals(s2.user));
        check("private groups and posts with links are never used", s2 != null && !s2.text.contains("private")
                && !s2.text.contains("http"));
        t.addAndGet(13L * 3600 * 1000);
        check("only the last 12 hours count", rs.bestShout(new HashSet<String>()) == null);
    }

    // ---- The mixing ----

    private static final int R = RadioEngine.RATE;

    /** A test song: a steady value (so the mix can be measured) for its length. */
    private static RadioEngine.Source steady(final int value, final long frames) {
        return new RadioEngine.Source() {
            long left = frames;

            @Override
            public int read(short[] buf, int n) {
                if (left <= 0) {
                    return -1;
                }
                int k = (int) Math.min(n, left);
                for (int i = 0; i < k * 2; i++) {
                    buf[i] = (short) value;
                }
                left -= k;
                return k;
            }

            @Override
            public void close() {
            }
        };
    }

    private static void engineTests() throws Exception {
        System.out.println("-- Mixing and DJ breaks --");
        final RadioStore.Station st = new RadioStore.Station();
        st.id = 1;
        st.name = "Test FM";
        st.djName = "DJ Test";
        st.talkEvery = 2;
        final List<RadioStore.Track> tracks = new ArrayList<RadioStore.Track>();
        for (int i = 1; i <= 5; i++) {
            RadioStore.Track tr = new RadioStore.Track();
            tr.id = i;
            tr.station = 1;
            tr.title = "Song " + i;
            tr.artist = "Artist " + i;
            tr.durationMs = 20000;
            tracks.add(tr);
        }
        final List<NewsService.Item> news = new ArrayList<NewsService.Item>();
        for (int i = 1; i <= 9; i++) {
            news.add(item(i, "Source" + (i % 3), "Headline number " + i, "Summary " + i + " is here."));
        }
        final List<String> spoken = new ArrayList<String>();
        final int speechValue = 5000;
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
            public RadioEngine.Source open(RadioStore.Track tr) {
                return steady((int) tr.id * 1000, tr.durationMs * R / 1000);
            }

            @Override
            public short[] speak(String text, String voice) {
                synchronized (spoken) {
                    spoken.add(text);
                }
                short[] s = new short[R * 3 * 2];
                java.util.Arrays.fill(s, (short) speechValue);
                return s;
            }

            @Override
            public List<NewsService.Item> news() {
                return news;
            }

            @Override
            public RadioWriter.Shout bestShout(Set<String> alreadyUsed) {
                RadioWriter.Shout s = new RadioWriter.Shout();
                s.user = "bob";
                s.name = "Bob";
                s.room = "General";
                s.text = "Loving this station so much " + alreadyUsed.size();
                return alreadyUsed.contains(s.user + "|" + s.text) ? null : s;
            }

            @Override
            public int listeners() {
                return 0;
            }

            @Override
            public void played(RadioStore.Track tr) {
            }
        };
        final List<Integer> firstSample = new ArrayList<Integer>();
        final RadioEngine[] holder = new RadioEngine[1];
        final long limit = (long) R * 260;
        RadioEngine.Sink sink = new RadioEngine.Sink() {
            long frames;

            @Override
            public void write(short[] buf, int n) {
                firstSample.add((int) buf[0]);
                frames += n;
                if (frames % (R * 2) < n) {
                    try {
                        Thread.sleep(2);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (frames >= limit) {
                    holder[0].stop();
                }
            }
        };
        RadioEngine e = new RadioEngine(1, c, sink, false, new Random(7));
        holder[0] = e;
        Thread th = new Thread(e);
        th.start();
        th.join(60000);
        check("the engine ran through about 4 minutes of radio", e.seconds() >= 259);
        List<RadioEngine.Event> ev = e.recent(40);
        java.util.Collections.reverse(ev);
        List<String> songs = new ArrayList<String>();
        int news2 = 0;
        int shouts = 0;
        int intros = 0;
        for (RadioEngine.Event x : ev) {
            if ("song".equals(x.kind)) {
                songs.add(x.text);
            } else if ("news".equals(x.kind)) {
                news2++;
            } else if ("shout".equals(x.kind)) {
                shouts++;
            } else if ("intro".equals(x.kind)) {
                intros++;
            }
        }
        System.out.println("      (" + songs.size() + " songs, " + news2 + " news, " + shouts + " shout-outs, " + intros + " intros)");
        check("several songs played", songs.size() >= 8);
        check("every song plays once before any repeats", new HashSet<String>(songs.subList(0, 5)).size() == 5);
        check("the DJ read the news", news2 >= 1);
        check("the DJ gave a shout-out", shouts >= 1);
        check("news and shout-outs take turns", Math.abs(news2 - shouts) <= 1);
        check("the DJ introduced songs", intros >= 2);
        boolean introAfterTalk = false;
        for (int i = 0; i + 2 < ev.size(); i++) {
            if (("news".equals(ev.get(i).kind) || "shout".equals(ev.get(i).kind)) && "song".equals(ev.get(i + 1).kind)
                    && "intro".equals(ev.get(i + 2).kind)) {
                introAfterTalk = true;
            }
        }
        check("after the talk the next song starts under the DJ's intro", introAfterTalk);
        boolean pureSpeech = false;
        boolean bed = false;
        boolean pureSong = false;
        for (int v : firstSample) {
            if (v == speechValue) {
                pureSpeech = true;
            }
            for (int id = 1; id <= 5; id++) {
                int expectBed = speechValue + (int) (0.18 * id * 1000);
                if (Math.abs(v - expectBed) <= 2) {
                    bed = true;
                }
                if (v == id * 1000) {
                    pureSong = true;
                }
            }
        }
        check("songs play at full volume", pureSong);
        check("the news is read with the music faded out", pureSpeech);
        check("during the intro the next song plays softly underneath", bed);
        boolean crossfade = false;
        for (int v : firstSample) {
            boolean between = v > 1000 && v < 5000 && v % 1000 != 0 && !bed(v, speechValue);
            if (between) {
                crossfade = true;
            }
        }
        check("songs without talk between them crossfade", crossfade);
        check("the DJ's words were spoken", spoken.size() >= 3);

        // When the voice program fails, the station keeps playing music and the log says why.
        RadioEngine.Content mute = new RadioEngine.Content() {
            @Override
            public RadioStore.Station station() {
                return st;
            }

            @Override
            public List<RadioStore.Track> tracks() {
                return tracks;
            }

            @Override
            public RadioEngine.Source open(RadioStore.Track tr) {
                return steady((int) tr.id * 1000, tr.durationMs * R / 1000);
            }

            @Override
            public short[] speak(String text, String voice) {
                return null;
            }

            @Override
            public List<NewsService.Item> news() {
                return news;
            }

            @Override
            public RadioWriter.Shout bestShout(Set<String> alreadyUsed) {
                return null;
            }

            @Override
            public int listeners() {
                return 0;
            }

            @Override
            public void played(RadioStore.Track tr) {
            }

            @Override
            public String voiceProblem() {
                return "Piper failed (exit 1): missing file";
            }
        };
        final long limit2 = (long) R * 120;
        final RadioEngine[] h2 = new RadioEngine[1];
        RadioEngine e2 = new RadioEngine(2, mute, new RadioEngine.Sink() {
            long frames;

            @Override
            public void write(short[] buf, int n) {
                frames += n;
                if (frames % (R * 2) < n) {
                    try {
                        Thread.sleep(2);
                    } catch (InterruptedException x) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (frames >= limit2) {
                    h2[0].stop();
                }
            }
        }, false, new Random(8));
        h2[0] = e2;
        e2.setQuietNotes(true);
        Thread t2 = new Thread(e2);
        t2.start();
        t2.join(60000);
        int songs2 = 0;
        boolean why = false;
        for (RadioEngine.Event x : e2.recent(40)) {
            if ("song".equals(x.kind)) {
                songs2++;
            }
            if ("note".equals(x.kind) && x.text.contains("could not speak") && x.text.contains("Piper failed")) {
                why = true;
            }
        }
        check("without a working voice the music keeps playing", songs2 >= 5);
        check("the log says why the DJ could not speak", why);
    }

    private static boolean bed(int v, int speech) {
        for (int id = 1; id <= 5; id++) {
            if (Math.abs(v - (speech + (int) (0.18 * id * 1000))) <= 2) {
                return true;
            }
        }
        return false;
    }

    // ---- With FFmpeg (skipped when it is not installed) ----

    private static void ffmpegTests(Path dir) throws Exception {
        System.out.println("-- Real sound files (FFmpeg) --");
        AudioTools tools = new AudioTools(java.nio.file.Paths.get(System.getProperty("vstream.toolsDir", "tools")),
                dir.resolve("tmp"));
        tools.detect();
        if (!tools.ready()) {
            System.out.println("SKIP  FFmpeg is not installed here, so real sound files were not tested");
            return;
        }
        Path song = dir.resolve("tone.mp3");
        Process p = new ProcessBuilder(tools.ffmpegPath(), "-v", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=6",
                "-metadata", "title=Test Tone", "-metadata", "artist=The Sines", "-c:a", "libmp3lame", song.toString())
                .redirectErrorStream(true).start();
        p.waitFor();
        if (!Files.isRegularFile(song)) {
            System.out.println("SKIP  could not make a test sound file");
            return;
        }
        AudioTools.Tags tags = tools.probe(song);
        check("song tags are read (title, artist, length)", "Test Tone".equals(tags.title)
                && "The Sines".equals(tags.artist) && tags.durationMs > 5500 && tags.durationMs < 6600);
        boolean refused = false;
        Path junk = dir.resolve("junk.mp3");
        Files.write(junk, "not music".getBytes(StandardCharsets.UTF_8));
        try {
            tools.probe(junk);
        } catch (IOException e) {
            refused = true;
        }
        check("files that are not sound are refused", refused);
        RadioEngine.ProcessSource src = new RadioEngine.ProcessSource(tools.decode(song));
        short[] buf = new short[4096 * 2];
        long frames = 0;
        int n;
        while ((n = src.read(buf, 4096)) > 0) {
            frames += n;
        }
        src.close();
        check("songs are decoded to sound samples", Math.abs(frames - 6L * R) < R / 10);
        short[] voice = tools.speak("Hello, this is your DJ.", "");
        if ("none".equals(tools.voiceEngine())) {
            System.out.println("SKIP  no voice program here");
        } else {
            if (voice != null && voice.length > R) {
                check("the DJ voice speaks (" + tools.voiceEngine() + ")", true);
            } else {
                System.out.println("WARN  the DJ voice (" + tools.voiceEngine() + ") did not speak - the radio plays music without talk");
            }
        }
        RadioEngine.Broadcast cast = new RadioEngine.Broadcast(tools, 96);
        short[] tone = tools.decodeAll(song);
        for (int i = 0; i + 4096 * 2 <= tone.length; i += 4096 * 2) {
            cast.write(java.util.Arrays.copyOfRange(tone, i, i + 4096 * 2), 4096);
        }
        Thread.sleep(800);
        final ByteArrayOutputStream got = new ByteArrayOutputStream();
        Thread listener = new Thread(() -> {
            try {
                cast.serve(new OutputStream() {
                    @Override
                    public void write(int b) {
                        got.write(b);
                    }

                    @Override
                    public void write(byte[] b, int off, int len) {
                        got.write(b, off, len);
                    }
                });
            } catch (IOException e) {
                // done
            }
        });
        listener.start();
        Thread.sleep(300);
        check("a listener is counted", cast.listenerCount() == 1);
        for (int i = 0; i + 4096 * 2 <= tone.length; i += 4096 * 2) {
            cast.write(java.util.Arrays.copyOfRange(tone, i, i + 4096 * 2), 4096);
        }
        Thread.sleep(1500);
        cast.close();
        listener.join(3000);
        byte[] mp3 = got.toByteArray();
        boolean frameSync = false;
        for (int i = 0; i + 1 < mp3.length; i++) {
            if ((mp3[i] & 0xff) == 0xff && (mp3[i + 1] & 0xe0) == 0xe0) {
                frameSync = true;
                break;
            }
        }
        check("listeners receive an MP3 stream (" + mp3.length + " bytes)", mp3.length > 20000 && frameSync);
    }
}
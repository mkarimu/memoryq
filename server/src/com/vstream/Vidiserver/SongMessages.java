package com.vstream.Vidiserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * What a song is about. Alma listens to a song once, ahead of time (while the song before it plays): its words are
 * written down by OpenAI's speech-to-text, and the AI finds the song's message - its heart - in one or two sentences.
 * Only that message is kept (never the words of the song). Songs shorter than 2 minutes, ads and station IDs are left
 * out. File (radio-messages.txt): trackId, status (ok / none / fail), when, tries, message.
 */
public final class SongMessages {

    static final long MIN_SONG_MS = 120000L;
    static final int LISTEN_SECONDS = 420;
    static final long RETRY_MS = 24L * 3600000L;

    static final class Entry {
        String status = "";
        long at;
        int tries;
        String message = "";
    }

    private final Path file;
    private final RadioStore store;
    private final AudioTools tools;
    private final RadioWriter.Ai ai;
    private final RadioWriter writer;
    private final LongSupplier clock;
    private final int perDay;
    private final Map<Long, Entry> entries = new HashMap<Long, Entry>();
    private final Set<Long> busy = new HashSet<Long>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "song-messages");
        t.setDaemon(true);
        return t;
    });
    private long dayStart;
    private int today;

    public SongMessages(Path file, RadioStore store, AudioTools tools, RadioWriter.Ai ai, LongSupplier clock, int perDay) {
        this.file = file;
        this.store = store;
        this.tools = tools;
        this.ai = ai;
        this.writer = new RadioWriter(ai, new Random());
        this.clock = clock;
        this.perDay = perDay;
    }

    public synchronized void init() {
        entries.clear();
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t", 5);
                if (f.length < 5 || !f[0].matches("\\d+")) {
                    continue;
                }
                Entry e = new Entry();
                e.status = f[1];
                e.at = Long.parseLong(f[2]);
                e.tries = Integer.parseInt(f[3]);
                e.message = f[4];
                entries.put(Long.parseLong(f[0]), e);
            }
        } catch (IOException | RuntimeException e) {
            System.out.println("[radio] could not read the song messages: " + e.getMessage());
        }
    }

    /** OpenAI is set up on this server (speech-to-text and writing). */
    public boolean ready() {
        return ai != null && ai.ready();
    }

    public static boolean worthy(RadioStore.Track t) {
        return t != null && t.durationMs >= MIN_SONG_MS;
    }

    /** The message if it is known already ("" = this song has none), or null when it is not known yet. */
    public synchronized String known(long trackId) {
        Entry e = entries.get(trackId);
        if (e == null) {
            return null;
        }
        return "ok".equals(e.status) ? e.message : "";
    }

    public synchronized int count(String status) {
        int n = 0;
        for (Entry e : entries.values()) {
            if (e.status.equals(status)) {
                n++;
            }
        }
        return n;
    }

    /** Starts listening to the song in the background (once per song). */
    public void request(RadioStore.Track t) {
        if (!ready() || !worthy(t)) {
            return;
        }
        synchronized (this) {
            Entry e = entries.get(t.id);
            long now = clock.getAsLong();
            if (e != null && (!"fail".equals(e.status) || e.tries >= 2 || now - e.at < RETRY_MS)) {
                return;
            }
            if (busy.contains(t.id)) {
                return;
            }
            if (now - dayStart > 86400000L) {
                dayStart = now;
                today = 0;
            }
            if (today >= perDay) {
                return;
            }
            today++;
            busy.add(t.id);
        }
        final RadioStore.Track track = t;
        worker.execute(() -> analyse(track));
    }

    /** The song's message, waiting up to waitMs while it is being worked out; null when there is none. */
    public String await(RadioStore.Track t, long waitMs) {
        if (t == null) {
            return null;
        }
        long until = System.currentTimeMillis() + Math.max(0, waitMs);
        while (true) {
            synchronized (this) {
                String k = known(t.id);
                if (k != null && !busy.contains(t.id)) {
                    return k.isEmpty() ? null : k;
                }
                if (!busy.contains(t.id)) {
                    return null;
                }
            }
            if (System.currentTimeMillis() >= until) {
                return null;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    void analyse(RadioStore.Track t) {
        Entry e = new Entry();
        Path tmp = null;
        try {
            Path src = store.pathOf(t);
            Path audio = src;
            String ff = tools == null ? null : tools.ffmpegPath();
            if (ff != null) {
                // a small mono copy of the first 7 minutes is plenty to hear the words
                tmp = Files.createTempFile("songmsg", ".mp3");
                Process p = new ProcessBuilder(ff, "-v", "error", "-y", "-i", src.toString(), "-t", String.valueOf(LISTEN_SECONDS),
                        "-vn", "-ac", "1", "-ar", "16000", "-b:a", "32k", tmp.toString()).redirectErrorStream(true).start();
                AudioTools.readAll(p.getInputStream());
                if (!p.waitFor(120, TimeUnit.SECONDS) || p.exitValue() != 0 || Files.size(tmp) < 1000) {
                    p.destroyForcibly();
                    throw new IOException("the song could not be read");
                }
                audio = tmp;
            }
            String words = ai.transcribe(audio);
            String msg = writer.songMessage(t.title, words);
            e.status = msg.isEmpty() ? "none" : "ok";
            e.message = msg.replace('\t', ' ').replace('\n', ' ');
        } catch (Exception x) {
            e.status = "fail";
            System.out.println("[radio] song message for " + t.label() + ": " + x.getMessage());
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // a temp file left behind
                }
            }
        }
        synchronized (this) {
            Entry old = entries.get(t.id);
            e.tries = (old == null ? 0 : old.tries) + 1;
            e.at = clock.getAsLong();
            entries.put(t.id, e);
            busy.remove(t.id);
            save();
        }
        if ("ok".equals(e.status)) {
            System.out.println("[radio] the message of " + t.label() + ": " + e.message);
        }
    }

    private void save() {
        if (file == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Long, Entry> x : entries.entrySet()) {
            Entry e = x.getValue();
            sb.append(x.getKey()).append('\t').append(e.status).append('\t').append(e.at).append('\t').append(e.tries).append('\t')
                    .append(e.message).append('\n');
        }
        try {
            Path part = file.resolveSibling(file.getFileName() + ".part");
            Files.write(part, sb.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            System.out.println("[radio] could not save the song messages: " + ex.getMessage());
        }
    }

    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
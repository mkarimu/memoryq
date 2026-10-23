package com.vstream.Vidiserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Videos: YouTube links and uploaded video files, with their comments. Saved to two text files (videos.txt,
 * video-comments.txt); uploaded files and their pictures live in a folder (videos/ID.mp4, videos/ID.jpg).
 */
public final class VideoStore {

    public static final int MAX_TITLE = 120;
    public static final int MAX_DESCRIPTION = 2000;
    public static final int MAX_COMMENT = 1000;

    public static final String YOUTUBE = "youtube";
    public static final String FILE = "file";

    public static final String READY = "ready";
    public static final String PROCESSING = "processing";
    public static final String FAILED = "failed";

    /** A copy of one video; changing it does not change the store. */
    public static final class Video {
        public long id;
        public String owner = "";
        public String kind = FILE;
        /** The YouTube id, or the file name in the videos folder. */
        public String ref = "";
        public String status = READY;
        /** "" = showing, "owner" = paused by its owner, "admin" = paused by the super-admin. */
        public String paused = "";
        /** Shown on Memoryq TV. */
        public boolean featured;
        public int pos;
        public long created;
        public long updated;
        public long durationMs;
        public long views;
        public long bytes;
        public String title = "";
        public String description = "";
        public String error = "";

        Video copy() {
            Video v = new Video();
            v.id = id;
            v.owner = owner;
            v.kind = kind;
            v.ref = ref;
            v.status = status;
            v.paused = paused;
            v.featured = featured;
            v.pos = pos;
            v.created = created;
            v.updated = updated;
            v.durationMs = durationMs;
            v.views = views;
            v.bytes = bytes;
            v.title = title;
            v.description = description;
            v.error = error;
            return v;
        }

        public boolean isPublic() {
            return READY.equals(status) && paused.isEmpty();
        }
    }

    public static final class Comment {
        public long id;
        public long video;
        public String author = "";
        public long created;
        public boolean edited;
        public String text = "";

        Comment copy() {
            Comment c = new Comment();
            c.id = id;
            c.video = video;
            c.author = author;
            c.created = created;
            c.edited = edited;
            c.text = text;
            return c;
        }
    }

    private final Path file;
    private final Path commentFile;
    private final Path dir;
    private final Map<Long, Video> videos = new LinkedHashMap<Long, Video>();
    private final Map<Long, Comment> comments = new LinkedHashMap<Long, Comment>();
    private long nextId = 1;
    private long nextComment = 1;

    public VideoStore(Path file, Path commentFile, Path dir) {
        this.file = file;
        this.commentFile = commentFile;
        this.dir = dir;
    }

    public Path dir() {
        return dir;
    }

    public synchronized void init() throws IOException {
        Files.createDirectories(dir);
        Files.createDirectories(dir.resolve("tmp"));
        if (Files.isRegularFile(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t", -1);
                if (f.length < 16) {
                    continue;
                }
                try {
                    Video v = new Video();
                    v.id = Long.parseLong(f[0]);
                    v.owner = f[1];
                    v.kind = f[2];
                    v.ref = f[3];
                    v.status = f[4];
                    v.paused = f[5];
                    v.featured = "1".equals(f[6]);
                    v.pos = Integer.parseInt(f[7]);
                    v.created = Long.parseLong(f[8]);
                    v.updated = Long.parseLong(f[9]);
                    v.durationMs = Long.parseLong(f[10]);
                    v.views = Long.parseLong(f[11]);
                    v.bytes = Long.parseLong(f[12]);
                    v.title = un(f[13]);
                    v.description = un(f[14]);
                    v.error = un(f[15]);
                    if (PROCESSING.equals(v.status)) {
                        v.status = FAILED; // the server restarted while it was being prepared
                        v.error = "the server restarted while preparing this video - please upload it again";
                    }
                    videos.put(v.id, v);
                    nextId = Math.max(nextId, v.id + 1);
                } catch (NumberFormatException e) {
                    // skip a damaged line
                }
            }
        }
        if (Files.isRegularFile(commentFile)) {
            for (String line : Files.readAllLines(commentFile, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t", -1);
                if (f.length < 6) {
                    continue;
                }
                try {
                    Comment c = new Comment();
                    c.id = Long.parseLong(f[0]);
                    c.video = Long.parseLong(f[1]);
                    c.author = f[2];
                    c.created = Long.parseLong(f[3]);
                    c.edited = "1".equals(f[4]);
                    c.text = un(f[5]);
                    if (videos.containsKey(c.video)) {
                        comments.put(c.id, c);
                    }
                    nextComment = Math.max(nextComment, c.id + 1);
                } catch (NumberFormatException e) {
                    // skip
                }
            }
        }
    }

    private void save() {
        StringBuilder sb = new StringBuilder();
        for (Video v : videos.values()) {
            sb.append(v.id).append('\t').append(v.owner).append('\t').append(v.kind).append('\t').append(v.ref).append('\t')
                    .append(v.status).append('\t').append(v.paused).append('\t').append(v.featured ? "1" : "0").append('\t')
                    .append(v.pos).append('\t').append(v.created).append('\t').append(v.updated).append('\t')
                    .append(v.durationMs).append('\t').append(v.views).append('\t').append(v.bytes).append('\t')
                    .append(esc(v.title)).append('\t').append(esc(v.description)).append('\t').append(esc(v.error)).append('\n');
        }
        write(file, sb.toString());
    }

    private void saveComments() {
        StringBuilder sb = new StringBuilder();
        for (Comment c : comments.values()) {
            sb.append(c.id).append('\t').append(c.video).append('\t').append(c.author).append('\t').append(c.created).append('\t')
                    .append(c.edited ? "1" : "0").append('\t').append(esc(c.text)).append('\n');
        }
        write(commentFile, sb.toString());
    }

    private static void write(Path target, String text) {
        try {
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.out.println("[videos] could not save " + target + ": " + e.getMessage());
        }
    }

    static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "").replace("\n", "\\n");
    }

    static String un(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == 't' ? '\t' : n == 'n' ? '\n' : n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String clean(String s, int max) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < max; i++) {
            char c = s.charAt(i);
            if (c == '\n' || c >= 0x20 && c != 0x7f) {
                sb.append(c == '\t' ? ' ' : c);
            }
        }
        return sb.toString().trim();
    }

    // ---- Videos ----

    public synchronized Video add(String owner, String kind, String ref, String title, String description, boolean featured,
                                  String status, long now) {
        Video v = new Video();
        v.id = nextId++;
        v.owner = owner;
        v.kind = kind;
        v.ref = ref;
        v.title = clean(title, MAX_TITLE);
        v.description = clean(description, MAX_DESCRIPTION);
        v.status = status;
        v.created = now;
        v.updated = now;
        v.featured = featured;
        v.pos = featured ? nextPos() : 0;
        if (FILE.equals(kind) && v.ref.isEmpty()) {
            v.ref = v.id + ".mp4";
        }
        videos.put(v.id, v);
        save();
        return v.copy();
    }

    private int nextPos() {
        int p = 0;
        for (Video v : videos.values()) {
            if (v.featured) {
                p = Math.max(p, v.pos);
            }
        }
        return p + 1;
    }

    public synchronized Video get(long id) {
        Video v = videos.get(id);
        return v == null ? null : v.copy();
    }

    public synchronized List<Video> list(Predicate<Video> filter) {
        List<Video> out = new ArrayList<Video>();
        for (Video v : videos.values()) {
            if (filter.test(v)) {
                out.add(v.copy());
            }
        }
        out.sort(Comparator.comparingLong((Video v) -> v.created).reversed());
        return out;
    }

    /** What Memoryq TV plays: featured, ready and not paused, in the admin's order. */
    public synchronized List<Video> tv() {
        List<Video> out = new ArrayList<Video>();
        for (Video v : videos.values()) {
            if (v.featured && v.isPublic()) {
                out.add(v.copy());
            }
        }
        out.sort(Comparator.comparingInt((Video v) -> v.pos).thenComparing(Comparator.comparingLong((Video v) -> v.created).reversed()));
        return out;
    }

    public synchronized List<Video> byOwner(String owner) {
        return list(v -> v.owner.equalsIgnoreCase(owner));
    }

    public synchronized long bytesOf(String owner) {
        long n = 0;
        for (Video v : videos.values()) {
            if (v.owner.equalsIgnoreCase(owner)) {
                n += v.bytes;
            }
        }
        return n;
    }

    public synchronized int countSince(String owner, long since) {
        int n = 0;
        for (Video v : videos.values()) {
            if (v.owner.equalsIgnoreCase(owner) && v.created >= since) {
                n++;
            }
        }
        return n;
    }

    public synchronized Video edit(long id, String title, String description, long now) {
        Video v = videos.get(id);
        if (v == null) {
            return null;
        }
        if (title != null) {
            String t = clean(title, MAX_TITLE);
            if (!t.isEmpty()) {
                v.title = t;
            }
        }
        if (description != null) {
            v.description = clean(description, MAX_DESCRIPTION);
        }
        v.updated = now;
        save();
        return v.copy();
    }

    public synchronized Video setPaused(long id, String by, long now) {
        Video v = videos.get(id);
        if (v == null) {
            return null;
        }
        v.paused = by == null ? "" : by;
        v.updated = now;
        save();
        return v.copy();
    }

    public synchronized Video setFeatured(long id, boolean on) {
        Video v = videos.get(id);
        if (v == null) {
            return null;
        }
        if (on && !v.featured) {
            v.pos = nextPos();
        }
        v.featured = on;
        save();
        return v.copy();
    }

    /** An account was replaced by another (a tester who registered): its videos move over. */
    public synchronized int reassign(String from, String to) {
        int n = 0;
        for (Video v : videos.values()) {
            if (v.owner.equalsIgnoreCase(from)) {
                v.owner = to;
                n++;
            }
        }
        if (n > 0) {
            save();
        }
        return n;
    }

    /** Moves a TV video one place up (-1) or down (+1). */
    public synchronized void move(long id, int dir) {
        List<Video> order = new ArrayList<Video>();
        for (Video v : videos.values()) {
            if (v.featured) {
                order.add(v);
            }
        }
        order.sort(Comparator.comparingInt((Video v) -> v.pos).thenComparing(Comparator.comparingLong((Video v) -> v.created).reversed()));
        for (int i = 0; i < order.size(); i++) {
            order.get(i).pos = i + 1;
        }
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).id == id) {
                int j = i + dir;
                if (j >= 0 && j < order.size()) {
                    order.get(i).pos = j + 1;
                    order.get(j).pos = i + 1;
                }
                break;
            }
        }
        save();
    }

    /** The background preparation of an uploaded file finished (or failed). */
    public synchronized void processed(long id, String status, long durationMs, long bytes, String error) {
        Video v = videos.get(id);
        if (v == null) {
            return;
        }
        v.status = status;
        v.durationMs = durationMs;
        v.bytes = bytes;
        v.error = error == null ? "" : error;
        save();
    }

    public synchronized void setMeta(long id, long durationMs, String title) {
        Video v = videos.get(id);
        if (v == null) {
            return;
        }
        if (durationMs > 0) {
            v.durationMs = durationMs;
        }
        if (title != null && !title.isEmpty() && v.title.isEmpty()) {
            v.title = clean(title, MAX_TITLE);
        }
        save();
    }

    public synchronized void addView(long id) {
        Video v = videos.get(id);
        if (v != null) {
            v.views++;
            save();
        }
    }

    /** Removes a video, its comments and its files. */
    public synchronized boolean delete(long id) {
        Video v = videos.remove(id);
        if (v == null) {
            return false;
        }
        comments.values().removeIf(c -> c.video == id);
        save();
        saveComments();
        deleteFiles(v);
        return true;
    }

    private void deleteFiles(Video v) {
        if (FILE.equals(v.kind)) {
            try {
                Files.deleteIfExists(dir.resolve(v.id + ".mp4"));
                Files.deleteIfExists(dir.resolve(v.id + ".jpg"));
            } catch (IOException e) {
                // left behind; harmless
            }
        }
    }

    /** Deletes everything belonging to accounts that no longer exist. Returns how many videos went. */
    public synchronized int purge(Predicate<String> exists) {
        List<Long> gone = new ArrayList<Long>();
        for (Video v : videos.values()) {
            if (!exists.test(v.owner)) {
                gone.add(v.id);
            }
        }
        for (long id : gone) {
            delete(id);
        }
        int before = comments.size();
        comments.values().removeIf(c -> !exists.test(c.author));
        if (comments.size() != before) {
            saveComments();
        }
        return gone.size();
    }

    public Path fileOf(Video v) {
        return dir.resolve(v.id + ".mp4");
    }

    public Path thumbOf(long id) {
        return dir.resolve(id + ".jpg");
    }

    // ---- Comments ----

    public synchronized List<Comment> comments(long video) {
        List<Comment> out = new ArrayList<Comment>();
        for (Comment c : comments.values()) {
            if (c.video == video) {
                out.add(c.copy());
            }
        }
        return out;
    }

    public synchronized int commentCount(long video) {
        int n = 0;
        for (Comment c : comments.values()) {
            if (c.video == video) {
                n++;
            }
        }
        return n;
    }

    public synchronized List<Comment> commentsBy(String author, int limit) {
        List<Comment> out = new ArrayList<Comment>();
        for (Comment c : comments.values()) {
            if (c.author.equalsIgnoreCase(author)) {
                out.add(c.copy());
            }
        }
        out.sort(Comparator.comparingLong((Comment c) -> c.created).reversed());
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    public synchronized int commentsSince(String author, long since) {
        int n = 0;
        for (Comment c : comments.values()) {
            if (c.author.equalsIgnoreCase(author) && c.created >= since) {
                n++;
            }
        }
        return n;
    }

    public synchronized Comment comment(long id) {
        Comment c = comments.get(id);
        return c == null ? null : c.copy();
    }

    public synchronized Comment addComment(long video, String author, String text, long now) {
        Comment c = new Comment();
        c.id = nextComment++;
        c.video = video;
        c.author = author;
        c.created = now;
        c.text = clean(text, MAX_COMMENT);
        comments.put(c.id, c);
        saveComments();
        return c.copy();
    }

    public synchronized Comment editComment(long id, String text) {
        Comment c = comments.get(id);
        if (c == null) {
            return null;
        }
        c.text = clean(text, MAX_COMMENT);
        c.edited = true;
        saveComments();
        return c.copy();
    }

    public synchronized boolean deleteComment(long id) {
        if (comments.remove(id) == null) {
            return false;
        }
        saveComments();
        return true;
    }

    /** The YouTube video id in a link (watch?v=, youtu.be/, /shorts/, /embed/, /live/), or null. */
    public static String youtubeId(String link) {
        if (link == null) {
            return null;
        }
        String s = link.trim();
        if (s.matches("[A-Za-z0-9_-]{11}")) {
            return s;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "^(?:https?://)?(?:www\\.|m\\.|music\\.)?(?:youtube\\.com/(?:watch\\?(?:.*&)?v=|shorts/|embed/|live/|v/)|youtu\\.be/)([A-Za-z0-9_-]{11})(?:[?&#/].*)?$",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(s);
        return m.matches() ? m.group(1) : null;
    }

    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
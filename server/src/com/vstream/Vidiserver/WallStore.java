package com.vstream.Vidiserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Wall posts: text and/or a picture on a person's wall, for "everyone" or "friends" only, with likes,
 * reports and comments. Comments form a tree: a comment can answer the post or another comment. Saved to wall.txt; pictures go in the wall folder under unguessable names, so a picture can only be
 * fetched by someone who was allowed to see its post.
 */
public final class WallStore {

    public static final int MAX_TEXT = 1000;
    public static final int MAX_IMAGE = 2 * 1024 * 1024;
    private static final int MAX_POSTS = 20000;
    private static final SecureRandom KEYS = new SecureRandom();

    public static final class Post {
        public long id;
        public String author = "";
        public String text = "";
        /** File name of the picture ("" for none); its random part doubles as the access key. */
        public String image = "";
        public boolean friendsOnly;
        public boolean test;
        public long created;
        public Set<String> likes = new LinkedHashSet<String>();
        /** Reports: who reported (lower case) and why. */
        public Map<String, String> reports = new LinkedHashMap<String, String>();
        public boolean reportDismissed;

        Post copy() {
            Post p = new Post();
            p.id = id;
            p.author = author;
            p.text = text;
            p.image = image;
            p.friendsOnly = friendsOnly;
            p.test = test;
            p.created = created;
            p.likes = new LinkedHashSet<String>(likes);
            p.reports = new LinkedHashMap<String, String>(reports);
            p.reportDismissed = reportDismissed;
            return p;
        }

        /** The secret part of the picture's name, given to people who may see the post. */
        public String imageKey() {
            int dash = image.indexOf('-');
            int dot = image.lastIndexOf('.');
            return dash > 0 && dot > dash ? image.substring(dash + 1, dot) : "";
        }

        public boolean likedBy(String user) {
            return likes.contains(user.toLowerCase(Locale.ROOT));
        }
    }

    public static final int MAX_COMMENT = 500;
    private static final int MAX_COMMENTS_PER_POST = 1000;

    public static final class Comment {
        public long id;
        public long post;
        /** The comment this one answers, or 0 when it answers the post. */
        public long parent;
        public String author = "";
        public String text = "";
        public long created;
        /** A deleted comment that still has replies is kept (without its text) so the replies stay in place. */
        public boolean deleted;

        Comment copy() {
            Comment c = new Comment();
            c.id = id;
            c.post = post;
            c.parent = parent;
            c.author = author;
            c.text = text;
            c.created = created;
            c.deleted = deleted;
            return c;
        }
    }

    public static final class Settings {
        /** A random post appears in the news after every this many stories (0 = never). */
        public int feedEvery = 5;
        public boolean testPostsOn = true;
    }

    private final Path file;
    private final Path dir;
    private final Map<Long, Post> posts = new LinkedHashMap<Long, Post>();
    private final Map<Long, Comment> comments = new LinkedHashMap<Long, Comment>();
    private long nextCommentId = 1;
    private Settings settings = new Settings();
    private long nextId = 1;
    private long lastTestPost = 0;
    private boolean dirty = false;

    public WallStore(Path file, Path imageDir) {
        this.file = file;
        this.dir = imageDir;
    }

    public synchronized void init() throws IOException {
        posts.clear();
        comments.clear();
        nextCommentId = 1;
        settings = new Settings();
        nextId = 1;
        lastTestPost = 0;
        Files.createDirectories(dir);
        if (Files.exists(file)) {
            load();
        }
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static String newKey() {
        byte[] b = new byte[12];
        KEYS.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    // ---- Settings ----

    public synchronized Settings settings() {
        Settings s = new Settings();
        s.feedEvery = settings.feedEvery;
        s.testPostsOn = settings.testPostsOn;
        return s;
    }

    public synchronized void setSettings(Settings s) {
        settings = s;
        dirty = true;
    }

    public synchronized long lastTestPost() {
        return lastTestPost;
    }

    // ---- Reading ----

    public synchronized Post get(long id) {
        Post p = posts.get(id);
        return p == null ? null : p.copy();
    }

    /** Someone's posts, newest first, older than beforeId (0 = from the newest). */
    public synchronized List<Post> byAuthor(String author, long beforeId, int limit) {
        List<Post> out = new ArrayList<Post>();
        List<Post> all = new ArrayList<Post>(posts.values());
        for (int i = all.size() - 1; i >= 0 && out.size() < limit; i--) {
            Post p = all.get(i);
            if (p.author.equalsIgnoreCase(author) && (beforeId <= 0 || p.id < beforeId)) {
                out.add(p.copy());
            }
        }
        return out;
    }

    public synchronized int countBy(String author, long since) {
        int n = 0;
        for (Post p : posts.values()) {
            if (p.author.equalsIgnoreCase(author) && p.created >= since) {
                n++;
            }
        }
        return n;
    }

    /** A random "everyone" post not written by viewer and not in skip, or null. Newer posts are a little likelier. */
    public synchronized Post randomPublic(String viewer, Collection<Long> skip, Random random) {
        List<Post> pool = new ArrayList<Post>();
        for (Post p : posts.values()) {
            if (!p.friendsOnly && !p.author.equalsIgnoreCase(viewer) && (skip == null || !skip.contains(p.id))) {
                pool.add(p);
            }
        }
        if (pool.isEmpty()) {
            return null;
        }
        // Pick twice and keep the newer one: random, but leaning towards recent posts.
        Post a = pool.get(random.nextInt(pool.size()));
        Post b = pool.get(random.nextInt(pool.size()));
        return (a.created >= b.created ? a : b).copy();
    }

    public synchronized List<Post> reported() {
        List<Post> out = new ArrayList<Post>();
        for (Post p : posts.values()) {
            if (!p.reports.isEmpty() && !p.reportDismissed) {
                out.add(p.copy());
            }
        }
        return out;
    }

    /** Recent "everyone" posts written by people who are not in skipAuthors (lower case), oldest first. */
    public synchronized List<Post> recentPublic(long since, Set<String> skipAuthors) {
        List<Post> out = new ArrayList<Post>();
        for (Post p : posts.values()) {
            if (!p.friendsOnly && p.created >= since && !skipAuthors.contains(lower(p.author))) {
                out.add(p.copy());
            }
        }
        return out;
    }

    /** True if any of these people (lower case) liked or commented on the post. */
    public synchronized boolean touchedBy(long post, Set<String> people) {
        Post p = posts.get(post);
        if (p == null) {
            return true;
        }
        for (String l : p.likes) {
            if (people.contains(l)) {
                return true;
            }
        }
        for (Comment c : comments.values()) {
            if (c.post == post && people.contains(lower(c.author))) {
                return true;
            }
        }
        return false;
    }

    /** Everyone who has written a post. */
    public synchronized Set<String> authors() {
        Set<String> out = new LinkedHashSet<String>();
        for (Post p : posts.values()) {
            out.add(p.author);
        }
        for (Comment c : comments.values()) {
            if (!c.deleted) {
                out.add(c.author);
            }
        }
        return out;
    }

    public synchronized int size() {
        return posts.size();
    }

    public synchronized int testCountBy(String author) {
        int n = 0;
        for (Post p : posts.values()) {
            if (p.test && p.author.equalsIgnoreCase(author)) {
                n++;
            }
        }
        return n;
    }

    /** The picture of a post, if key matches; otherwise null. */
    public synchronized byte[] image(long id, String key) {
        Post p = posts.get(id);
        if (p == null || p.image.isEmpty() || key == null || !p.imageKey().equals(key)) {
            return null;
        }
        try {
            return Files.readAllBytes(dir.resolve(p.image));
        } catch (IOException e) {
            return null;
        }
    }

    // ---- Changing ----

    /** Adds a post. image (already checked to be PNG or JPEG) may be null. */
    public synchronized Post create(String author, String text, byte[] image, String ext, boolean friendsOnly,
                                    boolean test, long now) throws IOException {
        Post p = new Post();
        p.id = nextId++;
        p.author = author;
        p.text = text == null ? "" : text;
        p.friendsOnly = friendsOnly;
        p.test = test;
        p.created = now;
        if (image != null) {
            p.image = "p" + p.id + "-" + newKey() + "." + ext;
            Files.write(dir.resolve(p.image), image);
        }
        posts.put(p.id, p);
        if (test) {
            lastTestPost = now;
        }
        while (posts.size() > MAX_POSTS) {
            removeQuiet(posts.keySet().iterator().next());
        }
        dirty = true;
        return p.copy();
    }

    // ---- Comments ----

    /** All comments on a post, oldest first. */
    public synchronized java.util.List<Comment> comments(long post) {
        java.util.List<Comment> out = new ArrayList<Comment>();
        for (Comment c : comments.values()) {
            if (c.post == post) {
                out.add(c.copy());
            }
        }
        return out;
    }

    public synchronized Comment comment(long id) {
        Comment c = comments.get(id);
        return c == null ? null : c.copy();
    }

    /** Comments on a post that are not deleted. */
    public synchronized int commentCount(long post) {
        int n = 0;
        for (Comment c : comments.values()) {
            if (c.post == post && !c.deleted) {
                n++;
            }
        }
        return n;
    }

    public synchronized int commentsBy(String author, long since) {
        int n = 0;
        for (Comment c : comments.values()) {
            if (c.author.equalsIgnoreCase(author) && c.created >= since) {
                n++;
            }
        }
        return n;
    }

    /**
     * Adds a comment; parent is 0 (answers the post) or a comment on the same post. Returns null if the post or
     * parent is missing, or the post has too many comments.
     */
    public synchronized Comment addComment(long post, long parent, String author, String text, long now) {
        if (!posts.containsKey(post)) {
            return null;
        }
        if (parent != 0) {
            Comment p = comments.get(parent);
            if (p == null || p.post != post) {
                return null;
            }
        }
        int n = 0;
        for (Comment c : comments.values()) {
            if (c.post == post) {
                n++;
            }
        }
        if (n >= MAX_COMMENTS_PER_POST) {
            return null;
        }
        Comment c = new Comment();
        c.id = nextCommentId++;
        c.post = post;
        c.parent = parent;
        c.author = author;
        c.text = text;
        c.created = now;
        comments.put(c.id, c);
        dirty = true;
        return c.copy();
    }

    private boolean hasReplies(long id) {
        for (Comment c : comments.values()) {
            if (c.parent == id) {
                return true;
            }
        }
        return false;
    }

    /** Deletes a comment. One with replies keeps its place (marked deleted); empty deleted parents are tidied away. */
    public synchronized boolean deleteComment(long id) {
        Comment c = comments.get(id);
        if (c == null || c.deleted) {
            return false;
        }
        if (hasReplies(id)) {
            c.deleted = true;
            c.text = "";
        } else {
            comments.remove(id);
            long up = c.parent;
            while (up != 0) {
                Comment p = comments.get(up);
                if (p == null || !p.deleted || hasReplies(up)) {
                    break;
                }
                comments.remove(up);
                up = p.parent;
            }
        }
        dirty = true;
        return true;
    }

    private void removeQuiet(long id) {
        java.util.Iterator<Comment> it = comments.values().iterator();
        while (it.hasNext()) {
            if (it.next().post == id) {
                it.remove();
            }
        }
        Post p = posts.remove(id);
        if (p != null && !p.image.isEmpty()) {
            try {
                Files.deleteIfExists(dir.resolve(p.image));
            } catch (IOException e) {
                // the picture file is left behind; harmless
            }
        }
    }

    public synchronized boolean delete(long id) {
        if (!posts.containsKey(id)) {
            return false;
        }
        removeQuiet(id);
        dirty = true;
        return true;
    }

    /** Removes every post by someone (their account was deleted). */
    public synchronized int deleteAllBy(String author) {
        List<Long> ids = new ArrayList<Long>();
        for (Post p : posts.values()) {
            if (p.author.equalsIgnoreCase(author)) {
                ids.add(p.id);
            }
        }
        for (Long id : ids) {
            removeQuiet(id);
        }
        List<Long> theirComments = new ArrayList<Long>();
        for (Comment c : comments.values()) {
            if (c.author.equalsIgnoreCase(author)) {
                theirComments.add(c.id);
            }
        }
        for (Long id : theirComments) {
            deleteComment(id);
        }
        for (Post p : posts.values()) {
            if (p.likes.remove(lower(author))) {
                dirty = true;
            }
        }
        if (!ids.isEmpty()) {
            dirty = true;
        }
        return ids.size();
    }

    public synchronized int setLike(long id, String user, boolean like) {
        Post p = posts.get(id);
        if (p == null) {
            return -1;
        }
        boolean changed = like ? p.likes.add(lower(user)) : p.likes.remove(lower(user));
        if (changed) {
            dirty = true;
        }
        return p.likes.size();
    }

    /** Records a report. Returns false if this person already reported the post. */
    public synchronized boolean report(long id, String user, String reason) {
        Post p = posts.get(id);
        if (p == null || p.reports.containsKey(lower(user))) {
            return false;
        }
        p.reports.put(lower(user), ProfileStore.clean(reason, 200));
        p.reportDismissed = false;
        dirty = true;
        return true;
    }

    public synchronized boolean dismissReports(long id) {
        Post p = posts.get(id);
        if (p == null || p.reports.isEmpty()) {
            return false;
        }
        p.reportDismissed = true;
        dirty = true;
        return true;
    }

    // ---- Storage ----

    private static String join(Collection<String> l, char sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) {
                sb.append(sep);
            }
            sb.append(s);
        }
        return sb.toString();
    }

    /** Text is stored with \n and \t written as \\n and \\t (and \\ as \\\\). */
    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t").replace("\r", "");
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == 'n' ? '\n' : (n == 't' ? '\t' : n));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private void load() throws IOException {
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            try {
                if ("S".equals(f[0]) && f.length >= 4) {
                    settings.feedEvery = Integer.parseInt(f[1]);
                    settings.testPostsOn = "1".equals(f[2]);
                    lastTestPost = Long.parseLong(f[3]);
                } else if ("P".equals(f[0]) && f.length >= 12) {
                    Post p = new Post();
                    p.id = Long.parseLong(f[1]);
                    p.author = f[2];
                    p.text = unescape(f[3]);
                    p.image = f[4];
                    p.friendsOnly = "1".equals(f[5]);
                    p.test = "1".equals(f[6]);
                    p.created = Long.parseLong(f[7]);
                    if (!f[8].isEmpty()) {
                        for (String l : f[8].split(",")) {
                            p.likes.add(l);
                        }
                    }
                    if (!f[9].isEmpty()) {
                        for (String r : f[9].split("\\|")) {
                            int eq = r.indexOf('=');
                            if (eq > 0) {
                                p.reports.put(r.substring(0, eq), r.substring(eq + 1));
                            }
                        }
                    }
                    p.reportDismissed = "1".equals(f[10]);
                    posts.put(p.id, p);
                    nextId = Math.max(nextId, p.id + 1);
                } else if ("C".equals(f[0]) && f.length >= 8) {
                    Comment c = new Comment();
                    c.id = Long.parseLong(f[1]);
                    c.post = Long.parseLong(f[2]);
                    c.parent = Long.parseLong(f[3]);
                    c.author = f[4];
                    c.text = unescape(f[5]);
                    c.created = Long.parseLong(f[6]);
                    c.deleted = "1".equals(f[7]);
                    comments.put(c.id, c);
                    nextCommentId = Math.max(nextCommentId, c.id + 1);
                }
            } catch (RuntimeException e) {
                // skip a damaged line
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
            System.out.println("[wall] could not save: " + e.getMessage());
        }
    }

    private void save() throws IOException {
        StringBuilder sb = new StringBuilder("# VStream wall posts\n");
        sb.append("S\t").append(settings.feedEvery).append('\t').append(settings.testPostsOn ? "1" : "0").append('\t')
                .append(lastTestPost).append('\n');
        for (Post p : posts.values()) {
            List<String> reps = new ArrayList<String>();
            for (Map.Entry<String, String> e : p.reports.entrySet()) {
                reps.add(e.getKey() + "=" + e.getValue().replace("|", "/"));
            }
            sb.append("P\t").append(p.id).append('\t').append(p.author).append('\t').append(escape(p.text)).append('\t')
                    .append(p.image).append('\t').append(p.friendsOnly ? "1" : "0").append('\t').append(p.test ? "1" : "0")
                    .append('\t').append(p.created).append('\t').append(join(p.likes, ',')).append('\t')
                    .append(join(reps, '|')).append('\t').append(p.reportDismissed ? "1" : "0").append("\tend\n");
        }
        for (Comment c : comments.values()) {
            sb.append("C\t").append(c.id).append('\t').append(c.post).append('\t').append(c.parent).append('\t')
                    .append(c.author).append('\t').append(escape(c.text)).append('\t').append(c.created).append('\t')
                    .append(c.deleted ? "1" : "0").append("\tend\n");
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
package com.vstream.Vidiserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Alma's story time: books a station owner adds (Studio - Playlists - Alma's stories). At a story break Alma reads
 * the next part (about one paragraph), then plays a song and carries on with the story at a later break, book after
 * book. Where each book is, is kept, so the story goes on after a restart.
 * Files: stories.tsv (the list) and books/BOOKID.txt (the text) in the given folder.
 */
public final class RadioStories {

    public static final int MAX_BOOKS = 50;
    public static final int MAX_TEXT = 2 * 1024 * 1024;
    /** A part is about one paragraph: short ones are joined, long ones split at sentences. */
    static final int MIN_WORDS = 60;
    static final int MAX_WORDS = 230;

    public static final class Book {
        public long id;
        public long station;
        public String title = "";
        public String author = "";
        public boolean enabled = true;
        /** The next part to read (0 = not started; parts = finished). */
        public int pos;
        public int parts;
        public int order;
        public long created;

        Book copy() {
            Book b = new Book();
            b.id = id;
            b.station = station;
            b.title = title;
            b.author = author;
            b.enabled = enabled;
            b.pos = pos;
            b.parts = parts;
            b.order = order;
            b.created = created;
            return b;
        }

        public boolean finished() {
            return pos >= parts;
        }
    }

    /** One part to read on air. */
    public static final class Part {
        public long book;
        public int index;
        public int of;
        public String title = "";
        public String author = "";
        public String text = "";

        public boolean first() {
            return index == 0;
        }

        public boolean last() {
            return index == of - 1;
        }
    }

    private final Path dir;
    private final Map<Long, Book> books = new LinkedHashMap<Long, Book>();
    private final Map<Long, List<String>> partsOf = new HashMap<Long, List<String>>();
    private long nextId = 1;

    public RadioStories(Path dir) {
        this.dir = dir;
    }

    public synchronized void init() throws IOException {
        Path f = dir.resolve("stories.tsv");
        if (!Files.isRegularFile(f)) {
            return;
        }
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            String[] v = line.split("\t", -1);
            if (v.length < 9 || !v[0].matches("\\d+")) {
                continue;
            }
            try {
                Book b = new Book();
                b.id = Long.parseLong(v[0]);
                b.station = Long.parseLong(v[1]);
                b.title = v[2];
                b.author = v[3];
                b.enabled = "1".equals(v[4]);
                b.pos = Integer.parseInt(v[5]);
                b.order = Integer.parseInt(v[6]);
                b.created = Long.parseLong(v[7]);
                Path t = textFile(b.id);
                List<String> parts = Files.isRegularFile(t) ? split(new String(Files.readAllBytes(t), StandardCharsets.UTF_8))
                        : new ArrayList<String>();
                b.parts = parts.size();
                b.pos = Math.min(b.pos, b.parts);
                partsOf.put(b.id, parts);
                books.put(b.id, b);
                nextId = Math.max(nextId, b.id + 1);
            } catch (RuntimeException e) {
                // a broken line is skipped
            }
        }
    }

    private Path textFile(long id) {
        return dir.resolve("books").resolve(id + ".txt");
    }

    private void save() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Book b : books.values()) {
            sb.append(b.id).append('\t').append(b.station).append('\t').append(clean(b.title)).append('\t').append(clean(b.author))
                    .append('\t').append(b.enabled ? "1" : "0").append('\t').append(b.pos).append('\t').append(b.order)
                    .append('\t').append(b.created).append("\tend\n");
        }
        Files.createDirectories(dir);
        Path part = dir.resolve("stories.tsv.part");
        Files.write(part, sb.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(part, dir.resolve("stories.tsv"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("[\\t\\r\\n]+", " ").trim();
    }

    /** A station's books, in reading order. */
    public synchronized List<Book> books(long station) {
        List<Book> out = new ArrayList<Book>();
        for (Book b : books.values()) {
            if (b.station == station) {
                out.add(b.copy());
            }
        }
        Collections.sort(out, (a, b) -> a.order != b.order ? Integer.compare(a.order, b.order) : Long.compare(a.id, b.id));
        return out;
    }

    public synchronized Book book(long id) {
        Book b = books.get(id);
        return b == null ? null : b.copy();
    }

    /** Adds a book (title, author, its text). */
    public synchronized Book add(long station, String title, String author, String text, long now) throws IOException {
        String t = clean(title);
        if (t.isEmpty() || t.length() > 120) {
            throw new IllegalArgumentException("give the book a title (up to 120 letters)");
        }
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("paste the book's text or choose a .txt file");
        }
        if (text.length() > MAX_TEXT) {
            throw new IllegalArgumentException("the text is too long (2 MB at most - split the book in parts)");
        }
        int have = 0, order = 0;
        for (Book b : books.values()) {
            if (b.station == station) {
                have++;
                order = Math.max(order, b.order + 1);
            }
        }
        if (have >= MAX_BOOKS) {
            throw new IllegalArgumentException(MAX_BOOKS + " books at most per station");
        }
        List<String> parts = split(text);
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("no readable text was found");
        }
        Book b = new Book();
        b.id = nextId++;
        b.station = station;
        b.title = t;
        b.author = clean(author).length() > 80 ? clean(author).substring(0, 80) : clean(author);
        b.created = now;
        b.order = order;
        b.parts = parts.size();
        Files.createDirectories(textFile(b.id).getParent());
        Files.write(textFile(b.id), text.getBytes(StandardCharsets.UTF_8));
        books.put(b.id, b);
        partsOf.put(b.id, parts);
        save();
        return b.copy();
    }

    /** Changes title, author, on/off, the place (part number to read next; -1 = keep) and the order (-1 = keep). */
    public synchronized Book update(long id, String title, String author, Boolean enabled, int pos, int order) throws IOException {
        Book b = books.get(id);
        if (b == null) {
            throw new IllegalArgumentException("no such book");
        }
        if (title != null && !clean(title).isEmpty()) {
            b.title = clean(title).length() > 120 ? clean(title).substring(0, 120) : clean(title);
        }
        if (author != null) {
            b.author = clean(author).length() > 80 ? clean(author).substring(0, 80) : clean(author);
        }
        if (enabled != null) {
            b.enabled = enabled;
        }
        if (pos >= 0) {
            b.pos = Math.min(pos, b.parts);
        }
        if (order >= 0) {
            b.order = order;
        }
        save();
        return b.copy();
    }

    public synchronized boolean delete(long station, long id) throws IOException {
        Book b = books.get(id);
        if (b == null || b.station != station) {
            return false;
        }
        books.remove(id);
        partsOf.remove(id);
        Files.deleteIfExists(textFile(id));
        save();
        return true;
    }

    /** Saves a station's books (list and texts) in dir, for restoreStation. */
    public synchronized void exportStation(long station, Path dir) throws IOException {
        RadioStories out = new RadioStories(dir);
        for (Book b : books.values()) {
            if (b.station != station) {
                continue;
            }
            Path t = textFile(b.id);
            if (!Files.isRegularFile(t)) {
                continue;
            }
            Files.createDirectories(out.textFile(b.id).getParent());
            Files.copy(t, out.textFile(b.id), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            out.books.put(b.id, b.copy());
        }
        out.save();
    }

    /** Puts a station's books back as exportStation saved them (books added since are deleted). */
    public synchronized void restoreStation(long station, Path dir) throws IOException {
        RadioStories saved = new RadioStories(dir);
        saved.init();
        for (Book b : new ArrayList<Book>(books.values())) {
            if (b.station == station) {
                books.remove(b.id);
                partsOf.remove(b.id);
                Files.deleteIfExists(textFile(b.id));
            }
        }
        for (Book b : saved.books.values()) {
            Book put = b.copy();
            if (books.containsKey(b.id)) {
                put.id = nextId++;
            }
            put.station = station;
            Files.createDirectories(textFile(put.id).getParent());
            Files.copy(saved.textFile(b.id), textFile(put.id), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            books.put(put.id, put);
            partsOf.put(put.id, saved.partsOf.get(b.id));
            nextId = Math.max(nextId, put.id + 1);
        }
        save();
    }

    /** One part of a book (for the Studio's preview), or "". */
    public synchronized String partText(long id, int index) {
        List<String> p = partsOf.get(id);
        return p == null || index < 0 || index >= p.size() ? "" : p.get(index);
    }

    /** What Alma reads next on this station: the first switched-on book that is not finished, or null. */
    public synchronized Part next(long station) {
        for (Book b : books(station)) {
            if (!b.enabled || b.finished()) {
                continue;
            }
            List<String> p = partsOf.get(b.id);
            if (p == null || p.isEmpty()) {
                continue;
            }
            Part part = new Part();
            part.book = b.id;
            part.index = b.pos;
            part.of = b.parts;
            part.title = b.title;
            part.author = b.author;
            part.text = p.get(b.pos);
            return part;
        }
        return null;
    }

    /** A part was read on air: the book moves on. */
    public synchronized void aired(Part p) {
        Book b = books.get(p.book);
        if (b != null && b.pos == p.index) {
            b.pos = p.index + 1;
            try {
                save();
            } catch (IOException e) {
                System.out.println("[radio] could not save the story place: " + e.getMessage());
            }
        }
    }

    // ---- Splitting a book into parts ----

    private static final Pattern SENTENCE = Pattern.compile("[^.!?]+(?:[.!?]+[\"'\u201d\u2019)]*|$)\\s*");

    /** Paragraphs of about MIN_WORDS..MAX_WORDS words (short ones joined, long ones split at sentence ends). */
    static List<String> split(String text) {
        String t = text.replace("\r\n", "\n").replace('\r', '\n').replace("\uFEFF", "");
        // paragraphs: blank lines; when the text has none, single line breaks
        String[] paras = t.contains("\n\n") ? t.split("\\n\\s*\\n") : t.split("\\n");
        List<String> clean = new ArrayList<String>();
        for (String p : paras) {
            String c = p.replaceAll("\\s+", " ").trim();
            if (!c.isEmpty()) {
                clean.add(c);
            }
        }
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        for (String p : clean) {
            int w = words(p);
            if (w > MAX_WORDS) {
                StringBuilder piece = new StringBuilder();
                if (cur.length() > 0 && words(cur.toString()) < MIN_WORDS) {
                    piece.append(cur); // a short paragraph before a long one is read with its first part
                } else if (cur.length() > 0) {
                    out.add(cur.toString());
                }
                cur.setLength(0);
                Matcher m = SENTENCE.matcher(p);
                while (m.find()) {
                    String s = m.group().trim();
                    if (s.isEmpty()) {
                        continue;
                    }
                    if (piece.length() > 0 && words(piece.toString()) + words(s) > MAX_WORDS * 3 / 4) {
                        out.add(piece.toString().trim());
                        piece.setLength(0);
                    }
                    piece.append(piece.length() > 0 ? " " : "").append(s);
                }
                if (piece.length() > 0) {
                    out.add(piece.toString().trim());
                }
                continue;
            }
            if (cur.length() > 0 && words(cur.toString()) + w > MAX_WORDS) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            cur.append(cur.length() > 0 ? " " : "").append(p);
            if (words(cur.toString()) >= MIN_WORDS) {
                out.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) {
            if (!out.isEmpty() && words(cur.toString()) < MIN_WORDS / 2 && words(out.get(out.size() - 1)) + words(cur.toString()) <= MAX_WORDS + 40) {
                out.set(out.size() - 1, out.get(out.size() - 1) + " " + cur);
            } else {
                out.add(cur.toString());
            }
        }
        return out;
    }

    static int words(String s) {
        String t = s.trim();
        return t.isEmpty() ? 0 : t.split("\\s+").length;
    }
}
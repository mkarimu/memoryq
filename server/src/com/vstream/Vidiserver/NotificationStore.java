package com.vstream.Vidiserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Notifications: "someone commented on your post", "replied to your comment", "liked your post", "added you as a
 * friend". Each person keeps their newest 200, for 60 days. Saved to notifications.txt.
 */
public final class NotificationStore {

    public static final String COMMENT = "comment";
    public static final String REPLY = "reply";
    public static final String LIKE = "like";
    public static final String FRIEND = "friend";

    private static final int MAX_PER_USER = 200;
    private static final long KEEP_MS = 60L * 24 * 3600000L;

    public static final class Note {
        public long id;
        public String user = "";
        public String type = "";
        public String actor = "";
        public long post;
        public long comment;
        public String text = "";
        public long created;
        public boolean read;

        Note copy() {
            Note n = new Note();
            n.id = id;
            n.user = user;
            n.type = type;
            n.actor = actor;
            n.post = post;
            n.comment = comment;
            n.text = text;
            n.created = created;
            n.read = read;
            return n;
        }
    }

    private final Path file;
    private final Map<Long, Note> notes = new LinkedHashMap<Long, Note>();
    private long nextId = 1;
    private boolean dirty = false;

    public NotificationStore(Path file) {
        this.file = file;
    }

    public synchronized void init() throws IOException {
        notes.clear();
        nextId = 1;
        if (Files.exists(file)) {
            load();
        }
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    /**
     * Adds a notification for user. Nothing is added for yourself, and a "like" is only added once per person and
     * post. Returns the new notification, or null.
     */
    public synchronized Note add(String user, String type, String actor, long post, long comment, String text, long now) {
        if (user == null || actor == null || user.equalsIgnoreCase(actor)) {
            return null;
        }
        if (LIKE.equals(type) || FRIEND.equals(type)) {
            for (Note n : notes.values()) {
                if (n.type.equals(type) && n.user.equalsIgnoreCase(user) && n.actor.equalsIgnoreCase(actor) && n.post == post) {
                    return null;
                }
            }
        }
        Note n = new Note();
        n.id = nextId++;
        n.user = user;
        n.type = type;
        n.actor = actor;
        n.post = post;
        n.comment = comment;
        n.text = ProfileStore.clean(text, 100);
        n.created = now;
        notes.put(n.id, n);
        prune(user, now);
        dirty = true;
        return n.copy();
    }

    private void prune(String user, long now) {
        List<Long> mine = new ArrayList<Long>();
        List<Long> old = new ArrayList<Long>();
        for (Note n : notes.values()) {
            if (now - n.created > KEEP_MS) {
                old.add(n.id);
            } else if (n.user.equalsIgnoreCase(user)) {
                mine.add(n.id);
            }
        }
        for (Long id : old) {
            notes.remove(id);
        }
        for (int i = 0; i < mine.size() - MAX_PER_USER; i++) {
            notes.remove(mine.get(i));
        }
    }

    /** A person's notifications, newest first. */
    public synchronized List<Note> list(String user, int limit) {
        List<Note> all = new ArrayList<Note>(notes.values());
        List<Note> out = new ArrayList<Note>();
        for (int i = all.size() - 1; i >= 0 && out.size() < limit; i--) {
            if (all.get(i).user.equalsIgnoreCase(user)) {
                out.add(all.get(i).copy());
            }
        }
        return out;
    }

    public synchronized int unread(String user) {
        int n = 0;
        for (Note x : notes.values()) {
            if (!x.read && x.user.equalsIgnoreCase(user)) {
                n++;
            }
        }
        return n;
    }

    /** Marks one (id > 0) or all of a person's notifications as read. */
    public synchronized void markRead(String user, long id) {
        for (Note n : notes.values()) {
            if (n.user.equalsIgnoreCase(user) && !n.read && (id <= 0 || n.id == id)) {
                n.read = true;
                dirty = true;
            }
        }
    }

    /** Forgets everything to or from someone (their account is gone). */
    public synchronized void removeUser(String user) {
        java.util.Iterator<Note> it = notes.values().iterator();
        while (it.hasNext()) {
            Note n = it.next();
            if (n.user.equalsIgnoreCase(user) || n.actor.equalsIgnoreCase(user)) {
                it.remove();
                dirty = true;
            }
        }
    }

    /** Everyone mentioned in a notification (to tidy up deleted accounts). */
    public synchronized java.util.Set<String> people() {
        java.util.Set<String> out = new java.util.LinkedHashSet<String>();
        for (Note n : notes.values()) {
            out.add(lower(n.user));
            out.add(lower(n.actor));
        }
        return out;
    }

    // ---- Storage ----

    private void load() throws IOException {
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            try {
                if ("N".equals(f[0]) && f.length >= 10) {
                    Note n = new Note();
                    n.id = Long.parseLong(f[1]);
                    n.user = f[2];
                    n.type = f[3];
                    n.actor = f[4];
                    n.post = Long.parseLong(f[5]);
                    n.comment = Long.parseLong(f[6]);
                    n.text = f[7];
                    n.created = Long.parseLong(f[8]);
                    n.read = "1".equals(f[9]);
                    notes.put(n.id, n);
                    nextId = Math.max(nextId, n.id + 1);
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
            StringBuilder sb = new StringBuilder("# VStream notifications\n");
            for (Note n : notes.values()) {
                sb.append("N\t").append(n.id).append('\t').append(n.user).append('\t').append(n.type).append('\t')
                        .append(n.actor).append('\t').append(n.post).append('\t').append(n.comment).append('\t')
                        .append(n.text).append('\t').append(n.created).append('\t').append(n.read ? "1" : "0")
                        .append("\tend\n");
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, sb.toString().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (IOException e) {
            System.out.println("[notifications] could not save: " + e.getMessage());
        }
    }
}
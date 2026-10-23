package com.vstream.Vidiserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Chat rooms and messages. A room is a channel room (id CHANNEL-nnn), a public group (id g-name, readable by
 * everyone) or a private group (id g-name, members only, joined by invite). Messages are single-line text and
 * can be replied to, reacted to, edited, forwarded, pinned and deleted. Receipts, online status, the privacy
 * switches, group membership and invites live here too. Saved to a text file; keep it private.
 */
public final class ChatStore {

    /** The reactions that can be used. */
    public static final List<String> REACTIONS = Collections.unmodifiableList(Arrays.asList(
            "\uD83D\uDC4D", "\u2764\uFE0F", "\uD83D\uDE02", "\uD83D\uDE2E", "\uD83D\uDE22", "\uD83D\uDE4F"));
    public static final long EDIT_WINDOW_MS = 15L * 60 * 1000;
    public static final int MAX_PINS = 3;

    public static final class ChatException extends Exception {
        private static final long serialVersionUID = 1L;
        public final int status;

        public ChatException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    public static final class Message {
        public final long id;
        public final String room;
        public final String user;
        public final long time;
        public final long replyTo;
        public final boolean deleted;
        public final String text;
        public final boolean edited;
        public final Map<String, List<String>> reactions;
        public final String forwardedFrom;
        public final boolean pinned;

        Message(long id, String room, String user, long time, long replyTo, boolean deleted, String text,
                boolean edited, Map<String, List<String>> reactions, String forwardedFrom, boolean pinned) {
            this.id = id;
            this.room = room;
            this.user = user;
            this.time = time;
            this.replyTo = replyTo;
            this.deleted = deleted;
            this.text = text;
            this.edited = edited;
            this.reactions = reactions;
            this.forwardedFrom = forwardedFrom;
            this.pinned = pinned;
        }
    }

    public static final class RoomInfo {
        public final String id;
        public final boolean group;
        public final String title;
        public final String owner;
        public final long lastId;
        public final String lastUser;
        public final String lastText;
        public final long lastTime;
        public final int recent;
        public final int total;
        public final boolean priv;
        public final int members;

        RoomInfo(String id, boolean group, String title, String owner, long lastId, String lastUser,
                 String lastText, long lastTime, int recent, int total, boolean priv, int members) {
            this.id = id;
            this.group = group;
            this.title = title;
            this.owner = owner;
            this.lastId = lastId;
            this.lastUser = lastUser;
            this.lastText = lastText;
            this.lastTime = lastTime;
            this.recent = recent;
            this.total = total;
            this.priv = priv;
            this.members = members;
        }
    }

    /** One search result. */
    public static final class Hit {
        public final String room;
        public final long id;
        public final String user;
        public final long time;
        public final String text;

        Hit(String room, long id, String user, long time, String text) {
            this.room = room;
            this.id = id;
            this.user = user;
            this.time = time;
            this.text = text;
        }
    }

    /** How many other people got and read a message, and (if asked for) who. */
    public static final class Receipt {
        public final int deliveredCount;
        public final int readCount;
        public final List<String> delivered;
        public final List<String> read;

        Receipt(int deliveredCount, int readCount, List<String> delivered, List<String> read) {
            this.deliveredCount = deliveredCount;
            this.readCount = readCount;
            this.delivered = delivered;
            this.read = read;
        }
    }

    public static final class Member {
        public final String name;
        public final boolean owner;
        public final long joined;

        Member(String name, boolean owner, long joined) {
            this.name = name;
            this.owner = owner;
            this.joined = joined;
        }
    }

    /** An invite code for a private group. The code is shown as XXXXX-XXXXX. */
    public static final class Invite {
        public final String code;
        public final String room;
        public final String createdBy;
        public final long created;
        public final long expires;
        public final int maxUses;
        public final int uses;

        Invite(String code, String room, String createdBy, long created, long expires, int maxUses, int uses) {
            this.code = code;
            this.room = room;
            this.createdBy = createdBy;
            this.created = created;
            this.expires = expires;
            this.maxUses = maxUses;
            this.uses = uses;
        }
    }

    /** What anyone holding a valid code may see before joining. */
    public static final class InvitePreview {
        public final String code;
        public final String title;
        public final String owner;
        public final int members;
        public final long expires;

        InvitePreview(String code, String title, String owner, int members, long expires) {
            this.code = code;
            this.title = title;
            this.owner = owner;
            this.members = members;
            this.expires = expires;
        }
    }

    /** A pending invitation sent to a user by the group owner. */
    public static final class Invitation {
        public final String room;
        public final String title;
        public final String from;
        public final long time;

        Invitation(String room, String title, String from, long time) {
            this.room = room;
            this.title = title;
            this.from = from;
            this.time = time;
        }
    }

    private static final class Msg {
        final long id;
        final String room;
        final String user;
        final long time;
        final long replyTo;
        boolean deleted;
        boolean edited;
        String text;
        String fwdFrom = "";
        final Map<String, LinkedHashSet<String>> reactions = new LinkedHashMap<String, LinkedHashSet<String>>();

        Msg(long id, String room, String user, long time, long replyTo, String text) {
            this.id = id;
            this.room = room;
            this.user = user;
            this.time = time;
            this.replyTo = replyTo;
            this.text = text;
        }

        Message view(Room r) {
            Map<String, List<String>> copy = new LinkedHashMap<String, List<String>>();
            if (!deleted) {
                for (Map.Entry<String, LinkedHashSet<String>> e : reactions.entrySet()) {
                    if (!e.getValue().isEmpty()) {
                        copy.put(e.getKey(), new ArrayList<String>(e.getValue()));
                    }
                }
            }
            return new Message(id, room, user, time, replyTo, deleted, deleted ? "" : text,
                    edited && !deleted, copy, deleted ? "" : fwdFrom, !deleted && r.pins.contains(id));
        }
    }

    private static final class MemberRec {
        final String name;
        final boolean owner;
        final long joined;

        MemberRec(String name, boolean owner, long joined) {
            this.name = name;
            this.owner = owner;
            this.joined = joined;
        }
    }

    private static final class InviteRec {
        final String code;
        final String room;
        final String by;
        final long created;
        final long expires;
        final int maxUses;
        int uses;

        InviteRec(String code, String room, String by, long created, long expires, int maxUses) {
            this.code = code;
            this.room = room;
            this.by = by;
            this.created = created;
            this.expires = expires;
            this.maxUses = maxUses;
        }
    }

    private static final class InvitationRec {
        final String room;
        final String toName;
        final String from;
        final long time;

        InvitationRec(String room, String toName, String from, long time) {
            this.room = room;
            this.toName = toName;
            this.from = from;
            this.time = time;
        }
    }

    /** One private message between the super-admin and a group admin. read = the other side has read it. */
    public static final class Mail {
        public final long id;
        public final boolean fromAdmin;
        public final long time;
        public final String text;
        public final boolean read;

        Mail(long id, boolean fromAdmin, long time, String text, boolean read) {
            this.id = id;
            this.fromAdmin = fromAdmin;
            this.time = time;
            this.text = text;
            this.read = read;
        }
    }

    /** A person who owns at least one group, with those groups. */
    public static final class GroupAdminInfo {
        public final String name;
        public final List<RoomInfo> groups;

        GroupAdminInfo(String name, List<RoomInfo> groups) {
            this.name = name;
            this.groups = groups;
        }
    }

    private static final class MailRec {
        final long id;
        final boolean fromAdmin;
        final long time;
        final String text;
        boolean readByAdmin;
        boolean readByUser;

        MailRec(long id, boolean fromAdmin, long time, String text) {
            this.id = id;
            this.fromAdmin = fromAdmin;
            this.time = time;
            this.text = text;
        }
    }

    /** Someone a group admin removed or suspended. until = 0 means until reinstated. */
    public static final class BanInfo {
        public final String key;
        public final boolean suspended;
        public final long until;

        BanInfo(String key, boolean suspended, long until) {
            this.key = key;
            this.suspended = suspended;
            this.until = until;
        }
    }

    private static final class Room {
        final String id;
        final boolean group;
        String title;
        final String owner;
        final long created;
        boolean priv = false;
        final ArrayList<Msg> messages = new ArrayList<Msg>();
        final LinkedHashSet<Long> pins = new LinkedHashSet<Long>();
        final LinkedHashMap<String, MemberRec> members = new LinkedHashMap<String, MemberRec>();
        final LinkedHashSet<String> banned = new LinkedHashSet<String>();
        final LinkedHashMap<String, Long> groupMutes = new LinkedHashMap<String, Long>();
        final LinkedHashMap<String, Long> suspendedUntil = new LinkedHashMap<String, Long>();

        Room(String id, boolean group, String title, String owner, long created) {
            this.id = id;
            this.group = group;
            this.title = title;
            this.owner = owner;
            this.created = created;
        }
    }

    private static final class Cursor {
        String name;
        long delivered;
        long read;

        Cursor(String name) {
            this.name = name;
        }
    }

    private static final int MAX_TEXT = 500;
    private static final int PER_ROOM = Math.max(50, Integer.getInteger("vstream.chatKeep", 500));
    private static final int MAX_GROUPS = 200;
    private static final int GROUPS_PER_USER = 5;
    private static final int GROUP_CREATES_PER_DAY = 3;
    private static final int MAX_CURSORS_PER_ROOM = 2000;
    private static final int MAX_MEMBERS = Math.max(2, Integer.getInteger("vstream.groupMaxMembers", 100));
    private static final int MAX_ACTIVE_INVITES = 20;
    private static final int MAX_PENDING_PER_ROOM = 50;
    private static final int INVITES_PER_DAY = 40;
    private static final int CODE_LEN = 10;
    private static final String CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long INVITATION_TTL_MS = 30L * DAY;
    private static final long TYPING_MS = 5000;
    private static final long ONLINE_ROOM_MS = 15000;
    private static final long ONLINE_MS = 60000;
    private static final Pattern GROUP_ID = Pattern.compile("g-[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path file;
    private final LongSupplier clock;
    private final Limiter limiter;
    private final Map<String, Room> rooms = new LinkedHashMap<String, Room>();
    private final Map<String, Long> muted = new LinkedHashMap<String, Long>();
    private final Map<String, Map<String, Long>> typingMap = new LinkedHashMap<String, Map<String, Long>>();
    private final Map<String, Map<String, Cursor>> cursors = new LinkedHashMap<String, Map<String, Cursor>>();
    private final Map<String, boolean[]> prefs = new LinkedHashMap<String, boolean[]>();
    private final Map<String, Long> lastSeen = new LinkedHashMap<String, Long>();
    private final Map<String, String> names = new LinkedHashMap<String, String>();
    private final Map<String, Map<String, Long>> roomSeen = new LinkedHashMap<String, Map<String, Long>>();
    private final Map<String, InviteRec> invites = new LinkedHashMap<String, InviteRec>();
    private final Map<String, Map<String, InvitationRec>> invitations = new LinkedHashMap<String, Map<String, InvitationRec>>();
    private final Map<String, List<MailRec>> mail = new LinkedHashMap<String, List<MailRec>>();
    private final Map<String, String> mailNames = new LinkedHashMap<String, String>();
    private long nextId = 1;
    private boolean dirty = false;

    public ChatStore(Path file, LongSupplier clock) {
        this.file = file;
        this.clock = clock;
        this.limiter = new Limiter(clock);
    }

    public long now() {
        return clock.getAsLong();
    }

    public static boolean isGroupId(String id) {
        return id != null && GROUP_ID.matcher(id).matches();
    }

    private static String lower(String user) {
        return user.toLowerCase(Locale.ROOT);
    }

    /** Loads the saved chat, or on the very first start creates the General group. */
    public synchronized void init() throws IOException {
        rooms.clear();
        muted.clear();
        typingMap.clear();
        cursors.clear();
        prefs.clear();
        invites.clear();
        invitations.clear();
        mail.clear();
        mailNames.clear();
        nextId = 1;
        boolean fresh = !Files.exists(file);
        if (!fresh) {
            load();
        } else {
            rooms.put("g-general", new Room("g-general", true, "General", "admin", clock.getAsLong()));
            dirty = true;
            save();
        }
    }

    // ---- Reading ----

    /** The newest messages a person wrote, in every room (for the super-admin's view of an account). */
    public synchronized List<Message> messagesBy(String user, int limit) {
        List<Message> out = new ArrayList<Message>();
        for (Room r : rooms.values()) {
            for (Msg m : r.messages) {
                if (m.user.equalsIgnoreCase(user) && !m.deleted) {
                    out.add(m.view(r));
                }
            }
        }
        out.sort((a, b) -> Long.compare(b.time, a.time));
        return out.size() > limit ? new ArrayList<Message>(out.subList(0, limit)) : out;
    }

    /** Groups a person owns, and groups they are a member of. */
    public synchronized List<String[]> groupsOf(String user) {
        List<String[]> out = new ArrayList<String[]>();
        for (Room r : rooms.values()) {
            if (!r.group) {
                continue;
            }
            boolean owner = r.owner.equalsIgnoreCase(user);
            boolean member = r.members.containsKey(user.toLowerCase(java.util.Locale.ROOT));
            if (owner || member) {
                out.add(new String[] {r.id, r.title, owner ? "owner" : "member", String.valueOf(r.members.size()), r.priv ? "private" : "public"});
            }
        }
        return out;
    }

    public synchronized boolean hasGroup(String id) {
        Room r = rooms.get(id);
        return r != null && r.group;
    }

    public synchronized boolean isPrivate(String id) {
        Room r = rooms.get(id);
        return r != null && r.group && r.priv;
    }

    public synchronized String groupOwner(String id) {
        Room r = rooms.get(id);
        return (r != null && r.group) ? r.owner : "";
    }

    /**
     * True if the user may see this room. Channel rooms and public groups: everyone. Private groups: members
     * only. user may be null (a viewer who is not signed in).
     */
    public synchronized boolean canAccess(String roomId, String user) {
        Room r = rooms.get(roomId);
        if (r == null) {
            return !isGroupId(roomId);
        }
        if (!r.group || !r.priv) {
            return true;
        }
        return user != null && r.members.containsKey(lower(user));
    }

    /** Every group, public and private. The caller must filter what each person may see. */
    public synchronized List<RoomInfo> listGroups() {
        List<RoomInfo> result = new ArrayList<RoomInfo>();
        for (Room r : rooms.values()) {
            if (r.group) {
                result.add(infoOf(r, r.title));
            }
        }
        return result;
    }

    /** Summary of a room. Rooms with no messages yet (channel rooms) show as empty. */
    public synchronized RoomInfo info(String roomId, String fallbackTitle) {
        Room r = rooms.get(roomId);
        if (r == null) {
            return new RoomInfo(roomId, isGroupId(roomId), fallbackTitle, "", 0, "", "", 0, 0, 0, false, 0);
        }
        return infoOf(r, r.group ? r.title : fallbackTitle);
    }

    /** Messages with an id above "after" (oldest first). With after = 0 the latest "limit" messages. */
    public synchronized List<Message> messages(String roomId, long after, int limit) {
        List<Message> out = new ArrayList<Message>();
        Room r = rooms.get(roomId);
        if (r == null) {
            return out;
        }
        int n = r.messages.size();
        if (after <= 0) {
            for (int i = Math.max(0, n - limit); i < n; i++) {
                out.add(r.messages.get(i).view(r));
            }
        } else {
            for (Msg m : r.messages) {
                if (m.id > after) {
                    out.add(m.view(r));
                    if (out.size() >= limit) {
                        break;
                    }
                }
            }
        }
        return out;
    }

    /** Counts messages above "after" that are not deleted and not written by excludeUser (may be null). */
    public synchronized int countAfter(String roomId, long after, String excludeUser) {
        Room r = rooms.get(roomId);
        if (r == null) {
            return 0;
        }
        int n = 0;
        for (Msg m : r.messages) {
            if (m.id > after && !m.deleted
                    && (excludeUser == null || !m.user.equalsIgnoreCase(excludeUser))) {
                n++;
            }
        }
        return n;
    }

    public synchronized Message find(String roomId, long id) {
        Room r = rooms.get(roomId);
        Msg m = r == null ? null : findMsg(r, id);
        return m == null ? null : m.view(r);
    }

    public synchronized long lastId(String roomId) {
        Room r = rooms.get(roomId);
        return (r == null || r.messages.isEmpty()) ? 0 : r.messages.get(r.messages.size() - 1).id;
    }

    /** Messages whose text contains the words searched for, newest first. roomId may be null for every room. */
    public synchronized List<Hit> search(String query, String roomId, int limit) {
        return search(query, roomId, limit, new Predicate<String>() {
            @Override
            public boolean test(String room) {
                return true;
            }
        });
    }

    /** The same, but only in rooms the caller is allowed to read. */
    public synchronized List<Hit> search(String query, String roomId, int limit, Predicate<String> readable) {
        List<Hit> hits = new ArrayList<Hit>();
        String q = query == null ? "" : lower(query.trim());
        if (q.length() < 2) {
            return hits;
        }
        for (Room r : rooms.values()) {
            if (roomId != null && !r.id.equals(roomId)) {
                continue;
            }
            if (!readable.test(r.id)) {
                continue;
            }
            for (Msg m : r.messages) {
                if (!m.deleted && lower(m.text).contains(q)) {
                    hits.add(new Hit(r.id, m.id, m.user, m.time, m.text));
                }
            }
        }
        Collections.sort(hits, new Comparator<Hit>() {
            @Override
            public int compare(Hit a, Hit b) {
                int c = Long.compare(b.time, a.time);
                return c != 0 ? c : Long.compare(b.id, a.id);
            }
        });
        return hits.size() > limit ? new ArrayList<Hit>(hits.subList(0, limit)) : hits;
    }

    // ---- Posting, forwarding, editing, deleting ----

    private void checkSendAllowed(String user) throws ChatException {
        if (isMuted(user)) {
            throw new ChatException(403, "you are muted by a moderator");
        }
        String k = lower(user);
        if (limiter.count("b|" + k, 3000) >= 3 || limiter.count("u|" + k, 60000) >= 20) {
            throw new ChatException(429, "you are sending too fast - wait a moment");
        }
    }

    private Room roomForPost(String roomId) throws ChatException {
        Room room = rooms.get(roomId);
        if (room == null) {
            if (isGroupId(roomId)) {
                throw new ChatException(404, "no such room");
            }
            room = new Room(roomId, false, roomId, "", clock.getAsLong());
            rooms.put(roomId, room);
        }
        return room;
    }

    private Message append(Room room, Msg m, String user) {
        room.messages.add(m);
        while (room.messages.size() > PER_ROOM) {
            Msg old = room.messages.remove(0);
            room.pins.remove(old.id);
        }
        String k = lower(user);
        limiter.record("b|" + k);
        limiter.record("u|" + k);
        Map<String, Long> typers = typingMap.get(room.id);
        if (typers != null) {
            typers.remove(user);
        }
        dirty = true;
        return m.view(room);
    }

    public synchronized Message post(String roomId, String user, String rawText, long replyTo) throws ChatException {
        String text = clean(rawText);
        if (text.isEmpty()) {
            throw new ChatException(400, "write a message first");
        }
        if (text.length() > MAX_TEXT) {
            throw new ChatException(400, "messages can be at most " + MAX_TEXT + " characters");
        }
        checkSendAllowed(user);
        Room room = roomForPost(roomId);
        checkGroupMute(room, user);
        long reply = 0;
        if (replyTo > 0 && findMsg(room, replyTo) != null) {
            reply = replyTo;
        }
        Msg m = new Msg(nextId++, roomId, user, clock.getAsLong(), reply, text);
        return append(room, m, user);
    }

    /** Copies a message into another room, marked as forwarded from the original author. */
    public synchronized Message forward(String fromRoom, long id, String toRoom, String user) throws ChatException {
        Room src = rooms.get(fromRoom);
        Msg original = src == null ? null : findMsg(src, id);
        if (original == null || original.deleted) {
            throw new ChatException(404, "no such message");
        }
        checkSendAllowed(user);
        Room dst = roomForPost(toRoom);
        checkGroupMute(dst, user);
        Msg copy = new Msg(nextId++, toRoom, user, clock.getAsLong(), 0, original.text);
        copy.fwdFrom = original.fwdFrom.isEmpty() ? original.user : original.fwdFrom;
        return append(dst, copy, user);
    }

    /** The author can change the text for 15 minutes. The message is then marked as edited. */
    public synchronized Message edit(String roomId, long id, String actor, String rawText) throws ChatException {
        String text = clean(rawText);
        if (text.isEmpty()) {
            throw new ChatException(400, "write a message first");
        }
        if (text.length() > MAX_TEXT) {
            throw new ChatException(400, "messages can be at most " + MAX_TEXT + " characters");
        }
        if (isMuted(actor)) {
            throw new ChatException(403, "you are muted by a moderator");
        }
        String k = lower(actor);
        if (limiter.count("e|" + k, 60000) >= 10) {
            throw new ChatException(429, "you are editing too fast - wait a moment");
        }
        Room r = rooms.get(roomId);
        Msg m = r == null ? null : findMsg(r, id);
        if (m == null || m.deleted) {
            throw new ChatException(404, "no such message");
        }
        if (!m.user.equalsIgnoreCase(actor)) {
            throw new ChatException(403, "you can only edit your own messages");
        }
        if (clock.getAsLong() - m.time > EDIT_WINDOW_MS) {
            throw new ChatException(403, "messages can only be edited for 15 minutes");
        }
        checkGroupMute(r, actor);
        if (!text.equals(m.text)) {
            m.text = text;
            m.edited = true;
            dirty = true;
        }
        limiter.record("e|" + k);
        return m.view(r);
    }

    /** Adds the reaction, or removes it if this user already used it. Returns true when it was added. */
    public synchronized boolean react(String roomId, long id, String user, String emoji) throws ChatException {
        if (emoji == null || !REACTIONS.contains(emoji)) {
            throw new ChatException(400, "that reaction is not available");
        }
        if (isMuted(user)) {
            throw new ChatException(403, "you are muted by a moderator");
        }
        String k = lower(user);
        if (limiter.count("r|" + k, 60000) >= 30) {
            throw new ChatException(429, "you are reacting too fast - wait a moment");
        }
        Room r = rooms.get(roomId);
        Msg m = r == null ? null : findMsg(r, id);
        if (m == null || m.deleted) {
            throw new ChatException(404, "no such message");
        }
        checkGroupMute(r, user);
        LinkedHashSet<String> set = m.reactions.get(emoji);
        if (set == null) {
            set = new LinkedHashSet<String>();
            m.reactions.put(emoji, set);
        }
        boolean added = !set.remove(user);
        if (added) {
            set.add(user);
        }
        if (set.isEmpty()) {
            m.reactions.remove(emoji);
        }
        limiter.record("r|" + k);
        dirty = true;
        return added;
    }

    /** The author, the admin or the group creator may delete a message. It stays as a "deleted" placeholder. */
    public synchronized void delete(String roomId, long id, String actor, boolean admin) throws ChatException {
        Room r = rooms.get(roomId);
        Msg m = r == null ? null : findMsg(r, id);
        if (m == null || m.deleted) {
            throw new ChatException(404, "no such message");
        }
        boolean owner = r.group && r.owner.equalsIgnoreCase(actor);
        if (!(m.user.equalsIgnoreCase(actor) || admin || owner)) {
            throw new ChatException(403, "you can only delete your own messages");
        }
        m.deleted = true;
        m.text = "";
        m.reactions.clear();
        r.pins.remove(id);
        dirty = true;
    }

    public synchronized void clearRoom(String roomId) {
        Room r = rooms.get(roomId);
        if (r != null) {
            r.messages.clear();
            r.pins.clear();
            dirty = true;
        }
    }

    // ---- Pins ----

    /** Pins or unpins a message. The caller decides who may pin. At most 3 pins per room. */
    public synchronized void setPinned(String roomId, long id, boolean pinned) throws ChatException {
        Room r = rooms.get(roomId);
        Msg m = r == null ? null : findMsg(r, id);
        if (m == null || m.deleted) {
            throw new ChatException(404, "no such message");
        }
        if (pinned) {
            if (!r.pins.contains(id) && r.pins.size() >= MAX_PINS) {
                throw new ChatException(400, "a room can have at most " + MAX_PINS + " pinned messages - unpin one first");
            }
            r.pins.add(id);
        } else {
            r.pins.remove(id);
        }
        dirty = true;
    }

    public synchronized List<Message> pins(String roomId) {
        List<Message> out = new ArrayList<Message>();
        Room r = rooms.get(roomId);
        if (r == null) {
            return out;
        }
        for (Long id : r.pins) {
            Msg m = findMsg(r, id);
            if (m != null && !m.deleted) {
                out.add(m.view(r));
            }
        }
        return out;
    }

    // ---- Receipts ----

    private Cursor cursor(String roomId, String user) {
        Map<String, Cursor> m = cursors.get(roomId);
        if (m == null) {
            m = new LinkedHashMap<String, Cursor>();
            cursors.put(roomId, m);
        }
        String k = lower(user);
        Cursor c = m.get(k);
        if (c == null) {
            if (m.size() >= MAX_CURSORS_PER_ROOM) {
                return null;
            }
            c = new Cursor(user);
            m.put(k, c);
        }
        return c;
    }

    /** The user's phone has received every message up to and including upTo. */
    public synchronized void markDelivered(String roomId, String user, long upTo) {
        Cursor c = cursor(roomId, user);
        if (c != null && upTo > c.delivered) {
            c.delivered = upTo;
            dirty = true;
        }
    }

    /** The user has read every message up to upTo. Not recorded if the user turned read receipts off. */
    public synchronized void markRead(String roomId, String user, long upTo) {
        Cursor c = cursor(roomId, user);
        if (c == null || upTo <= 0) {
            return;
        }
        if (upTo > c.delivered) {
            c.delivered = upTo;
            dirty = true;
        }
        if (sendsReceipts(lower(user)) && upTo > c.read) {
            c.read = upTo;
            dirty = true;
        }
    }

    /** How many people other than the author got and read a message. Names (up to 30 each) only if asked. */
    public synchronized Receipt receipt(String roomId, long id, boolean withNames) {
        List<String> delivered = new ArrayList<String>();
        List<String> read = new ArrayList<String>();
        int d = 0;
        int rd = 0;
        Room r = rooms.get(roomId);
        Msg m = r == null ? null : findMsg(r, id);
        Map<String, Cursor> map = cursors.get(roomId);
        if (m != null && map != null) {
            String author = lower(m.user);
            for (Map.Entry<String, Cursor> e : map.entrySet()) {
                if (e.getKey().equals(author)) {
                    continue;
                }
                Cursor c = e.getValue();
                if (c.delivered >= id) {
                    d++;
                    if (withNames && delivered.size() < 30) {
                        delivered.add(c.name);
                    }
                }
                if (c.read >= id) {
                    rd++;
                    if (withNames && read.size() < 30) {
                        read.add(c.name);
                    }
                }
            }
        }
        return new Receipt(d, rd, delivered, read);
    }

    // ---- Presence and privacy ----

    public synchronized void setPrefs(String user, boolean showOnline, boolean sendReceipts) {
        prefs.put(lower(user), new boolean[]{showOnline, sendReceipts});
        dirty = true;
    }

    /** {showOnline, sendReceipts}, both true by default. */
    public synchronized boolean[] prefs(String user) {
        boolean[] p = prefs.get(lower(user));
        return p == null ? new boolean[]{true, true} : new boolean[]{p[0], p[1]};
    }

    private boolean showsOnline(String k) {
        boolean[] p = prefs.get(k);
        return p == null || p[0];
    }

    private boolean sendsReceipts(String k) {
        boolean[] p = prefs.get(k);
        return p == null || p[1];
    }

    /** Records that a signed-in user is looking at a room right now. */
    public synchronized void touch(String user, String roomId) {
        String k = lower(user);
        long now = clock.getAsLong();
        lastSeen.put(k, now);
        names.put(k, user);
        Map<String, Long> m = roomSeen.get(roomId);
        if (m == null) {
            m = new LinkedHashMap<String, Long>();
            roomSeen.put(roomId, m);
        }
        m.put(k, now);
    }

    private List<String> onlineKeys(String roomId) {
        List<String> keys = new ArrayList<String>();
        Map<String, Long> m = roomSeen.get(roomId);
        if (m == null) {
            return keys;
        }
        long cutoff = clock.getAsLong() - ONLINE_ROOM_MS;
        Iterator<Map.Entry<String, Long>> it = m.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (e.getValue() < cutoff) {
                it.remove();
            } else if (showsOnline(e.getKey())) {
                keys.add(e.getKey());
            }
        }
        if (m.isEmpty()) {
            roomSeen.remove(roomId);
        }
        return keys;
    }

    public synchronized int onlineCount(String roomId) {
        return onlineKeys(roomId).size();
    }

    public synchronized List<String> onlineUsers(String roomId, int max) {
        List<String> out = new ArrayList<String>();
        for (String k : onlineKeys(roomId)) {
            if (out.size() < max) {
                out.add(names.get(k));
            }
        }
        return out;
    }

    /** True if the user was seen in the last minute and has not hidden their status. */
    public synchronized boolean isOnline(String user) {
        String k = lower(user);
        Long t = lastSeen.get(k);
        return t != null && showsOnline(k) && clock.getAsLong() - t < ONLINE_MS;
    }

    // ---- Typing (not saved) ----

    public synchronized void typing(String roomId, String user) {
        if (isMuted(user)) {
            return;
        }
        String k = lower(user);
        Room tr = rooms.get(roomId);
        if (tr != null && isGroupMuted(tr, k)) {
            return;
        }
        if (limiter.count("t|" + k, 60000) >= 40) {
            return;
        }
        limiter.record("t|" + k);
        Map<String, Long> m = typingMap.get(roomId);
        if (m == null) {
            m = new LinkedHashMap<String, Long>();
            typingMap.put(roomId, m);
        }
        m.put(user, clock.getAsLong() + TYPING_MS);
    }

    /** Up to 5 users who are typing in the room right now, not counting "exclude". */
    public synchronized List<String> typingUsers(String roomId, String exclude) {
        List<String> out = new ArrayList<String>();
        Map<String, Long> m = typingMap.get(roomId);
        if (m == null) {
            return out;
        }
        long now = clock.getAsLong();
        Iterator<Map.Entry<String, Long>> it = m.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (e.getValue() <= now) {
                it.remove();
                continue;
            }
            if (exclude != null && e.getKey().equalsIgnoreCase(exclude)) {
                continue;
            }
            if (out.size() < 5) {
                out.add(e.getKey());
            }
        }
        if (m.isEmpty()) {
            typingMap.remove(roomId);
        }
        return out;
    }

    // ---- Groups ----

    public synchronized RoomInfo createGroup(String rawTitle, String creator, boolean exempt) throws ChatException {
        return createGroup(rawTitle, creator, exempt, false);
    }

    /** Creates a public group, or a private (invite-only) one where the creator is the first member and the owner. */
    public synchronized RoomInfo createGroup(String rawTitle, String creator, boolean exempt, boolean priv) throws ChatException {
        String title = clean(rawTitle);
        if (title.length() < 3 || title.length() > 30) {
            throw new ChatException(400, "group names are 3 to 30 characters");
        }
        String k = lower(creator);
        if (!exempt) {
            int groups = 0;
            int owned = 0;
            for (Room r : rooms.values()) {
                if (r.group) {
                    groups++;
                    if (r.owner.equalsIgnoreCase(creator)) {
                        owned++;
                    }
                }
            }
            if (groups >= MAX_GROUPS) {
                throw new ChatException(400, "there are too many groups already");
            }
            if (owned >= GROUPS_PER_USER) {
                throw new ChatException(400, "you already own " + GROUPS_PER_USER + " groups - delete one first");
            }
            if (limiter.count("g|" + k, DAY) >= GROUP_CREATES_PER_DAY) {
                throw new ChatException(429, "you can create " + GROUP_CREATES_PER_DAY + " groups per day");
            }
        }
        String base = slug(title);
        String id = "g-" + base;
        int n = 2;
        while (rooms.containsKey(id)) {
            id = "g-" + base + "-" + n++;
        }
        Room r = new Room(id, true, title, creator, clock.getAsLong());
        r.priv = priv;
        if (priv) {
            r.members.put(k, new MemberRec(creator, true, clock.getAsLong()));
        }
        rooms.put(id, r);
        if (!exempt) {
            limiter.record("g|" + k);
        }
        dirty = true;
        return infoOf(r, title);
    }

    public synchronized void deleteGroup(String roomId, String actor, boolean admin) throws ChatException {
        Room r = rooms.get(roomId);
        if (r == null || !r.group) {
            throw new ChatException(404, "no such group");
        }
        if (!(admin || r.owner.equalsIgnoreCase(actor))) {
            throw new ChatException(403, "only the creator or the admin can delete a group");
        }
        rooms.remove(roomId);
        typingMap.remove(roomId);
        cursors.remove(roomId);
        roomSeen.remove(roomId);
        Iterator<InviteRec> it = invites.values().iterator();
        while (it.hasNext()) {
            if (it.next().room.equals(roomId)) {
                it.remove();
            }
        }
        for (Map<String, InvitationRec> m : invitations.values()) {
            m.remove(roomId);
        }
        dirty = true;
    }

    // ---- Membership of private groups ----

    private Room privateGroup(String roomId) throws ChatException {
        Room r = rooms.get(roomId);
        if (r == null || !r.group || !r.priv) {
            throw new ChatException(404, "no such group");
        }
        return r;
    }

    /** The members, owner first. */
    public synchronized List<Member> members(String roomId) {
        List<Member> out = new ArrayList<Member>();
        Room r = rooms.get(roomId);
        if (r == null || !r.priv) {
            return out;
        }
        for (MemberRec m : r.members.values()) {
            if (m.owner) {
                out.add(new Member(m.name, true, m.joined));
            }
        }
        for (MemberRec m : r.members.values()) {
            if (!m.owner) {
                out.add(new Member(m.name, false, m.joined));
            }
        }
        return out;
    }

    private void addMemberInternal(Room r, String user) throws ChatException {
        if (r.banned.contains(lower(user))) {
            throw new ChatException(403, "you were removed from this group by its admin");
        }
        if (r.members.size() >= MAX_MEMBERS) {
            throw new ChatException(400, "this group is full (" + MAX_MEMBERS + " members)");
        }
        String k = lower(user);
        r.members.put(k, new MemberRec(user, false, clock.getAsLong()));
        Map<String, InvitationRec> pending = invitations.get(k);
        if (pending != null) {
            pending.remove(r.id);
        }
        dirty = true;
    }

    /** The owner (or the admin) removes a member. The owner cannot be removed. */
    public synchronized void removeMember(String roomId, String target, String actor, boolean admin) throws ChatException {
        Room r = privateGroup(roomId);
        if (!(admin || r.owner.equalsIgnoreCase(actor))) {
            throw new ChatException(403, "only the group owner can remove people");
        }
        String tk = lower(target);
        MemberRec m = r.members.get(tk);
        if (m == null) {
            throw new ChatException(404, "that person is not in this group");
        }
        if (m.owner) {
            throw new ChatException(400, "the owner cannot be removed");
        }
        r.members.remove(tk);
        r.banned.add(tk);
        dirty = true;
    }

    /** A member leaves. The owner cannot leave; the owner can delete the group instead. */
    public synchronized void leave(String roomId, String user) throws ChatException {
        Room r = privateGroup(roomId);
        String k = lower(user);
        MemberRec m = r.members.get(k);
        if (m == null) {
            throw new ChatException(404, "you are not in this group");
        }
        if (m.owner) {
            throw new ChatException(400, "the owner cannot leave - delete the group instead");
        }
        r.members.remove(k);
        dirty = true;
    }

    /** True if the two people are both members of at least one private group. Public rooms do not count. */
    public synchronized boolean sharesPrivateGroup(String a, String b) {
        return !commonPrivateGroups(a, b).isEmpty();
    }

    public synchronized List<String> commonPrivateGroups(String a, String b) {
        List<String> out = new ArrayList<String>();
        if (a == null || b == null) {
            return out;
        }
        String ka = lower(a);
        String kb = lower(b);
        for (Room r : rooms.values()) {
            if (r.group && r.priv && r.members.containsKey(ka) && r.members.containsKey(kb)) {
                out.add(r.id);
            }
        }
        return out;
    }

    /** Removes a deleted account from every group and drops its pending invitations. */
    public synchronized void removeUserEverywhere(String user) {
        String k = lower(user);
        for (Room r : rooms.values()) {
            MemberRec m = r.members.get(k);
            if (m != null && !m.owner) {
                r.members.remove(k);
                dirty = true;
            }
        }
        mail.remove(k);
        mailNames.remove(k);
        if (invitations.remove(k) != null) {
            dirty = true;
        }
    }

    // ---- Invite codes ----

    private static String normalizeCode(String raw) {
        return raw == null ? "" : raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static String formatCode(String code) {
        return code.substring(0, 5) + "-" + code.substring(5);
    }

    private static String newCode() {
        StringBuilder sb = new StringBuilder(CODE_LEN);
        for (int i = 0; i < CODE_LEN; i++) {
            sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    private InviteRec validInvite(String raw) {
        String code = normalizeCode(raw);
        if (code.length() != CODE_LEN) {
            return null;
        }
        InviteRec rec = invites.get(code);
        if (rec == null) {
            return null;
        }
        if (rec.expires <= clock.getAsLong() || rec.uses >= rec.maxUses || !rooms.containsKey(rec.room)) {
            invites.remove(code);
            dirty = true;
            return null;
        }
        return rec;
    }

    private void purgeInvites() {
        Iterator<Map.Entry<String, InviteRec>> it = invites.entrySet().iterator();
        long now = clock.getAsLong();
        while (it.hasNext()) {
            InviteRec r = it.next().getValue();
            if (r.expires <= now || r.uses >= r.maxUses || !rooms.containsKey(r.room)) {
                it.remove();
                dirty = true;
            }
        }
    }

    private Invite view(InviteRec r) {
        return new Invite(formatCode(r.code), r.room, r.by, r.created, r.expires, r.maxUses, r.uses);
    }

    /** The owner makes an invite code: maxUses 1 to 50, valid for 1 to 30 days. */
    public synchronized Invite createInvite(String roomId, String actor, int maxUses, int days) throws ChatException {
        Room r = privateGroup(roomId);
        if (!r.owner.equalsIgnoreCase(actor)) {
            throw new ChatException(403, "only the group owner can invite people");
        }
        purgeInvites();
        int active = 0;
        for (InviteRec i : invites.values()) {
            if (i.room.equals(roomId)) {
                active++;
            }
        }
        if (active >= MAX_ACTIVE_INVITES) {
            throw new ChatException(400, "too many active invites - revoke some first");
        }
        int uses = Math.max(1, Math.min(50, maxUses));
        int d = Math.max(1, Math.min(30, days));
        String code = newCode();
        while (invites.containsKey(code)) {
            code = newCode();
        }
        long now = clock.getAsLong();
        InviteRec rec = new InviteRec(code, roomId, actor, now, now + d * DAY, uses);
        invites.put(code, rec);
        dirty = true;
        return view(rec);
    }

    public synchronized List<Invite> listInvites(String roomId, String actor) throws ChatException {
        Room r = privateGroup(roomId);
        if (!r.owner.equalsIgnoreCase(actor)) {
            throw new ChatException(403, "only the group owner can see the invites");
        }
        purgeInvites();
        List<Invite> out = new ArrayList<Invite>();
        for (InviteRec i : invites.values()) {
            if (i.room.equals(roomId)) {
                out.add(view(i));
            }
        }
        return out;
    }

    public synchronized void revokeInvite(String roomId, String rawCode, String actor) throws ChatException {
        Room r = privateGroup(roomId);
        if (!r.owner.equalsIgnoreCase(actor)) {
            throw new ChatException(403, "only the group owner can revoke invites");
        }
        String code = normalizeCode(rawCode);
        InviteRec rec = invites.get(code);
        if (rec == null || !rec.room.equals(roomId)) {
            throw new ChatException(404, "no such invite");
        }
        invites.remove(code);
        dirty = true;
    }

    /** What a code points to, or null if it is not valid (unknown, expired, used up or revoked). */
    public synchronized InvitePreview previewInvite(String rawCode) {
        InviteRec rec = validInvite(rawCode);
        if (rec == null) {
            return null;
        }
        Room r = rooms.get(rec.room);
        return new InvitePreview(formatCode(rec.code), r.title, r.owner, r.members.size(), rec.expires);
    }

    /** Joins the group the code points to. Returns the room id. An existing member keeps the code unused. */
    public synchronized String joinByCode(String rawCode, String user) throws ChatException {
        InviteRec rec = validInvite(rawCode);
        if (rec == null) {
            throw new ChatException(404, "this invite is not valid any more");
        }
        Room r = rooms.get(rec.room);
        if (r.members.containsKey(lower(user))) {
            return r.id;
        }
        addMemberInternal(r, user);
        rec.uses++;
        if (rec.uses >= rec.maxUses) {
            invites.remove(rec.code);
        }
        dirty = true;
        return r.id;
    }

    // ---- Invitations to a named user ----

    /** The owner invites a user by name. The user sees it in their list and accepts or declines. */
    public synchronized void inviteUser(String roomId, String actor, String targetName) throws ChatException {
        Room r = privateGroup(roomId);
        if (!r.owner.equalsIgnoreCase(actor)) {
            throw new ChatException(403, "only the group owner can invite people");
        }
        String tk = lower(targetName);
        if (r.members.containsKey(tk)) {
            throw new ChatException(400, "that person is already in this group");
        }
        String ak = lower(actor);
        if (limiter.count("iv|" + ak, DAY) >= INVITES_PER_DAY) {
            throw new ChatException(429, "you can send " + INVITES_PER_DAY + " invitations per day");
        }
        int pending = 0;
        for (Map<String, InvitationRec> m : invitations.values()) {
            if (m.containsKey(roomId)) {
                pending++;
            }
        }
        Map<String, InvitationRec> mine = invitations.get(tk);
        if (mine != null && mine.containsKey(roomId)) {
            return;
        }
        if (pending >= MAX_PENDING_PER_ROOM) {
            throw new ChatException(400, "too many pending invitations for this group");
        }
        if (mine == null) {
            mine = new LinkedHashMap<String, InvitationRec>();
            invitations.put(tk, mine);
        }
        r.banned.remove(tk);
        mine.put(roomId, new InvitationRec(roomId, targetName, actor, clock.getAsLong()));
        limiter.record("iv|" + ak);
        dirty = true;
    }

    public synchronized List<Invitation> invitationsFor(String user) {
        List<Invitation> out = new ArrayList<Invitation>();
        Map<String, InvitationRec> m = invitations.get(lower(user));
        if (m == null) {
            return out;
        }
        long cutoff = clock.getAsLong() - INVITATION_TTL_MS;
        Iterator<Map.Entry<String, InvitationRec>> it = m.entrySet().iterator();
        while (it.hasNext()) {
            InvitationRec rec = it.next().getValue();
            Room r = rooms.get(rec.room);
            if (r == null || !r.priv || rec.time < cutoff) {
                it.remove();
                dirty = true;
                continue;
            }
            out.add(new Invitation(rec.room, r.title, rec.from, rec.time));
        }
        return out;
    }

    public synchronized void acceptInvitation(String roomId, String user) throws ChatException {
        Map<String, InvitationRec> m = invitations.get(lower(user));
        InvitationRec rec = m == null ? null : m.get(roomId);
        Room r = rooms.get(roomId);
        if (rec == null || r == null || !r.priv) {
            throw new ChatException(404, "no such invitation");
        }
        if (!r.members.containsKey(lower(user))) {
            addMemberInternal(r, user);
        }
        m.remove(roomId);
        dirty = true;
    }

    public synchronized void declineInvitation(String roomId, String user) throws ChatException {
        Map<String, InvitationRec> m = invitations.get(lower(user));
        if (m == null || m.remove(roomId) == null) {
            throw new ChatException(404, "no such invitation");
        }
        dirty = true;
    }

    // ---- Group admin tools: rename, mute inside a group, private mail from the super-admin ----

    /** The group's creator (or the super-admin) renames it. The room id stays the same. */
    public synchronized void renameGroup(String roomId, String rawTitle, String actor, boolean superAdmin) throws ChatException {
        Room r = rooms.get(roomId);
        if (r == null || !r.group) {
            throw new ChatException(404, "no such group");
        }
        if (!(superAdmin || r.owner.equalsIgnoreCase(actor))) {
            throw new ChatException(403, "only the group admin can rename the group");
        }
        String title = clean(rawTitle);
        if (title.length() < 3 || title.length() > 30) {
            throw new ChatException(400, "group names are 3 to 30 characters");
        }
        if (!superAdmin) {
            String k = lower(actor);
            if (limiter.count("rn|" + k, DAY) >= 10) {
                throw new ChatException(429, "you can rename groups 10 times a day");
            }
            limiter.record("rn|" + k);
        }
        if (!title.equals(r.title)) {
            r.title = title;
            dirty = true;
        }
    }

    private boolean isGroupMuted(Room r, String key) {
        Long until = r.groupMutes.get(key);
        if (until == null) {
            return false;
        }
        if (until != 0 && until <= clock.getAsLong()) {
            r.groupMutes.remove(key);
            dirty = true;
            return false;
        }
        return true;
    }

    private void checkGroupMute(Room r, String user) throws ChatException {
        if (isGroupMuted(r, lower(user))) {
            throw new ChatException(403, "you are muted in this group by its admin");
        }
    }

    /** The group admin mutes a person inside this group only. hours = 0 means until unmuted. */
    public synchronized void muteInGroup(String roomId, String target, String actor, long hours) throws ChatException {
        Room r = rooms.get(roomId);
        if (r == null || !r.group) {
            throw new ChatException(404, "no such group");
        }
        if (!r.owner.equalsIgnoreCase(actor)) {
            throw new ChatException(403, "only the group admin can mute people in this group");
        }
        if (target.equalsIgnoreCase(r.owner)) {
            throw new ChatException(400, "the group admin cannot be muted");
        }
        String tk = lower(target);
        if (r.priv && !r.members.containsKey(tk)) {
            throw new ChatException(404, "that person is not in this group");
        }
        if (r.groupMutes.size() >= 200 && !r.groupMutes.containsKey(tk)) {
            throw new ChatException(400, "too many muted people in this group");
        }
        r.groupMutes.put(tk, hours <= 0 ? 0L : clock.getAsLong() + hours * 3600000L);
        dirty = true;
    }

    public synchronized void unmuteInGroup(String roomId, String target, String actor) throws ChatException {
        Room r = rooms.get(roomId);
        if (r == null || !r.group) {
            throw new ChatException(404, "no such group");
        }
        if (!r.owner.equalsIgnoreCase(actor)) {
            throw new ChatException(403, "only the group admin can unmute people in this group");
        }
        if (r.groupMutes.remove(lower(target)) == null) {
            throw new ChatException(404, "that person is not muted in this group");
        }
        dirty = true;
    }

    /** The people muted in this group (lower case) with the time the mute ends, or 0 for until unmuted. */
    public synchronized Map<String, Long> groupMutedList(String roomId, String actor) throws ChatException {
        Room r = rooms.get(roomId);
        if (r == null || !r.group) {
            throw new ChatException(404, "no such group");
        }
        if (!r.owner.equalsIgnoreCase(actor)) {
            throw new ChatException(403, "only the group admin can see who is muted");
        }
        for (String k : new ArrayList<String>(r.groupMutes.keySet())) {
            isGroupMuted(r, k);
        }
        return new LinkedHashMap<String, Long>(r.groupMutes);
    }

    /** True if the person owns at least one group. */
    public synchronized boolean isGroupAdmin(String user) {
        for (Room r : rooms.values()) {
            if (r.group && r.owner.equalsIgnoreCase(user)) {
                return true;
            }
        }
        return false;
    }

    /** Everyone who owns a group, with their groups. The caller leaves out the super-admin. */
    public synchronized List<GroupAdminInfo> groupAdmins() {
        Map<String, List<RoomInfo>> byOwner = new LinkedHashMap<String, List<RoomInfo>>();
        Map<String, String> display = new LinkedHashMap<String, String>();
        for (Room r : rooms.values()) {
            if (!r.group) {
                continue;
            }
            String k = lower(r.owner);
            List<RoomInfo> l = byOwner.get(k);
            if (l == null) {
                l = new ArrayList<RoomInfo>();
                byOwner.put(k, l);
                display.put(k, r.owner);
            }
            l.add(infoOf(r, r.title));
        }
        List<GroupAdminInfo> out = new ArrayList<GroupAdminInfo>();
        for (Map.Entry<String, List<RoomInfo>> e : byOwner.entrySet()) {
            out.add(new GroupAdminInfo(display.get(e.getKey()), e.getValue()));
        }
        return out;
    }

    private Mail mailView(MailRec m) {
        return new Mail(m.id, m.fromAdmin, m.time, m.text, m.fromAdmin ? m.readByUser : m.readByAdmin);
    }

    private static String cleanMail(String raw) throws ChatException {
        String text = clean(raw);
        if (text.isEmpty()) {
            throw new ChatException(400, "write a message first");
        }
        if (text.length() > MAX_TEXT) {
            throw new ChatException(400, "messages can be at most " + MAX_TEXT + " characters");
        }
        return text;
    }

    /** The super-admin writes to a group admin. The caller checks that the person is a group admin. */
    public synchronized Mail mailFromAdmin(String user, String rawText) throws ChatException {
        String text = cleanMail(rawText);
        String k = lower(user);
        List<MailRec> list = mail.get(k);
        if (list == null) {
            list = new ArrayList<MailRec>();
            mail.put(k, list);
        }
        mailNames.put(k, user);
        MailRec m = new MailRec(nextId++, true, clock.getAsLong(), text);
        m.readByAdmin = true;
        list.add(m);
        while (list.size() > 200) {
            list.remove(0);
        }
        dirty = true;
        return mailView(m);
    }

    /** A group admin replies. Only possible once the super-admin has written to them. */
    public synchronized Mail mailFromUser(String user, String rawText) throws ChatException {
        String text = cleanMail(rawText);
        String k = lower(user);
        List<MailRec> list = mail.get(k);
        boolean hasAdminMessage = false;
        if (list != null) {
            for (MailRec x : list) {
                if (x.fromAdmin) {
                    hasAdminMessage = true;
                    break;
                }
            }
        }
        if (!hasAdminMessage) {
            throw new ChatException(403, "you can only reply to a message from the super-admin");
        }
        if (limiter.count("ml|" + k, 60000) >= 10) {
            throw new ChatException(429, "you are sending too fast - wait a moment");
        }
        MailRec m = new MailRec(nextId++, false, clock.getAsLong(), text);
        m.readByUser = true;
        list.add(m);
        while (list.size() > 200) {
            list.remove(0);
        }
        limiter.record("ml|" + k);
        dirty = true;
        return mailView(m);
    }

    public synchronized List<Mail> mailThread(String user, boolean asAdmin) {
        List<Mail> out = new ArrayList<Mail>();
        List<MailRec> list = mail.get(lower(user));
        if (list != null) {
            for (MailRec m : list) {
                out.add(mailView(m));
            }
        }
        return out;
    }

    /** Messages from the other side that the viewer has not read yet. */
    public synchronized int mailUnread(String user, boolean asAdmin) {
        List<MailRec> list = mail.get(lower(user));
        int n = 0;
        if (list != null) {
            for (MailRec m : list) {
                if (asAdmin ? (!m.fromAdmin && !m.readByAdmin) : (m.fromAdmin && !m.readByUser)) {
                    n++;
                }
            }
        }
        return n;
    }

    public synchronized void markMailRead(String user, boolean asAdmin) {
        List<MailRec> list = mail.get(lower(user));
        if (list == null) {
            return;
        }
        for (MailRec m : list) {
            if (asAdmin && !m.fromAdmin && !m.readByAdmin) {
                m.readByAdmin = true;
                dirty = true;
            } else if (!asAdmin && m.fromAdmin && !m.readByUser) {
                m.readByUser = true;
                dirty = true;
            }
        }
    }

    // ---- Suspending, banning and reinstating people in a private group ----

    /** The group admin suspends a member. hours = 0 means until reinstated. Private groups only. */
    public synchronized void suspendMember(String roomId, String target, String actor, long hours) throws ChatException {
        Room r = privateGroup(roomId);
        if (!r.owner.equalsIgnoreCase(actor)) {
            throw new ChatException(403, "only the group admin can suspend people");
        }
        String tk = lower(target);
        MemberRec m = r.members.get(tk);
        if (m == null) {
            throw new ChatException(404, "that person is not in this group");
        }
        if (m.owner) {
            throw new ChatException(400, "the group admin cannot be suspended");
        }
        r.members.remove(tk);
        r.banned.add(tk);
        r.suspendedUntil.put(tk, hours <= 0 ? 0L : clock.getAsLong() + hours * 3600000L);
        dirty = true;
    }

    /** People the group admin removed or suspended. */
    public synchronized List<BanInfo> bannedList(String roomId, String actor) throws ChatException {
        Room r = privateGroup(roomId);
        if (!r.owner.equalsIgnoreCase(actor)) {
            throw new ChatException(403, "only the group admin can see who is banned");
        }
        List<BanInfo> out = new ArrayList<BanInfo>();
        for (String k : r.banned) {
            Long until = r.suspendedUntil.get(k);
            out.add(new BanInfo(k, until != null, until == null ? 0L : until));
        }
        return out;
    }

    /** Lets a removed or suspended person back in without a new invite. */
    public synchronized void reinstate(String roomId, String target, String actor) throws ChatException {
        Room r = privateGroup(roomId);
        if (!r.owner.equalsIgnoreCase(actor)) {
            throw new ChatException(403, "only the group admin can reinstate people");
        }
        String tk = lower(target);
        if (!r.banned.contains(tk)) {
            throw new ChatException(404, "that person is not removed or suspended");
        }
        if (r.members.size() >= MAX_MEMBERS) {
            throw new ChatException(400, "this group is full (" + MAX_MEMBERS + " members)");
        }
        r.banned.remove(tk);
        r.suspendedUntil.remove(tk);
        r.members.put(tk, new MemberRec(target, false, clock.getAsLong()));
        dirty = true;
    }

    /** Lifts group suspensions whose time is up and puts those people back in. Returns how many. */
    public synchronized int tickGroups(java.util.function.Function<String, String> display) {
        long now = clock.getAsLong();
        int n = 0;
        for (Room r : rooms.values()) {
            if (!r.priv) {
                continue;
            }
            Iterator<Map.Entry<String, Long>> it = r.suspendedUntil.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Long> e = it.next();
                if (e.getValue() != 0 && e.getValue() <= now) {
                    String k = e.getKey();
                    it.remove();
                    r.banned.remove(k);
                    String shown = display == null ? null : display.apply(k);
                    if (shown != null && r.members.size() < MAX_MEMBERS) {
                        r.members.put(k, new MemberRec(shown, false, now));
                    }
                    n++;
                    dirty = true;
                }
            }
        }
        return n;
    }

    // ---- What a report captures ----

    /** The message and up to "before" earlier visible messages, oldest first. */
    public synchronized List<Message> context(String roomId, long id, int before) {
        List<Message> out = new ArrayList<Message>();
        Room r = rooms.get(roomId);
        if (r == null) {
            return out;
        }
        int idx = -1;
        for (int i = 0; i < r.messages.size(); i++) {
            if (r.messages.get(i).id == id) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            return out;
        }
        out.add(r.messages.get(idx).view(r));
        int taken = 0;
        for (int i = idx - 1; i >= 0 && taken < before; i--) {
            Msg m = r.messages.get(i);
            if (!m.deleted) {
                out.add(m.view(r));
                taken++;
            }
        }
        Collections.reverse(out);
        return out;
    }

    /** A person's latest visible messages in one room, oldest first. */
    public synchronized List<Message> recentBy(String roomId, String user, int limit) {
        List<Message> out = new ArrayList<Message>();
        Room r = rooms.get(roomId);
        if (r == null) {
            return out;
        }
        for (int i = r.messages.size() - 1; i >= 0 && out.size() < limit; i--) {
            Msg m = r.messages.get(i);
            if (!m.deleted && m.user.equalsIgnoreCase(user)) {
                out.add(m.view(r));
            }
        }
        Collections.reverse(out);
        return out;
    }

    /** Deletes every group a person owns (used when an account is deleted). Returns how many. */
    public synchronized int deleteGroupsOwnedBy(String owner) {
        int n = 0;
        Iterator<Room> it = rooms.values().iterator();
        while (it.hasNext()) {
            Room r = it.next();
            if (r.group && r.owner.equalsIgnoreCase(owner)) {
                it.remove();
                typingMap.remove(r.id);
                cursors.remove(r.id);
                roomSeen.remove(r.id);
                Iterator<InviteRec> iv = invites.values().iterator();
                while (iv.hasNext()) {
                    if (iv.next().room.equals(r.id)) {
                        iv.remove();
                    }
                }
                for (Map<String, InvitationRec> m : invitations.values()) {
                    m.remove(r.id);
                }
                n++;
                dirty = true;
            }
        }
        return n;
    }

    // ---- Muting ----

    /** Mutes a user. hours = 0 means until unmuted. */
    public synchronized void mute(String user, long hours) {
        muted.put(lower(user), hours <= 0 ? 0L : clock.getAsLong() + hours * 3600000L);
        dirty = true;
    }

    public synchronized boolean unmute(String user) {
        boolean was = muted.remove(lower(user)) != null;
        if (was) {
            dirty = true;
        }
        return was;
    }

    public synchronized boolean isMuted(String user) {
        String k = lower(user);
        Long until = muted.get(k);
        if (until == null) {
            return false;
        }
        if (until != 0 && until <= clock.getAsLong()) {
            muted.remove(k);
            dirty = true;
            return false;
        }
        return true;
    }

    /** Muted users (lower case) with the time the mute ends, or 0 for until unmuted. */
    public synchronized Map<String, Long> mutedList() {
        for (String k : new ArrayList<String>(muted.keySet())) {
            isMuted(k);
        }
        return new LinkedHashMap<String, Long>(muted);
    }

    // ---- Helpers ----

    private static Msg findMsg(Room r, long id) {
        for (Msg m : r.messages) {
            if (m.id == id) {
                return m;
            }
        }
        return null;
    }

    private RoomInfo infoOf(Room r, String title) {
        long cutoff = clock.getAsLong() - DAY;
        Msg last = null;
        int recent = 0;
        int total = 0;
        for (Msg m : r.messages) {
            if (!m.deleted) {
                total++;
                last = m;
                if (m.time >= cutoff) {
                    recent++;
                }
            }
        }
        long lastId = r.messages.isEmpty() ? 0 : r.messages.get(r.messages.size() - 1).id;
        return new RoomInfo(r.id, r.group, title, r.owner, lastId,
                last == null ? "" : last.user, last == null ? "" : excerpt(last.text, 80),
                last == null ? 0 : last.time, recent, total, r.priv, r.members.size());
    }

    /** At most max characters; a longer text is cut and ends with an ellipsis. */
    public static String excerpt(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max - 1) + "\u2026";
    }

    /** One line: control characters become spaces. */
    static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            sb.append((ch < 0x20 || ch == 0x7f) ? ' ' : ch);
        }
        return sb.toString().trim();
    }

    private static String slug(String title) {
        String s = lower(title).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.length() > 24) {
            s = s.substring(0, 24).replaceAll("-+$", "");
        }
        return s.isEmpty() ? "group" : s;
    }

    // ---- Storage ----

    private void load() throws IOException {
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] p = line.split("\t", -1);
            try {
                switch (p[0]) {
                    case "I":
                        nextId = Math.max(nextId, Long.parseLong(p[1]));
                        break;
                    case "G": {
                        Room g = new Room(p[1], true, p[2], p[3], Long.parseLong(p[4]));
                        g.priv = p.length > 5 && "1".equals(p[5]);
                        rooms.put(p[1], g);
                        break;
                    }
                    case "U":
                        muted.put(p[1], Long.parseLong(p[2]));
                        break;
                    case "F":
                        prefs.put(p[1], new boolean[]{"1".equals(p[2]), "1".equals(p[3])});
                        break;
                    case "M": {
                        String roomId = p[2];
                        Room r = rooms.get(roomId);
                        if (r == null && !isGroupId(roomId)) {
                            r = new Room(roomId, false, roomId, "", 0);
                            rooms.put(roomId, r);
                        }
                        if (r != null) {
                            Msg m = new Msg(Long.parseLong(p[1]), roomId, p[3], Long.parseLong(p[4]),
                                    Long.parseLong(p[5]), p.length > 7 ? p[7] : "");
                            m.deleted = "1".equals(p[6]);
                            m.edited = p.length > 8 && "1".equals(p[8]);
                            m.fwdFrom = p.length > 9 ? p[9] : "";
                            r.messages.add(m);
                            nextId = Math.max(nextId, m.id + 1);
                        }
                        break;
                    }
                    case "R": {
                        Room r = rooms.get(p[1]);
                        Msg m = r == null ? null : findMsg(r, Long.parseLong(p[2]));
                        if (m != null && !m.deleted && REACTIONS.contains(p[3])) {
                            LinkedHashSet<String> set = m.reactions.get(p[3]);
                            if (set == null) {
                                set = new LinkedHashSet<String>();
                                m.reactions.put(p[3], set);
                            }
                            set.add(p[4]);
                        }
                        break;
                    }
                    case "P": {
                        Room r = rooms.get(p[1]);
                        if (r != null) {
                            r.pins.add(Long.parseLong(p[2]));
                        }
                        break;
                    }
                    case "C": {
                        Map<String, Cursor> m = cursors.get(p[1]);
                        if (m == null) {
                            m = new LinkedHashMap<String, Cursor>();
                            cursors.put(p[1], m);
                        }
                        Cursor c = new Cursor(p.length > 5 ? p[5] : p[2]);
                        c.delivered = Long.parseLong(p[3]);
                        c.read = Long.parseLong(p[4]);
                        m.put(p[2], c);
                        break;
                    }
                    case "B": {
                        Room r = rooms.get(p[1]);
                        if (r != null) {
                            r.members.put(p[2], new MemberRec(p[3], "1".equals(p[4]), Long.parseLong(p[5])));
                        }
                        break;
                    }
                    case "X": {
                        Room r = rooms.get(p[1]);
                        if (r != null) {
                            r.banned.add(p[2]);
                        }
                        break;
                    }
                    case "Y": {
                        Room r = rooms.get(p[1]);
                        if (r != null) {
                            r.groupMutes.put(p[2], Long.parseLong(p[3]));
                        }
                        break;
                    }
                    case "Z": {
                        String key = p[1];
                        MailRec rec = new MailRec(Long.parseLong(p[3]), "1".equals(p[4]), Long.parseLong(p[5]),
                                p.length > 8 ? p[8] : "");
                        rec.readByAdmin = "1".equals(p[6]);
                        rec.readByUser = "1".equals(p[7]);
                        List<MailRec> list = mail.get(key);
                        if (list == null) {
                            list = new ArrayList<MailRec>();
                            mail.put(key, list);
                        }
                        list.add(rec);
                        mailNames.put(key, p[2]);
                        nextId = Math.max(nextId, rec.id + 1);
                        break;
                    }
                    case "S": {
                        Room r = rooms.get(p[1]);
                        if (r != null) {
                            r.suspendedUntil.put(p[2], Long.parseLong(p[3]));
                        }
                        break;
                    }
                    case "V": {
                        InviteRec v = new InviteRec(p[1], p[2], p[3], Long.parseLong(p[4]),
                                Long.parseLong(p[5]), Integer.parseInt(p[6]));
                        v.uses = Integer.parseInt(p[7]);
                        invites.put(v.code, v);
                        break;
                    }
                    case "W": {
                        Map<String, InvitationRec> m = invitations.get(p[2]);
                        if (m == null) {
                            m = new LinkedHashMap<String, InvitationRec>();
                            invitations.put(p[2], m);
                        }
                        m.put(p[1], new InvitationRec(p[1], p[3], p[4], Long.parseLong(p[5])));
                        break;
                    }
                    default:
                        break;
                }
            } catch (RuntimeException e) {
                // skip a damaged line
            }
        }
    }

    public synchronized void flushIfDirty() {
        if (dirty) {
            try {
                save();
            } catch (IOException e) {
                System.out.println("[chat] could not save: " + e.getMessage());
            }
        }
    }

    private void save() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# VStream chat - keep private\n");
        sb.append("I\t").append(nextId).append('\n');
        for (Room r : rooms.values()) {
            if (r.group) {
                sb.append("G\t").append(r.id).append('\t').append(r.title).append('\t')
                        .append(r.owner).append('\t').append(r.created).append('\t')
                        .append(r.priv ? "1" : "0").append('\n');
            }
        }
        for (Map.Entry<String, Long> e : muted.entrySet()) {
            sb.append("U\t").append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        }
        for (Map.Entry<String, boolean[]> e : prefs.entrySet()) {
            sb.append("F\t").append(e.getKey()).append('\t').append(e.getValue()[0] ? "1" : "0")
                    .append('\t').append(e.getValue()[1] ? "1" : "0").append('\n');
        }
        for (Room r : rooms.values()) {
            for (Msg m : r.messages) {
                sb.append("M\t").append(m.id).append('\t').append(r.id).append('\t').append(m.user)
                        .append('\t').append(m.time).append('\t').append(m.replyTo).append('\t')
                        .append(m.deleted ? "1" : "0").append('\t').append(m.deleted ? "" : m.text)
                        .append('\t').append(m.edited ? "1" : "0").append('\t')
                        .append(m.deleted ? "" : m.fwdFrom).append('\n');
            }
        }
        for (Room r : rooms.values()) {
            for (Msg m : r.messages) {
                if (m.deleted) {
                    continue;
                }
                for (Map.Entry<String, LinkedHashSet<String>> e : m.reactions.entrySet()) {
                    for (String who : e.getValue()) {
                        sb.append("R\t").append(r.id).append('\t').append(m.id).append('\t')
                                .append(e.getKey()).append('\t').append(who).append('\n');
                    }
                }
            }
            for (Long pinned : r.pins) {
                sb.append("P\t").append(r.id).append('\t').append(pinned).append('\n');
            }
        }
        for (Map.Entry<String, Map<String, Cursor>> room : cursors.entrySet()) {
            for (Map.Entry<String, Cursor> e : room.getValue().entrySet()) {
                sb.append("C\t").append(room.getKey()).append('\t').append(e.getKey()).append('\t')
                        .append(e.getValue().delivered).append('\t').append(e.getValue().read).append('\t')
                        .append(e.getValue().name).append('\n');
            }
        }
        for (Room r : rooms.values()) {
            for (Map.Entry<String, MemberRec> e : r.members.entrySet()) {
                sb.append("B\t").append(r.id).append('\t').append(e.getKey()).append('\t')
                        .append(e.getValue().name).append('\t').append(e.getValue().owner ? "1" : "0")
                        .append('\t').append(e.getValue().joined).append('\n');
            }
        }
        for (InviteRec v : invites.values()) {
            sb.append("V\t").append(v.code).append('\t').append(v.room).append('\t').append(v.by).append('\t')
                    .append(v.created).append('\t').append(v.expires).append('\t').append(v.maxUses).append('\t')
                    .append(v.uses).append('\n');
        }
        for (Map.Entry<String, Map<String, InvitationRec>> e : invitations.entrySet()) {
            for (InvitationRec rec : e.getValue().values()) {
                sb.append("W\t").append(rec.room).append('\t').append(e.getKey()).append('\t')
                        .append(rec.toName).append('\t').append(rec.from).append('\t').append(rec.time).append('\n');
            }
        }
        for (Room r : rooms.values()) {
            for (String b : r.banned) {
                sb.append("X\t").append(r.id).append('\t').append(b).append('\n');
            }
            for (Map.Entry<String, Long> e : r.groupMutes.entrySet()) {
                sb.append("Y\t").append(r.id).append('\t').append(e.getKey()).append('\t')
                        .append(e.getValue()).append('\n');
            }
        }
        for (Map.Entry<String, List<MailRec>> e : mail.entrySet()) {
            for (MailRec m : e.getValue()) {
                sb.append("Z\t").append(e.getKey()).append('\t').append(mailNames.get(e.getKey())).append('\t')
                        .append(m.id).append('\t').append(m.fromAdmin ? "1" : "0").append('\t').append(m.time)
                        .append('\t').append(m.readByAdmin ? "1" : "0").append('\t')
                        .append(m.readByUser ? "1" : "0").append('\t').append(m.text).append('\n');
            }
        }
        for (Room r : rooms.values()) {
            for (Map.Entry<String, Long> e : r.suspendedUntil.entrySet()) {
                sb.append("S\t").append(r.id).append('\t').append(e.getKey()).append('\t')
                        .append(e.getValue()).append('\n');
            }
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, sb.toString().getBytes(StandardCharsets.UTF_8));
        try {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            // not a POSIX file system (for example Windows)
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        dirty = false;
    }
}
package com.vstream.Vidiserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/**
 * Reports, account suspensions, account deletion and the moderation log. A report captures the reported
 * message and a few before it, so the super-admin sees only that. Suspending an account uses the same
 * Disable logic as the admin page (a broadcaster's channel is removed and its publisher kicked), releases
 * a claimed pool channel and signs the person out; timed suspensions lift themselves.
 */
public final class ModerationService {

    public static final List<String> REASONS = Collections.unmodifiableList(
            Arrays.asList("spam", "abuse", "harassment", "illegal", "other"));

    private static final long HOUR = 3600000L;
    private static final long DAY = 24L * HOUR;
    private static final int MAX_REPORTS = 1000;
    private static final int MAX_AUDIT = 500;

    /** One line of what was reported. */
    public static final class Line {
        public final long id;
        public final String user;
        public final long time;
        public final String text;

        Line(long id, String user, long time, String text) {
            this.id = id;
            this.user = user;
            this.time = time;
            this.text = text;
        }
    }

    public static final class Report {
        public final long id;
        public final long time;
        public final String reporter;
        public final String type;
        public final String room;
        public final String target;
        public final long messageId;
        public final String reason;
        public final String note;
        public final String status;
        public final String actions;
        public final String handledBy;
        public final long handledTime;
        public final List<Line> context;

        Report(long id, long time, String reporter, String type, String room, String target, long messageId,
               String reason, String note, String status, String actions, String handledBy, long handledTime,
               List<Line> context) {
            this.id = id;
            this.time = time;
            this.reporter = reporter;
            this.type = type;
            this.room = room;
            this.target = target;
            this.messageId = messageId;
            this.reason = reason;
            this.note = note;
            this.status = status;
            this.actions = actions;
            this.handledBy = handledBy;
            this.handledTime = handledTime;
            this.context = context;
        }
    }

    public static final class Suspension {
        public final String username;
        public final long since;
        public final long until;
        public final String by;
        public final String notice;
        public final String reason;

        Suspension(String username, long since, long until, String by, String notice, String reason) {
            this.username = username;
            this.since = since;
            this.until = until;
            this.by = by;
            this.notice = notice;
            this.reason = reason;
        }
    }

    public static final class Audit {
        public final long time;
        public final String actor;
        public final String action;
        public final String target;
        public final String detail;

        Audit(long time, String actor, String action, String target, String detail) {
            this.time = time;
            this.actor = actor;
            this.action = action;
            this.target = target;
            this.detail = detail;
        }
    }

    private static final class ReportRec {
        final long id;
        final long time;
        final String reporter;
        final String type;
        final String room;
        final String target;
        final long messageId;
        final String reason;
        final String note;
        String status = "open";
        String actions = "";
        String handledBy = "";
        long handledTime = 0;
        final List<Line> context;

        ReportRec(long id, long time, String reporter, String type, String room, String target, long messageId,
                  String reason, String note, List<Line> context) {
            this.id = id;
            this.time = time;
            this.reporter = reporter;
            this.type = type;
            this.room = room;
            this.target = target;
            this.messageId = messageId;
            this.reason = reason;
            this.note = note;
            this.context = context;
        }

        Report view() {
            return new Report(id, time, reporter, type, room, target, messageId, reason, note, status, actions,
                    handledBy, handledTime, new ArrayList<Line>(context));
        }
    }

    private static final class SuspensionRec {
        final String username;
        final long since;
        final long until;
        final String by;
        final String notice;
        final String reason;
        final boolean priorDisabled;

        SuspensionRec(String username, long since, long until, String by, String notice, String reason,
                      boolean priorDisabled) {
            this.username = username;
            this.since = since;
            this.until = until;
            this.by = by;
            this.notice = notice;
            this.reason = reason;
            this.priorDisabled = priorDisabled;
        }
    }

    private final Path file;
    private final LongSupplier clock;
    private final ChatStore chat;
    private final UserStore users;
    private final SessionStore sessions;
    private final BiConsumer<String, String> releaseClaim;
    private final Limiter limiter;
    private final List<ReportRec> reports = new ArrayList<ReportRec>();
    private final Map<String, SuspensionRec> suspensions = new LinkedHashMap<String, SuspensionRec>();
    private final List<Audit> audit = new ArrayList<Audit>();
    private long nextId = 1;

    public ModerationService(Path file, LongSupplier clock, ChatStore chat, UserStore users, SessionStore sessions,
                             BiConsumer<String, String> releaseClaim) {
        this.file = file;
        this.clock = clock;
        this.chat = chat;
        this.users = users;
        this.sessions = sessions;
        this.releaseClaim = releaseClaim;
        this.limiter = new Limiter(clock);
    }

    public synchronized void init() throws IOException {
        reports.clear();
        suspensions.clear();
        audit.clear();
        nextId = 1;
        if (Files.exists(file)) {
            load();
        }
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    /** One line, no control characters, at most max characters. */
    private static String line(String raw, int max) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            sb.append((ch < 0x20 || ch == 0x7f) ? ' ' : ch);
        }
        String s = sb.toString().trim();
        return s.length() > max ? s.substring(0, max).trim() : s;
    }

    private static Line lineOf(ChatStore.Message m) {
        return new Line(m.id, m.user, m.time, m.text);
    }

    // ---- Reports ----

    /** Records a report with a snapshot of what was reported. The caller has checked the reporter may see the room. */
    public synchronized long submitReport(String reporter, String type, String room, long messageId,
                                          String targetName, String reason, String rawNote) throws ChatStore.ChatException {
        String reasonKey = reason == null ? "" : reason.trim().toLowerCase(Locale.ROOT);
        if (!REASONS.contains(reasonKey)) {
            throw new ChatStore.ChatException(400, "choose a reason: spam, abuse, harassment, illegal or other");
        }
        String kind = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        String rk = lower(reporter);
        if (limiter.count("rp|" + rk, HOUR) >= 10) {
            throw new ChatStore.ChatException(429, "you can send 10 reports an hour - please wait");
        }
        String note = line(rawNote, 300);
        List<Line> context = new ArrayList<Line>();
        String target;
        long msgId = 0;
        if (kind.equals("message")) {
            ChatStore.Message m = chat.find(room, messageId);
            if (m == null || m.deleted) {
                throw new ChatStore.ChatException(404, "that message is not available");
            }
            target = m.user;
            msgId = m.id;
            for (ChatStore.Message c : chat.context(room, messageId, 4)) {
                context.add(lineOf(c));
            }
        } else if (kind.equals("user")) {
            UserStore.Info u = targetName == null ? null : users.info(targetName);
            if (u == null) {
                throw new ChatStore.ChatException(404, "no such user");
            }
            target = u.username;
            for (ChatStore.Message c : chat.recentBy(room, target, 5)) {
                context.add(lineOf(c));
            }
        } else if (kind.equals("group")) {
            if (!ChatStore.isGroupId(room) || !chat.hasGroup(room)) {
                throw new ChatStore.ChatException(400, "only groups can be reported as a group");
            }
            target = chat.groupOwner(room);
            for (ChatStore.Message c : chat.messages(room, 0, 5)) {
                if (!c.deleted) {
                    context.add(lineOf(c));
                }
            }
        } else {
            throw new ChatStore.ChatException(400, "unknown report type");
        }
        if (target.equalsIgnoreCase(reporter)) {
            throw new ChatStore.ChatException(400, "you cannot report yourself");
        }
        UserStore.Info ti = users.info(target);
        if (ti != null && ti.role == UserStore.Role.ADMIN) {
            throw new ChatStore.ChatException(400, "the super-admin cannot be reported here");
        }
        long now = clock.getAsLong();
        for (ReportRec r : reports) {
            if (r.reporter.equalsIgnoreCase(reporter) && r.type.equals(kind) && r.room.equals(room)
                    && r.target.equalsIgnoreCase(target) && r.messageId == msgId && r.time > now - DAY) {
                throw new ChatStore.ChatException(429, "you already reported this");
            }
        }
        ReportRec rec = new ReportRec(nextId++, now, reporter, kind, room, target, msgId, reasonKey, note, context);
        reports.add(rec);
        limiter.record("rp|" + rk);
        while (reports.size() > MAX_REPORTS) {
            int drop = 0;
            for (int i = 0; i < reports.size(); i++) {
                if (!"open".equals(reports.get(i).status)) {
                    drop = i;
                    break;
                }
            }
            reports.remove(drop);
        }
        saveQuiet();
        return rec.id;
    }

    /** Newest first. status is open, handled, dismissed or all. */
    public synchronized List<Report> list(String status, int limit) {
        List<Report> out = new ArrayList<Report>();
        for (int i = reports.size() - 1; i >= 0 && out.size() < limit; i--) {
            ReportRec r = reports.get(i);
            if ("all".equals(status) || r.status.equals(status)) {
                out.add(r.view());
            }
        }
        return out;
    }

    private ReportRec rec(long id) throws ChatStore.ChatException {
        for (ReportRec r : reports) {
            if (r.id == id) {
                return r;
            }
        }
        throw new ChatStore.ChatException(404, "no such report");
    }

    private void markHandled(ReportRec r, String admin, String action) {
        if ("open".equals(r.status)) {
            r.status = "handled";
        }
        r.actions = (r.actions.isEmpty() ? "" : r.actions + "; ") + action;
        r.handledBy = admin;
        r.handledTime = clock.getAsLong();
        saveQuiet();
    }

    public synchronized void dismiss(String admin, long id, String note) throws ChatStore.ChatException {
        ReportRec r = rec(id);
        r.status = "dismissed";
        r.actions = "dismissed" + (line(note, 100).isEmpty() ? "" : " (" + line(note, 100) + ")");
        r.handledBy = admin;
        r.handledTime = clock.getAsLong();
        record(admin, "dismiss report", r.target, "report " + id);
        saveQuiet();
    }

    /** A private message to the admin of the group the report is about. */
    public synchronized void warn(String admin, long id, String text) throws ChatStore.ChatException {
        ReportRec r = rec(id);
        String owner = chat.groupOwner(r.room);
        UserStore.Info oi = owner.isEmpty() ? null : users.info(owner);
        if (oi == null || oi.role == UserStore.Role.ADMIN) {
            throw new ChatStore.ChatException(400, "this room has no group admin to warn");
        }
        chat.mailFromAdmin(oi.username, text);
        markHandled(r, admin, "warned the group admin " + oi.username);
        record(admin, "warn group admin", oi.username, "report " + id);
    }

    /** Deletes the reported message, even in a private group. This is the only way the super-admin touches one. */
    public synchronized void deleteReportedMessage(String admin, long id) throws ChatStore.ChatException {
        ReportRec r = rec(id);
        if (!"message".equals(r.type)) {
            throw new ChatStore.ChatException(400, "this report is not about a message");
        }
        chat.delete(r.room, r.messageId, admin, true);
        markHandled(r, admin, "deleted the message");
        record(admin, "delete message", r.target, "report " + id);
    }

    public synchronized void suspendFromReport(String admin, long id, long hours, String notice, String reason)
            throws ChatStore.ChatException {
        ReportRec r = rec(id);
        suspendAccount(admin, r.target, hours, notice, reason);
        markHandled(r, admin, "suspended " + r.target + (hours <= 0 ? " until reinstated" : " for " + hours + " h"));
    }

    public synchronized int deleteAccountFromReport(String admin, long id) throws ChatStore.ChatException {
        ReportRec r = rec(id);
        int groups = deleteAccount(admin, r.target);
        markHandled(r, admin, "deleted the account " + r.target);
        return groups;
    }

    // ---- Accounts ----

    /** Suspends an account. hours = 0 means until reinstated. */
    public synchronized void suspendAccount(String actor, String username, long hours, String notice, String reason)
            throws ChatStore.ChatException {
        UserStore.Info info = username == null ? null : users.info(username);
        if (info == null) {
            throw new ChatStore.ChatException(404, "no such user");
        }
        if (info.role == UserStore.Role.ADMIN) {
            throw new ChatStore.ChatException(400, "the admin account cannot be suspended");
        }
        String key = lower(info.username);
        SuspensionRec old = suspensions.get(key);
        boolean prior = old != null ? old.priorDisabled : info.disabled;
        try {
            users.setDisabled(info.username, true);
        } catch (Exception e) {
            throw new ChatStore.ChatException(500, "could not suspend the account: " + e.getMessage());
        }
        try {
            releaseClaim.accept(info.username, "Your account was suspended by the admin.");
        } catch (RuntimeException e) {
            // the account is suspended even if the channel could not be released
        }
        sessions.removeUser(info.username);
        long now = clock.getAsLong();
        long until = hours <= 0 ? 0L : now + Math.min(hours, 8760L) * HOUR;
        suspensions.put(key, new SuspensionRec(info.username, now, until, actor, line(notice, 200),
                line(reason, 200), prior));
        record(actor, "suspend", info.username, hours <= 0 ? "until reinstated" : hours + " h");
        saveQuiet();
    }

    /** Reinstates a suspended account. */
    public synchronized void lift(String actor, String username) throws ChatStore.ChatException {
        SuspensionRec s = username == null ? null : suspensions.get(lower(username));
        if (s == null) {
            throw new ChatStore.ChatException(404, "that account is not suspended");
        }
        suspensions.remove(lower(username));
        if (!s.priorDisabled && users.info(s.username) != null) {
            try {
                users.setDisabled(s.username, false);
            } catch (Exception e) {
                throw new ChatStore.ChatException(500, "could not reinstate the account: " + e.getMessage());
            }
        }
        record(actor, "reinstate", s.username, "");
        saveQuiet();
    }

    /** Deletes a registered or guest account. Returns how many groups were deleted with it. */
    public synchronized int deleteAccount(String actor, String username) throws ChatStore.ChatException {
        UserStore.Info info = username == null ? null : users.info(username);
        if (info == null) {
            throw new ChatStore.ChatException(404, "no such user");
        }
        if (info.role == UserStore.Role.ADMIN) {
            throw new ChatStore.ChatException(400, "the admin account cannot be deleted");
        }
        if (info.role == UserStore.Role.BROADCASTER) {
            throw new ChatStore.ChatException(400, "broadcaster accounts can be suspended but not deleted here");
        }
        try {
            releaseClaim.accept(info.username, "Your account was deleted by the admin.");
        } catch (RuntimeException e) {
            // keep going
        }
        try {
            users.deleteAccount(info.username);
        } catch (Exception e) {
            throw new ChatStore.ChatException(500, "could not delete the account: " + e.getMessage());
        }
        int groups = chat.deleteGroupsOwnedBy(info.username);
        chat.removeUserEverywhere(info.username);
        sessions.removeUser(info.username);
        suspensions.remove(lower(info.username));
        record(actor, "delete account", info.username, groups + " group(s) deleted");
        saveQuiet();
        return groups;
    }

    public synchronized List<Suspension> suspensions() {
        List<Suspension> out = new ArrayList<Suspension>();
        for (SuspensionRec s : suspensions.values()) {
            out.add(new Suspension(s.username, s.since, s.until, s.by, s.notice, s.reason));
        }
        return out;
    }

    /** What a person sees when they try to sign in to a suspended account, or null if it is not suspended. */
    public synchronized String suspensionMessage(String username) {
        if (username == null) {
            return null;
        }
        SuspensionRec s = suspensions.get(lower(username.trim()));
        if (s == null) {
            return null;
        }
        UserStore.Info info = users.info(username.trim());
        long now = clock.getAsLong();
        if (info == null || !info.disabled || (s.until != 0 && s.until <= now)) {
            return null;
        }
        StringBuilder sb = new StringBuilder("This account is suspended");
        if (s.until == 0) {
            sb.append(" until the administrator reinstates it.");
        } else {
            long mins = (s.until - now + 59999L) / 60000L;
            if (mins < 60) {
                sb.append(" for another ").append(mins).append(mins == 1 ? " minute." : " minutes.");
            } else {
                long hours = (mins + 59) / 60;
                sb.append(" for another ").append(hours).append(hours == 1 ? " hour." : " hours.");
            }
        }
        if (!s.notice.isEmpty()) {
            sb.append(' ').append(s.notice);
        }
        sb.append(" If you think this is a mistake, contact the administrator.");
        return sb.toString();
    }

    /** Lifts suspensions whose time is up, in accounts and in groups. Returns how many. */
    public synchronized int tick() {
        long now = clock.getAsLong();
        int n = 0;
        for (String key : new ArrayList<String>(suspensions.keySet())) {
            SuspensionRec s = suspensions.get(key);
            if (s != null && s.until != 0 && s.until <= now) {
                try {
                    lift("system", s.username);
                    n++;
                } catch (ChatStore.ChatException e) {
                    System.out.println("[moderation] could not lift " + s.username + ": " + e.getMessage());
                }
            }
        }
        n += chat.tickGroups(new java.util.function.Function<String, String>() {
            @Override
            public String apply(String name) {
                UserStore.Info i = users.info(name);
                return (i == null || i.disabled) ? null : i.username;
            }
        });
        return n;
    }

    // ---- The log ----

    private void record(String actor, String action, String target, String detail) {
        audit.add(new Audit(clock.getAsLong(), actor, action, target, detail));
        while (audit.size() > MAX_AUDIT) {
            audit.remove(0);
        }
    }

    /** Newest first. */
    public synchronized List<Audit> audit(int limit) {
        List<Audit> out = new ArrayList<Audit>();
        for (int i = audit.size() - 1; i >= 0 && out.size() < limit; i--) {
            out.add(audit.get(i));
        }
        return out;
    }

    // ---- Storage ----

    private void load() throws IOException {
        for (String ln : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (ln.isEmpty() || ln.startsWith("#")) {
                continue;
            }
            String[] p = ln.split("\t", -1);
            try {
                switch (p[0]) {
                    case "N":
                        nextId = Math.max(nextId, Long.parseLong(p[1]));
                        break;
                    case "R": {
                        ReportRec r = new ReportRec(Long.parseLong(p[1]), Long.parseLong(p[2]), p[3], p[4], p[5], p[6],
                                Long.parseLong(p[7]), p[8], p[9], new ArrayList<Line>());
                        r.status = p[10];
                        r.actions = p[11];
                        r.handledBy = p[12];
                        r.handledTime = Long.parseLong(p[13]);
                        reports.add(r);
                        nextId = Math.max(nextId, r.id + 1);
                        break;
                    }
                    case "C": {
                        long rid = Long.parseLong(p[1]);
                        for (int i = reports.size() - 1; i >= 0; i--) {
                            if (reports.get(i).id == rid) {
                                reports.get(i).context.add(new Line(Long.parseLong(p[2]), p[3], Long.parseLong(p[4]),
                                        p.length > 5 ? p[5] : ""));
                                break;
                            }
                        }
                        break;
                    }
                    case "S":
                        suspensions.put(lower(p[1]), new SuspensionRec(p[1], Long.parseLong(p[2]), Long.parseLong(p[3]),
                                p[4], p[5], p[6], "1".equals(p[7])));
                        break;
                    case "A":
                        audit.add(new Audit(Long.parseLong(p[1]), p[2], p[3], p[4], p.length > 5 ? p[5] : ""));
                        break;
                    default:
                        break;
                }
            } catch (RuntimeException e) {
                // skip a damaged line
            }
        }
    }

    private void saveQuiet() {
        try {
            save();
        } catch (IOException e) {
            System.out.println("[moderation] could not save: " + e.getMessage());
        }
    }

    private void save() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# VStream moderation - keep private\n");
        sb.append("N\t").append(nextId).append('\n');
        for (ReportRec r : reports) {
            sb.append("R\t").append(r.id).append('\t').append(r.time).append('\t').append(r.reporter).append('\t')
                    .append(r.type).append('\t').append(r.room).append('\t').append(r.target).append('\t')
                    .append(r.messageId).append('\t').append(r.reason).append('\t').append(r.note).append('\t')
                    .append(r.status).append('\t').append(r.actions).append('\t').append(r.handledBy).append('\t')
                    .append(r.handledTime).append('\n');
            for (Line l : r.context) {
                sb.append("C\t").append(r.id).append('\t').append(l.id).append('\t').append(l.user).append('\t')
                        .append(l.time).append('\t').append(l.text).append('\n');
            }
        }
        for (SuspensionRec s : suspensions.values()) {
            sb.append("S\t").append(s.username).append('\t').append(s.since).append('\t').append(s.until).append('\t')
                    .append(s.by).append('\t').append(s.notice).append('\t').append(s.reason).append('\t')
                    .append(s.priorDisabled ? "1" : "0").append('\n');
        }
        for (Audit a : audit) {
            sb.append("A\t").append(a.time).append('\t').append(a.actor).append('\t').append(a.action).append('\t')
                    .append(a.target).append('\t').append(a.detail).append('\n');
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
    }
}
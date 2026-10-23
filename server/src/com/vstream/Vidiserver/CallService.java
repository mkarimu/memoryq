package com.vstream.Vidiserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * The calls add-on. Any signed-in person can call any other (except the super-admin); blocks, do-not-disturb and
 * rate limits protect people from unwanted calls. A call starts with one person; anyone in the call can then add
 * more people. With three or more people it is a group call; with
 * two it is one-to-one again. Do-not-disturb, blocks, call history and missed calls live here too. The media
 * flows through MediaMTX (WebRTC); this class only decides who may publish and read each call stream (authorize).
 * Off by default. Saved to a text file.
 */
public final class CallService {

    public static final class CallException extends Exception {
        private static final long serialVersionUID = 1L;
        public final int status;

        public CallException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    public static final long RING_MS = 45000L;
    public static final long DROP_MS = 30000L;
    public static final long MAX_CALL_MS = 4L * 3600000L;
    public static final int MAX_VIDEO = 6;
    public static final int MAX_VOICE = 8;

    private static final long HOUR = 3600000L;
    private static final long KEEP_ENDED_MS = 10L * 60000L;
    private static final int MAX_HISTORY = 2000;
    private static final int MAX_BLOCKS = 200;
    private static final int MAX_ADD_AT_ONCE = 7;
    private static final SecureRandom RANDOM = new SecureRandom();

    // ---- What the API sees ----

    public static final class PartView {
        public final String name;
        public final String state;
        public final String by;

        PartView(String name, String state, String by) {
            this.name = name;
            this.state = state;
            this.by = by;
        }
    }

    public static final class View {
        public final String id;
        public final boolean video;
        public final boolean group;
        public final String initiator;
        public final String room;
        public final boolean ended;
        public final String endReason;
        public final long created;
        public final long answeredAt;
        public final long endedAt;
        public final List<PartView> participants;
        public final String myState;
        public final String invitedBy;
        public final String pubToken;
        public final String readToken;
        public final long ringLeftMs;

        View(String id, boolean video, boolean group, String initiator, String room, boolean ended, String endReason,
             long created, long answeredAt, long endedAt, List<PartView> participants, String myState,
             String invitedBy, String pubToken, String readToken, long ringLeftMs) {
            this.id = id;
            this.video = video;
            this.group = group;
            this.initiator = initiator;
            this.room = room;
            this.ended = ended;
            this.endReason = endReason;
            this.created = created;
            this.answeredAt = answeredAt;
            this.endedAt = endedAt;
            this.participants = participants;
            this.myState = myState;
            this.invitedBy = invitedBy;
            this.pubToken = pubToken;
            this.readToken = readToken;
            this.ringLeftMs = ringLeftMs;
        }
    }

    public static final class Skipped {
        public final String name;
        public final String reason;

        Skipped(String name, String reason) {
            this.name = name;
            this.reason = reason;
        }
    }

    public static final class StartResult {
        public final View call;
        public final List<Skipped> skipped;

        StartResult(View call, List<Skipped> skipped) {
            this.call = call;
            this.skipped = skipped;
        }
    }

    public static final class AddResult {
        public final View call;
        public final List<String> added;
        public final List<Skipped> skipped;

        AddResult(View call, List<String> added, List<Skipped> skipped) {
            this.call = call;
            this.added = added;
            this.skipped = skipped;
        }
    }

    public static final class Snapshot {
        public final List<View> ringing;
        public final View current;
        public final int missed;

        Snapshot(List<View> ringing, View current, int missed) {
            this.ringing = ringing;
            this.current = current;
            this.missed = missed;
        }
    }

    public static final class HistoryEntry {
        public final String id;
        public final boolean video;
        public final boolean group;
        public final boolean outgoing;
        public final String peer;
        public final String room;
        public final String outcome;
        public final long time;
        public final long durationMs;
        public final List<String> people;

        HistoryEntry(String id, boolean video, boolean group, boolean outgoing, String peer, String room,
                     String outcome, long time, long durationMs, List<String> people) {
            this.id = id;
            this.video = video;
            this.group = group;
            this.outgoing = outgoing;
            this.peer = peer;
            this.room = room;
            this.outcome = outcome;
            this.time = time;
            this.durationMs = durationMs;
            this.people = people;
        }
    }

    public static final class AdminCall {
        public final String id;
        public final boolean video;
        public final boolean group;
        public final String initiator;
        public final int joined;
        public final int total;
        public final long created;

        AdminCall(String id, boolean video, boolean group, String initiator, int joined, int total, long created) {
            this.id = id;
            this.video = video;
            this.group = group;
            this.initiator = initiator;
            this.joined = joined;
            this.total = total;
            this.created = created;
        }
    }

    // ---- Inside ----

    private static final class Part {
        final String name;
        String state;
        String invitedBy = "";
        long ringUntil = 0;
        long joinedAt = 0;
        long lastSeen = 0;
        String pubToken = "";
        String readToken = "";

        Part(String name, String state) {
            this.name = name;
            this.state = state;
        }
    }

    private static final class Call {
        final String id;
        final boolean video;
        final String initiator;
        final String room;
        final long created;
        long answeredAt = 0;
        long endedAt = 0;
        String endReason = "";
        int maxPeople = 2;
        final LinkedHashMap<String, Part> parts = new LinkedHashMap<String, Part>();

        Call(String id, boolean video, String initiator, String room, long created) {
            this.id = id;
            this.video = video;
            this.initiator = initiator;
            this.room = room;
            this.created = created;
        }
    }

    private static final class Hist {
        final String id;
        final boolean video;
        final String initiator;
        final String room;
        final boolean group;
        final long created;
        final long ended;
        final long answered;
        final LinkedHashMap<String, String> outcomes;

        Hist(String id, boolean video, String initiator, String room, boolean group, long created, long ended,
             long answered, LinkedHashMap<String, String> outcomes) {
            this.id = id;
            this.video = video;
            this.initiator = initiator;
            this.room = room;
            this.group = group;
            this.created = created;
            this.ended = ended;
            this.answered = answered;
            this.outcomes = outcomes;
        }
    }

    private static final class Target {
        final String name;
        final String key;

        Target(String name, String key) {
            this.name = name;
            this.key = key;
        }
    }

    private final Path file;
    private final LongSupplier clock;
    private final ChatStore chat;
    private final UserStore users;
    private final boolean guestsMayStart;
    private final Limiter limiter;
    private final Map<String, Call> calls = new LinkedHashMap<String, Call>();
    private final List<Hist> history = new ArrayList<Hist>();
    private final Set<String> dnd = new LinkedHashSet<String>();
    private final Map<String, LinkedHashSet<String>> blocks = new LinkedHashMap<String, LinkedHashSet<String>>();
    private final Map<String, Long> missedSeen = new LinkedHashMap<String, Long>();
    private boolean enabled = false;
    private boolean dirty = false;

    public CallService(Path file, LongSupplier clock, ChatStore chat, UserStore users, boolean guestsMayStart) {
        this.file = file;
        this.clock = clock;
        this.chat = chat;
        this.users = users;
        this.guestsMayStart = guestsMayStart;
        this.limiter = new Limiter(clock);
    }

    public synchronized void init() throws IOException {
        calls.clear();
        history.clear();
        dnd.clear();
        blocks.clear();
        missedSeen.clear();
        enabled = false;
        if (Files.exists(file)) {
            load();
        }
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    private static String shortName(String s) {
        String t = s == null ? "" : s.trim();
        return t.length() > 24 ? t.substring(0, 24) : t;
    }

    private static String newId() {
        byte[] b = new byte[8];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder(16);
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    // ---- The switch ----

    public synchronized boolean enabled() {
        return enabled;
    }

    /** Turning the add-on off ends every call in progress. */
    public synchronized void setEnabled(boolean on) {
        if (enabled == on) {
            return;
        }
        enabled = on;
        if (!on) {
            for (Call c : new ArrayList<Call>(calls.values())) {
                if (c.endedAt == 0) {
                    endCall(c, "calls were turned off");
                }
            }
        }
        dirty = true;
    }

    public void requireEnabled() throws CallException {
        if (!enabled()) {
            throw new CallException(404, "calls are not enabled on this server");
        }
    }

    private void needOn() throws CallException {
        if (!enabled) {
            throw new CallException(404, "calls are not enabled on this server");
        }
    }

    // ---- Starting a call (always with one person) ----

    private List<Target> parseTargets(String callerKey, List<String> to, boolean selfIsError) throws CallException {
        List<Target> targets = new ArrayList<Target>();
        Set<String> seen = new LinkedHashSet<String>();
        if (to != null) {
            for (String raw : to) {
                if (raw == null || raw.trim().isEmpty()) {
                    continue;
                }
                UserStore.Info ti = users.info(raw.trim());
                if (ti == null || ti.disabled) {
                    throw new CallException(404, "no such user: " + shortName(raw));
                }
                if (ti.role == UserStore.Role.ADMIN) {
                    throw new CallException(400, "the super-admin cannot be called");
                }
                String tk = lower(ti.username);
                if (tk.equals(callerKey)) {
                    if (selfIsError) {
                        throw new CallException(400, "you cannot call yourself");
                    }
                    continue;
                }
                if (seen.add(tk)) {
                    targets.add(new Target(ti.username, tk));
                }
            }
        }
        return targets;
    }

    /**
     * Starts a call with one person. room (optional) is the chat room the call was started from; it is only shown
     * for information.
     */
    public synchronized StartResult start(String caller, boolean video, String room, List<String> to)
            throws CallException {
        needOn();
        expire();
        UserStore.Info me = users.info(caller);
        if (me == null || me.disabled) {
            throw new CallException(403, "your account cannot start calls");
        }
        if (me.role == UserStore.Role.ADMIN) {
            throw new CallException(403, "the super-admin account does not make calls");
        }
        if (me.role == UserStore.Role.GUEST && !guestsMayStart) {
            throw new CallException(403, "guest accounts can join calls but not start them - create a registered account");
        }
        if (chat.isMuted(me.username)) {
            throw new CallException(403, "you are muted and cannot start calls");
        }
        String ck = lower(me.username);
        if (currentCallOf(ck) != null) {
            throw new CallException(409, "you are already in a call");
        }
        if (limiter.count("cs|" + ck, HOUR) >= 10) {
            throw new CallException(429, "you can start 10 calls an hour - please wait");
        }
        long now = clock.getAsLong();
        List<Target> targets = parseTargets(ck, to, true);
        if (targets.size() != 1) {
            throw new CallException(400, "choose one person to call - you can add more people once the call has started");
        }
        Target t = targets.get(0);
        // Shown as "through <group>": the group the call was started from if both are in it, otherwise any
        // private group the two share, otherwise none. A group is never shown to someone who is not in it.
        List<String> common = chat.commonPrivateGroups(me.username, t.name);
        String via = (room != null && common.contains(room)) ? room : (common.isEmpty() ? "" : common.get(0));
        if (isBlocked(ck, t.key)) {
            throw new CallException(409, "you have blocked this person - unblock them to call");
        }
        if (limiter.count("ct|" + ck + "|" + t.key, 10 * 60000L) >= 3) {
            throw new CallException(429, "you called this person several times - please wait a few minutes");
        }
        limiter.record("cs|" + ck);
        limiter.record("ct|" + ck + "|" + t.key);
        if (isBlocked(t.key, ck)) {
            throw new CallException(409, "this person is not available right now");
        }
        if (dnd.contains(t.key)) {
            recordDnd(me.username, t.name, via, video, now);
            throw new CallException(409, "this person is not available right now");
        }
        if (currentCallOf(t.key) != null) {
            throw new CallException(409, "this person is busy on another call");
        }
        Call c = new Call(newId(), video, me.username, via, now);
        addCaller(c, me.username, now);
        addInvitee(c, t.name, now, me.username);
        calls.put(c.id, c);
        dirty = true;
        return new StartResult(view(c, ck), new ArrayList<Skipped>());
    }

    private void giveTokens(Part p, long now) {
        p.pubToken = LiveChannelRegistry.newToken();
        p.readToken = LiveChannelRegistry.newToken();
        p.joinedAt = now;
        p.lastSeen = now;
        p.state = "JOINED";
    }

    private void addCaller(Call c, String name, long now) {
        Part p = new Part(name, "JOINED");
        giveTokens(p, now);
        c.parts.put(lower(name), p);
    }

    private void addInvitee(Call c, String name, long now, String by) {
        Part p = new Part(name, "INVITED");
        p.ringUntil = now + RING_MS;
        p.invitedBy = by;
        c.parts.put(lower(name), p);
    }

    private void recordDnd(String caller, String callee, String room, boolean video, long now) {
        LinkedHashMap<String, String> outcomes = new LinkedHashMap<String, String>();
        outcomes.put(caller, "no answer");
        outcomes.put(callee, "dnd");
        addHistory(new Hist(newId(), video, caller, room, false, now, now, 0, outcomes));
    }

    // ---- Adding people to a call ----

    /**
     * Someone who is in the call adds people they could call themselves. The call becomes a group call when it
     * has three or more people. Returns who was rung and who was skipped (and why).
     */
    public synchronized AddResult add(String actor, String id, List<String> to) throws CallException {
        needOn();
        expire();
        Call c = activeCall(id);
        String ak = lower(actor);
        Part me = c.parts.get(ak);
        if (me == null) {
            throw new CallException(404, "this call has ended or does not exist");
        }
        if (!me.state.equals("JOINED")) {
            throw new CallException(403, "only people in the call can add others");
        }
        UserStore.Info ai = users.info(actor);
        if (ai == null || ai.disabled) {
            throw new CallException(403, "your account cannot add people to calls");
        }
        if (chat.isMuted(ai.username)) {
            throw new CallException(403, "you are muted and cannot add people to calls");
        }
        List<Target> targets = parseTargets(ak, to, false);
        if (targets.isEmpty()) {
            throw new CallException(400, "choose who to add");
        }
        if (targets.size() > MAX_ADD_AT_ONCE) {
            throw new CallException(400, "you can add up to " + MAX_ADD_AT_ONCE + " people at a time");
        }
        int cap = c.video ? MAX_VIDEO : MAX_VOICE;
        int fresh = 0;
        for (Target t : targets) {
            Part existing = c.parts.get(t.key);
            if (existing == null || !(existing.state.equals("JOINED") || existing.state.equals("INVITED"))) {
                fresh++;
            }
        }
        if (activeCount(c) + fresh > cap) {
            throw new CallException(400, "too many people - a " + (c.video ? "video" : "voice")
                    + " call can have at most " + cap + " people");
        }
        long now = clock.getAsLong();
        List<String> added = new ArrayList<String>();
        List<Skipped> skipped = new ArrayList<Skipped>();
        int firstStatus = 0;
        for (Target t : targets) {
            String[] why = addRefusal(c, ai.username, ak, t, now);
            if (why != null) {
                skipped.add(new Skipped(t.name, why[1]));
                if (firstStatus == 0) {
                    firstStatus = Integer.parseInt(why[0]);
                }
                continue;
            }
            Part p = c.parts.get(t.key);
            if (p == null) {
                p = new Part(t.name, "INVITED");
                c.parts.put(t.key, p);
            }
            p.state = "INVITED";
            p.ringUntil = now + RING_MS;
            p.invitedBy = ai.username;
            p.pubToken = "";
            p.readToken = "";
            p.joinedAt = 0;
            limiter.record("ct|" + ak + "|" + t.key);
            limiter.record("ad|" + ak);
            added.add(t.name);
        }
        if (added.isEmpty() && targets.size() == 1) {
            throw new CallException(firstStatus, skipped.get(0).reason);
        }
        c.maxPeople = Math.max(c.maxPeople, activeCount(c));
        dirty = true;
        return new AddResult(view(c, ak), added, skipped);
    }

    /** Why a person cannot be added right now as {status, message}, or null if they can. */
    private String[] addRefusal(Call c, String actor, String actorKey, Target t, long now) {
        Part existing = c.parts.get(t.key);
        if (existing != null && (existing.state.equals("JOINED") || existing.state.equals("INVITED"))) {
            return new String[]{"409", "this person is already in the call"};
        }
        if (isBlocked(actorKey, t.key)) {
            return new String[]{"409", "you have blocked this person - unblock them to add them"};
        }
        if (isBlocked(t.key, actorKey)) {
            return new String[]{"409", "this person is not available right now"};
        }
        for (Part p : c.parts.values()) {
            if (p.state.equals("JOINED") || p.state.equals("INVITED")) {
                String pk = lower(p.name);
                if (isBlocked(t.key, pk) || isBlocked(pk, t.key)) {
                    return new String[]{"409", "this person is not available right now"};
                }
            }
        }
        if (limiter.count("ct|" + actorKey + "|" + t.key, 10 * 60000L) >= 3) {
            return new String[]{"429", "you rang this person several times - please wait a few minutes"};
        }
        if (limiter.count("ad|" + actorKey, HOUR) >= 20) {
            return new String[]{"429", "you can add 20 people an hour - please wait"};
        }
        if (dnd.contains(t.key)) {
            recordDnd(actor, t.name, c.room, c.video, now);
            return new String[]{"409", "this person is not available right now"};
        }
        if (currentCallOf(t.key) != null) {
            return new String[]{"409", "this person is busy on another call"};
        }
        return null;
    }

    private int activeCount(Call c) {
        int n = 0;
        for (Part p : c.parts.values()) {
            if (p.state.equals("JOINED") || p.state.equals("INVITED")) {
                n++;
            }
        }
        return n;
    }

    // ---- Seeing and answering calls ----

    /** What the app asks every couple of seconds: calls ringing for me, my current call, my unseen missed calls. */
    public synchronized Snapshot poll(String user) throws CallException {
        needOn();
        expire();
        String k = lower(user);
        long now = clock.getAsLong();
        List<View> ringing = new ArrayList<View>();
        View current = null;
        for (Call c : calls.values()) {
            if (c.endedAt != 0) {
                continue;
            }
            Part p = c.parts.get(k);
            if (p == null) {
                continue;
            }
            if (p.state.equals("INVITED") && p.ringUntil > now) {
                ringing.add(view(c, k));
            } else if (p.state.equals("JOINED")) {
                p.lastSeen = now;
                current = view(c, k);
            }
        }
        return new Snapshot(ringing, current, missedUnseen(k));
    }

    private Call activeCall(String id) throws CallException {
        Call c = id == null ? null : calls.get(id);
        if (c == null || c.endedAt != 0) {
            throw new CallException(404, "this call has ended or does not exist");
        }
        return c;
    }

    public synchronized View accept(String user, String id) throws CallException {
        needOn();
        expire();
        Call c = activeCall(id);
        String k = lower(user);
        Part p = c.parts.get(k);
        long now = clock.getAsLong();
        if (p == null || !p.state.equals("INVITED") || p.ringUntil <= now) {
            throw new CallException(404, "this call is no longer ringing");
        }
        UserStore.Info me = users.info(user);
        if (me == null || me.disabled) {
            throw new CallException(403, "your account cannot join calls");
        }
        if (currentCallOf(k) != null) {
            throw new CallException(409, "you are already in a call");
        }
        giveTokens(p, now);
        if (c.answeredAt == 0) {
            c.answeredAt = now;
        }
        dirty = true;
        return view(c, k);
    }

    public synchronized void decline(String user, String id) throws CallException {
        needOn();
        expire();
        Call c = activeCall(id);
        Part p = c.parts.get(lower(user));
        if (p == null || !p.state.equals("INVITED")) {
            throw new CallException(404, "this call is no longer ringing");
        }
        p.state = "DECLINED";
        evaluate(c);
    }

    public synchronized void leave(String user, String id) throws CallException {
        needOn();
        expire();
        Call c = calls.get(id);
        String k = lower(user);
        Part p = (c == null || c.endedAt != 0) ? null : c.parts.get(k);
        if (p == null || !p.state.equals("JOINED")) {
            throw new CallException(404, "you are not in this call");
        }
        if (c.answeredAt == 0 && k.equals(lower(c.initiator))) {
            c.endReason = "cancelled";
        }
        p.state = "LEFT";
        evaluate(c);
    }

    /** The person who started the call ends it for everyone. */
    public synchronized void end(String user, String id) throws CallException {
        needOn();
        expire();
        Call c = calls.get(id);
        String k = lower(user);
        if (c == null || c.endedAt != 0 || !c.parts.containsKey(k)) {
            throw new CallException(404, "this call has ended or does not exist");
        }
        if (!k.equals(lower(c.initiator))) {
            throw new CallException(403, "only the person who started the call can end it for everyone - use Leave");
        }
        endCall(c, "ended");
    }

    /** The super-admin ends any call. */
    public synchronized void adminEnd(String id) throws CallException {
        Call c = calls.get(id);
        if (c == null || c.endedAt != 0) {
            throw new CallException(404, "no such call");
        }
        endCall(c, "ended by the administrator");
    }

    public synchronized View state(String user, String id) throws CallException {
        needOn();
        expire();
        Call c = calls.get(id);
        String k = lower(user);
        if (c == null || !c.parts.containsKey(k)) {
            throw new CallException(404, "this call has ended or does not exist");
        }
        Part p = c.parts.get(k);
        if (p.state.equals("JOINED") && c.endedAt == 0) {
            p.lastSeen = clock.getAsLong();
        }
        return view(c, k);
    }

    private View view(Call c, String viewerKey) {
        long now = clock.getAsLong();
        List<PartView> pv = new ArrayList<PartView>();
        for (Part p : c.parts.values()) {
            pv.add(new PartView(p.name, p.state.equals("DND") ? "MISSED" : p.state, p.invitedBy));
        }
        Part me = c.parts.get(viewerKey);
        String myState = me == null ? "" : (me.state.equals("DND") ? "MISSED" : me.state);
        boolean creds = me != null && me.state.equals("JOINED") && c.endedAt == 0;
        long ringLeft = (me != null && me.state.equals("INVITED") && c.endedAt == 0) ? Math.max(0, me.ringUntil - now) : 0;
        boolean group = c.endedAt == 0 ? activeCount(c) >= 3 : c.maxPeople >= 3;
        return new View(c.id, c.video, group, c.initiator, c.room, c.endedAt != 0, c.endReason, c.created,
                c.answeredAt, c.endedAt, pv, myState, me == null ? "" : me.invitedBy,
                creds ? me.pubToken : "", creds ? me.readToken : "", ringLeft);
    }

    private Call currentCallOf(String key) {
        for (Call c : calls.values()) {
            if (c.endedAt == 0) {
                Part p = c.parts.get(key);
                if (p != null && p.state.equals("JOINED")) {
                    return c;
                }
            }
        }
        return null;
    }

    // ---- Time passing ----

    /** Ends calls that are over, lets unanswered rings time out, and removes people who dropped off. */
    private void expire() {
        long now = clock.getAsLong();
        for (Call c : new ArrayList<Call>(calls.values())) {
            if (c.endedAt != 0) {
                if (now - c.endedAt > KEEP_ENDED_MS) {
                    calls.remove(c.id);
                }
                continue;
            }
            for (Part p : c.parts.values()) {
                if (p.state.equals("INVITED") && p.ringUntil <= now) {
                    p.state = "MISSED";
                } else if (p.state.equals("JOINED")) {
                    UserStore.Info i = users.info(p.name);
                    if (i == null || i.disabled || now - p.lastSeen > DROP_MS) {
                        p.state = "LEFT";
                    }
                }
            }
            if (now - c.created > MAX_CALL_MS) {
                endCall(c, "time limit");
                continue;
            }
            evaluate(c);
        }
    }

    public synchronized void tick() {
        expire();
        flushIfDirty();
    }

    private boolean someoneDeclined(Call c) {
        for (Map.Entry<String, Part> e : c.parts.entrySet()) {
            if (!e.getKey().equals(lower(c.initiator)) && e.getValue().state.equals("DECLINED")) {
                return true;
            }
        }
        return false;
    }

    /** A call is over when fewer than two people are in it and nobody else is still ringing. */
    private void evaluate(Call c) {
        if (c.endedAt != 0) {
            return;
        }
        int joined = 0;
        int pending = 0;
        for (Part p : c.parts.values()) {
            if (p.state.equals("JOINED")) {
                joined++;
            } else if (p.state.equals("INVITED")) {
                pending++;
            }
        }
        if (joined == 0) {
            endCall(c, c.endReason.isEmpty() ? "ended" : c.endReason);
            return;
        }
        if (joined == 1 && pending == 0) {
            if (c.answeredAt == 0) {
                endCall(c, someoneDeclined(c) ? "declined" : "no answer");
            } else {
                endCall(c, "ended");
            }
        }
    }

    private void endCall(Call c, String reason) {
        if (c.endedAt != 0) {
            return;
        }
        long now = clock.getAsLong();
        c.endedAt = now;
        c.endReason = reason;
        LinkedHashMap<String, String> outcomes = new LinkedHashMap<String, String>();
        for (Map.Entry<String, Part> e : c.parts.entrySet()) {
            Part p = e.getValue();
            if (p.state.equals("JOINED")) {
                p.state = "LEFT";
            } else if (p.state.equals("INVITED")) {
                p.state = "MISSED";
            }
            String o;
            if (e.getKey().equals(lower(c.initiator))) {
                o = c.answeredAt > 0 ? "answered" : (reason.equals("declined") ? "declined" : "no answer");
            } else if (p.joinedAt > 0) {
                o = "answered";
            } else if (p.state.equals("DECLINED")) {
                o = "declined";
            } else if (p.state.equals("DND")) {
                o = "dnd";
            } else {
                o = "missed";
            }
            outcomes.put(p.name, o);
        }
        addHistory(new Hist(c.id, c.video, c.initiator, c.room, c.maxPeople >= 3, c.created, now, c.answeredAt, outcomes));
    }

    private void addHistory(Hist h) {
        history.add(h);
        while (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
        dirty = true;
    }

    // ---- The media login ----

    /** Used by MediaMTX's login check for paths call/ID/USER. Returns null if allowed, otherwise a reason. */
    public synchronized String authorize(String action, String callId, String userKey, String token) {
        if (!enabled) {
            return "calls are off";
        }
        expire();
        Call c = calls.get(callId);
        if (c == null || c.endedAt != 0) {
            return "no such call";
        }
        Part owner = c.parts.get(userKey);
        if (owner == null || !owner.state.equals("JOINED")) {
            return "not in this call";
        }
        if (token == null || token.isEmpty()) {
            return "missing token";
        }
        if ("publish".equals(action)) {
            return same(token, owner.pubToken) ? null : "wrong token";
        }
        if ("read".equals(action)) {
            for (Part p : c.parts.values()) {
                if (p.state.equals("JOINED") && same(token, p.readToken)) {
                    return null;
                }
            }
            return "wrong token";
        }
        return "action not allowed";
    }

    private static boolean same(String given, String expected) {
        return expected != null && !expected.isEmpty()
                && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    // ---- History and missed calls ----

    /** The person's own calls, newest first. */
    public synchronized List<HistoryEntry> history(String user, int limit) {
        String k = lower(user);
        List<HistoryEntry> out = new ArrayList<HistoryEntry>();
        for (int i = history.size() - 1; i >= 0 && out.size() < limit; i--) {
            Hist h = history.get(i);
            String outcome = null;
            List<String> people = new ArrayList<String>();
            for (Map.Entry<String, String> e : h.outcomes.entrySet()) {
                people.add(e.getKey());
                if (lower(e.getKey()).equals(k)) {
                    outcome = e.getValue();
                }
            }
            if (outcome == null) {
                continue;
            }
            String peer = "";
            if (!h.group) {
                for (String n : people) {
                    if (!lower(n).equals(k)) {
                        peer = n;
                    }
                }
            }
            out.add(new HistoryEntry(h.id, h.video, h.group, lower(h.initiator).equals(k), peer, h.room, outcome,
                    h.created, h.answered > 0 ? h.ended - h.answered : 0, people));
        }
        return out;
    }

    private int missedUnseen(String k) {
        long seen = missedSeen.containsKey(k) ? missedSeen.get(k) : 0L;
        int n = 0;
        for (Hist h : history) {
            if (lower(h.initiator).equals(k) || h.ended <= seen) {
                continue;
            }
            for (Map.Entry<String, String> e : h.outcomes.entrySet()) {
                if (lower(e.getKey()).equals(k) && (e.getValue().equals("missed") || e.getValue().equals("dnd"))) {
                    n++;
                }
            }
        }
        return n;
    }

    public synchronized void markMissedSeen(String user) {
        missedSeen.put(lower(user), clock.getAsLong());
        dirty = true;
    }

    // ---- Do not disturb and blocks ----

    public synchronized boolean isDnd(String user) {
        return dnd.contains(lower(user));
    }

    public synchronized void setDnd(String user, boolean on) {
        if (on ? dnd.add(lower(user)) : dnd.remove(lower(user))) {
            dirty = true;
        }
    }

    private boolean isBlocked(String blockerKey, String targetKey) {
        LinkedHashSet<String> b = blocks.get(blockerKey);
        return b != null && b.contains(targetKey);
    }

    /** True if blocker has blocked target (used for calls and for comments on walls). */
    public synchronized boolean hasBlocked(String blocker, String target) {
        return blocker != null && target != null && isBlocked(lower(blocker), lower(target));
    }

    public synchronized void block(String blocker, String target) throws CallException {
        UserStore.Info ti = target == null ? null : users.info(target.trim());
        if (ti == null) {
            throw new CallException(404, "no such user");
        }
        String bk = lower(blocker);
        if (bk.equals(lower(ti.username))) {
            throw new CallException(400, "you cannot block yourself");
        }
        LinkedHashSet<String> set = blocks.get(bk);
        if (set == null) {
            set = new LinkedHashSet<String>();
            blocks.put(bk, set);
        }
        if (set.size() >= MAX_BLOCKS && !set.contains(lower(ti.username))) {
            throw new CallException(400, "your block list is full");
        }
        if (set.add(lower(ti.username))) {
            dirty = true;
        }
    }

    public synchronized void unblock(String blocker, String target) throws CallException {
        LinkedHashSet<String> set = blocks.get(lower(blocker));
        if (target == null || set == null || !set.remove(lower(target.trim()))) {
            throw new CallException(404, "that person is not on your block list");
        }
        dirty = true;
    }

    public synchronized List<String> blockedBy(String blocker) {
        LinkedHashSet<String> set = blocks.get(lower(blocker));
        List<String> out = new ArrayList<String>();
        if (set != null) {
            for (String k : set) {
                UserStore.Info i = users.info(k);
                out.add(i == null ? k : i.username);
            }
        }
        return out;
    }

    // ---- For the admin page ----

    public synchronized List<AdminCall> adminActive() {
        expire();
        List<AdminCall> out = new ArrayList<AdminCall>();
        for (Call c : calls.values()) {
            if (c.endedAt != 0) {
                continue;
            }
            int joined = 0;
            for (Part p : c.parts.values()) {
                if (p.state.equals("JOINED")) {
                    joined++;
                }
            }
            out.add(new AdminCall(c.id, c.video, activeCount(c) >= 3, c.initiator, joined, c.parts.size(), c.created));
        }
        return out;
    }

    public synchronized int callsSince(long sinceMs) {
        int n = 0;
        for (Hist h : history) {
            if (h.created >= sinceMs) {
                n++;
            }
        }
        for (Call c : calls.values()) {
            if (c.endedAt == 0 && c.created >= sinceMs) {
                n++;
            }
        }
        return n;
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
                    case "E":
                        enabled = "1".equals(p[1]);
                        break;
                    case "D":
                        dnd.add(p[1]);
                        break;
                    case "B": {
                        LinkedHashSet<String> set = blocks.get(p[1]);
                        if (set == null) {
                            set = new LinkedHashSet<String>();
                            blocks.put(p[1], set);
                        }
                        set.add(p[2]);
                        break;
                    }
                    case "M":
                        missedSeen.put(p[1], Long.parseLong(p[2]));
                        break;
                    case "H": {
                        LinkedHashMap<String, String> outcomes = new LinkedHashMap<String, String>();
                        for (String pair : p[9].split(";")) {
                            int eq = pair.indexOf('=');
                            if (eq > 0) {
                                outcomes.put(pair.substring(0, eq), pair.substring(eq + 1));
                            }
                        }
                        history.add(new Hist(p[1], "V".equals(p[2]), p[3], p[4], "1".equals(p[5]),
                                Long.parseLong(p[6]), Long.parseLong(p[7]), Long.parseLong(p[8]), outcomes));
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
                System.out.println("[calls] could not save: " + e.getMessage());
            }
        }
    }

    private void save() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# VStream calls - keep private\n");
        sb.append("E\t").append(enabled ? "1" : "0").append('\n');
        for (String d : dnd) {
            sb.append("D\t").append(d).append('\n');
        }
        for (Map.Entry<String, LinkedHashSet<String>> e : blocks.entrySet()) {
            for (String b : e.getValue()) {
                sb.append("B\t").append(e.getKey()).append('\t').append(b).append('\n');
            }
        }
        for (Map.Entry<String, Long> e : missedSeen.entrySet()) {
            sb.append("M\t").append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        }
        for (Hist h : history) {
            sb.append("H\t").append(h.id).append('\t').append(h.video ? "V" : "A").append('\t').append(h.initiator)
                    .append('\t').append(h.room == null ? "" : h.room).append('\t').append(h.group ? "1" : "0")
                    .append('\t').append(h.created).append('\t').append(h.ended).append('\t').append(h.answered).append('\t');
            boolean first = true;
            for (Map.Entry<String, String> e : h.outcomes.entrySet()) {
                if (!first) {
                    sb.append(';');
                }
                first = false;
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            sb.append('\n');
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
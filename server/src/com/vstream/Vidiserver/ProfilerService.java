package com.vstream.Vidiserver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * The profiler: a temporary helper for testing that creates test people (real chatter accounts, marked as test)
 * with invented profiles. It starts with a batch from different countries, then adds one person every interval
 * until there are max test people. The admin can pause it, resume it or add one at once.
 */
public final class ProfilerService {

    private final ProfileStore store;
    private final UserStore users;
    private final LongSupplier clock;
    private final PeopleGenerator generator;
    private final Random random;
    private final int firstBatch;
    private final int max;
    private final long everyMs;

    public ProfilerService(ProfileStore store, UserStore users, LongSupplier clock, Random random,
                           int firstBatch, int max, long everyMs) {
        this.store = store;
        this.users = users;
        this.clock = clock;
        this.random = random;
        this.generator = new PeopleGenerator(random);
        this.firstBatch = firstBatch;
        this.max = max;
        this.everyMs = everyMs;
    }

    public int max() {
        return max;
    }

    public long everyMs() {
        return everyMs;
    }

    /** Milliseconds until the next scheduled person, or -1 if none is coming (paused or full). */
    public synchronized long msUntilNext() {
        if (!store.profilerOn() || store.testCount() >= max) {
            return -1;
        }
        if (store.testCount() == 0) {
            return 0;
        }
        return Math.max(0, store.lastGenerated() + everyMs - clock.getAsLong());
    }

    /** Runs every minute: tidies up, and creates people when they are due. Returns how many were created. */
    public synchronized int tick() {
        cleanUp();
        int made = 0;
        if (store.profilerOn() && !store.testSuspended()) {
            int have = store.testCount();
            if (have == 0) {
                made += createBatch(Math.min(firstBatch, max));
            } else if (have < max && clock.getAsLong() - store.lastGenerated() >= everyMs) {
                made += createBatch(1);
            }
        }
        store.flushIfDirty();
        return made;
    }

    /**
     * The test people master switch. OFF: no new people, no test posts or reactions, and every test account is
     * disabled - hidden from people lists, walls and searches, and unable to sign in - but nothing is deleted.
     * ON: their accounts work and show again, and the profiler carries on. Returns how many accounts changed.
     */
    public synchronized int setTestPeopleOn(boolean on, java.util.function.Consumer<String> signOut) {
        store.setTestSuspended(!on);
        store.setProfilerOn(on);
        int changed = 0;
        for (ProfileStore.Profile p : store.testPeople()) {
            UserStore.Info i = users.info(p.username);
            if (i == null) {
                continue;
            }
            if (i.disabled == on) {
                try {
                    users.setDisabled(p.username, !on);
                    changed++;
                } catch (UserStore.UserException e) {
                    System.out.println("[profiler] could not switch " + p.username + ": " + e.getMessage());
                }
            }
            if (!on && signOut != null) {
                signOut.accept(p.username);
            }
        }
        store.flushIfDirty();
        System.out.println("[profiler] test people switched " + (on ? "ON" : "OFF") + " (" + changed + " accounts)");
        return changed;
    }

    /** Creates up to n people now (never more than max in total), each from a different country if possible. */
    public synchronized int createBatch(int n) {
        if (store.testSuspended()) {
            return 0;
        }
        Set<String> countries = new LinkedHashSet<String>();
        for (ProfileStore.Profile p : store.testPeople()) {
            countries.add(p.country);
        }
        if (countries.size() >= PeopleGenerator.countryCount()) {
            countries.clear();
        }
        int made = 0;
        for (int i = 0; i < n && store.testCount() < max; i++) {
            String name = createOne(countries);
            if (name == null) {
                break;
            }
            made++;
        }
        store.flushIfDirty();
        return made;
    }

    /** Creates one person; returns their username, or null if it could not. */
    private String createOne(Set<String> avoidCountries) {
        for (int attempt = 0; attempt < 25; attempt++) {
            PeopleGenerator.Person p = generator.invent(avoidCountries);
            String username = generator.username(p);
            if (users.info(username) != null) {
                continue;
            }
            String password = UserStore.randomPassword(12);
            try {
                users.createUser(username, password, UserStore.Role.CHATTER);
            } catch (UserStore.UserException e) {
                continue;
            }
            long now = clock.getAsLong();
            store.addTestPerson(username, password, p, now);
            avoidCountries.add(p.country);
            if (avoidCountries.size() >= PeopleGenerator.countryCount()) {
                avoidCountries.clear();
            }
            makeFriends(username, now);
            System.out.println("[profiler] created test person " + username + " (" + p.stageName + ", "
                    + p.country + ")");
            return username;
        }
        return null;
    }

    /** A new test person becomes friends (both ways) with up to three other test people. */
    private void makeFriends(String username, long now) {
        List<String> others = new ArrayList<String>();
        for (ProfileStore.Profile p : store.testPeople()) {
            if (!p.username.equalsIgnoreCase(username)) {
                others.add(p.username);
            }
        }
        Collections.shuffle(others, random);
        int n = Math.min(others.size(), 1 + random.nextInt(3));
        for (int i = 0; i < n; i++) {
            store.addFriend(username, others.get(i), now);
            store.addFriend(others.get(i), username, now);
        }
    }

    /** Forgets profiles (and friendships) of accounts that no longer exist. */
    public synchronized void cleanUp() {
        for (String k : store.keys()) {
            if (users.info(k) == null) {
                store.remove(k);
            }
        }
    }
}
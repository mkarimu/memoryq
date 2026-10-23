package com.vstream.Vidiserver;

import java.io.IOException;
import java.util.List;
import java.util.Random;
import java.util.function.LongSupplier;

/**
 * Keeps the walls tidy and lively: removes posts of deleted accounts, and lets the profiler's test people post
 * invented stories (some with a drawn picture) - each test person once at the start, then one post every interval.
 * Test people also like and comment on new public posts by real people (a couple of minutes after they appear),
 * so notifications can be tried out without a second person.
 */
public final class WallService {

    private static final String[] STORIES = {
        "Just finished a {hobby} session in {city}. Best way to spend the evening!",
        "Who else here loves {hobby}? Tell me your favourite spot in {country}.",
        "Throwback to my {study} days. Time flies!",
        "Sunday mood in {city}: {hobby} and good music.",
        "Trying something new this week: more {hobby}, less phone. Wish me luck \ud83d\ude04",
        "Greetings from {city}, {country}! What is the weather like where you are?",
        "Big shout-out to everyone who came to the {hobby} meetup yesterday \ud83d\ude4c",
        "New week, new goals. Mine: get better at {hobby}. Yours?",
        "Can't believe how beautiful {city} looks tonight.",
        "Coffee, {hobby}, repeat. That is the plan for today \u2615",
    };

    private final WallStore wall;
    private final ProfileStore profiles;
    private final UserStore users;
    private final LongSupplier clock;
    private final Random random;
    private final long everyMs;
    private volatile NotificationStore notes;

    private static final String[] REACTIONS = {
        "Love this! \uD83D\uDE0D", "Wow, great post.", "This made my day!", "So true \uD83D\uDE02",
        "Nice one! Where was this?", "Totally agree.", "Beautiful \u2728", "Keep them coming!",
    };

    public void setNotifications(NotificationStore n) {
        this.notes = n;
    }

    public WallService(WallStore wall, ProfileStore profiles, UserStore users, LongSupplier clock, Random random,
                       long everyMs) {
        this.wall = wall;
        this.profiles = profiles;
        this.users = users;
        this.clock = clock;
        this.random = random;
        this.everyMs = everyMs;
    }

    /** Runs every minute. Returns how many test posts were written. */
    public synchronized int tick() {
        for (String a : wall.authors()) {
            if (users.info(a) == null) {
                wall.deleteAllBy(a);
            }
        }
        NotificationStore n = notes;
        if (n != null) {
            for (String person : n.people()) {
                if (users.info(person) == null) {
                    n.removeUser(person);
                }
            }
        }
        int made = 0;
        if (wall.settings().testPostsOn && !profiles.testSuspended()) {
            List<ProfileStore.Profile> people = profiles.testPeople();
            for (ProfileStore.Profile p : people) {
                if (wall.testCountBy(p.username) == 0 && users.info(p.username) != null) {
                    made += postFor(p) ? 1 : 0;
                }
            }
            react(people);
            if (made == 0 && !people.isEmpty() && clock.getAsLong() - wall.lastTestPost() >= everyMs) {
                ProfileStore.Profile p = people.get(random.nextInt(people.size()));
                if (users.info(p.username) != null) {
                    made += postFor(p) ? 1 : 0;
                }
            }
        }
        wall.flushIfDirty();
        return made;
    }

    /** A test person likes and comments on the newest public post by a real person they have not reacted to (one per minute). */
    private void react(List<ProfileStore.Profile> people) {
        if (people.isEmpty()) {
            return;
        }
        java.util.Set<String> testNames = new java.util.HashSet<String>();
        for (ProfileStore.Profile p : people) {
            testNames.add(p.username.toLowerCase(java.util.Locale.ROOT));
        }
        long now = clock.getAsLong();
        List<WallStore.Post> recent = wall.recentPublic(now - 2L * 24 * 3600000L, testNames);
        java.util.Collections.reverse(recent);
        for (WallStore.Post post : recent) {
            if (now - post.created < 2 * 60000L || wall.touchedBy(post.id, testNames)) {
                continue;
            }
            ProfileStore.Profile who = people.get(random.nextInt(people.size()));
            if (users.info(who.username) == null) {
                return;
            }
            wall.setLike(post.id, who.username, true);
            String text = REACTIONS[random.nextInt(REACTIONS.length)];
            WallStore.Comment c = wall.addComment(post.id, 0, who.username, text, now);
            NotificationStore ns = notes;
            if (ns != null) {
                ns.add(post.author, NotificationStore.LIKE, who.username, post.id, 0, post.text, now);
                if (c != null) {
                    ns.add(post.author, NotificationStore.COMMENT, who.username, post.id, c.id, text, now);
                }
                ns.flushIfDirty();
            }
            return;
        }
    }

    /** One invented post by a test person; about 6 in 10 come with a drawn picture. */
    boolean postFor(ProfileStore.Profile p) {
        String hobby = p.hobbies.isEmpty() ? "music" : p.hobbies.get(random.nextInt(p.hobbies.size()));
        String study = p.education.isEmpty() ? "school" : p.education.split(",")[0];
        String text = STORIES[random.nextInt(STORIES.length)]
                .replace("{hobby}", hobby).replace("{city}", p.city.isEmpty() ? "my city" : p.city)
                .replace("{country}", p.country.isEmpty() ? "your country" : p.country).replace("{study}", study);
        byte[] img = null;
        if (random.nextInt(10) < 6) {
            img = ProfileArt.scene(random.nextLong(), p.country, p.city);
        }
        try {
            wall.create(p.username, text, img, "png", random.nextInt(5) == 0, true, clock.getAsLong());
            return true;
        } catch (IOException e) {
            System.out.println("[wall] could not save a test post: " + e.getMessage());
            return false;
        }
    }
}
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Profiles: names, stage name, place, education, hobbies and friends, for real and test (profiler-made)
 * accounts, plus account-deletion requests to the admin. Saved to a text file (profiles.txt). The file also holds
 * the passwords the profiler gave its test accounts, so the admin can sign in as them: keep it private.
 */
public final class ProfileStore {

    public static final int MAX_NAME = 40;
    public static final int MAX_PLACE = 60;
    public static final int MAX_EDUCATION = 120;
    public static final int MAX_HOBBY = 30;
    public static final int MAX_HOBBIES = 10;
    public static final int MAX_FRIENDS = 200;
    public static final int MAX_REASON = 300;

    /** A copy of one profile; changes to it are not saved. */
    public static final class Profile {
        public String username;
        public String firstName = "";
        public String lastName = "";
        public String stageName = "";
        public String city = "";
        public String country = "";
        public String education = "";
        public List<String> hobbies = new ArrayList<String>();
        public List<String> friends = new ArrayList<String>();
        public boolean test;
        public String testPassword = "";
        public long created;
        public long seed;
        /** "", "pending" or "declined". */
        public String deletion = "";
        public long deletionAt;
        public String deletionReason = "";
        public String deletionNote = "";
        /** File name of the person's own photo in the photo folder ("" = use the drawn pictures). */
        public String photo = "";
        public long photoAt;
        /** The colour the person chose for their card ("#rrggbb"), or "" for the automatic colour. */
        public String color = "";

        Profile copy() {
            Profile p = new Profile();
            p.username = username;
            p.firstName = firstName;
            p.lastName = lastName;
            p.stageName = stageName;
            p.city = city;
            p.country = country;
            p.education = education;
            p.hobbies = new ArrayList<String>(hobbies);
            p.friends = new ArrayList<String>(friends);
            p.test = test;
            p.testPassword = testPassword;
            p.created = created;
            p.seed = seed;
            p.deletion = deletion;
            p.deletionAt = deletionAt;
            p.deletionReason = deletionReason;
            p.deletionNote = deletionNote;
            p.photo = photo;
            p.photoAt = photoAt;
            p.color = color;
            return p;
        }
    }

    private final Path file;
    private final Path photoDir;
    private final Map<String, Profile> profiles = new LinkedHashMap<String, Profile>();
    private boolean profilerOn = true;
    /** Test people switched OFF: their accounts are disabled (hidden, cannot sign in) but nothing is deleted. */
    private boolean testSuspended = false;
    private long lastGenerated = 0;
    private boolean dirty = false;

    public ProfileStore(Path file) {
        this(file, file.resolveSibling("profile-photos"));
    }

    public ProfileStore(Path file, Path photoDir) {
        this.file = file;
        this.photoDir = photoDir;
    }

    // ---- Photos ----

    /** Saves a person's own photo (already checked and cropped, JPEG). Replaces the old one. */
    public synchronized void setPhoto(String username, byte[] jpeg, long now) throws IOException {
        Files.createDirectories(photoDir);
        Profile p = getOrCreate(username, now);
        byte[] r = new byte[6];
        new java.security.SecureRandom().nextBytes(r);
        StringBuilder name = new StringBuilder(key(username)).append('-');
        for (byte x : r) {
            name.append(String.format("%02x", x));
        }
        name.append(".jpg");
        Files.write(photoDir.resolve(name.toString()), jpeg);
        deletePhotoFile(p.photo);
        p.photo = name.toString();
        p.photoAt = now;
        dirty = true;
    }

    public synchronized boolean clearPhoto(String username) {
        Profile p = profiles.get(key(username));
        if (p == null || p.photo.isEmpty()) {
            return false;
        }
        deletePhotoFile(p.photo);
        p.photo = "";
        p.photoAt = 0;
        dirty = true;
        return true;
    }

    // ---- Card colour ----

    /** Sets the person's card colour ("#rrggbb", already checked) or "" for the automatic colour. */
    public synchronized void setColor(String username, String color, long now) {
        Profile p = getOrCreate(username, now);
        String c = color == null ? "" : color.toLowerCase(java.util.Locale.ROOT);
        if (!c.equals(p.color)) {
            p.color = c;
            dirty = true;
        }
    }

    /** "#rrggbb" if it is a colour white writing can be read on, else null. */
    public static String checkColor(String s) {
        if (s == null || !s.matches("#[0-9a-fA-F]{6}")) {
            return null;
        }
        int rgb = Integer.parseInt(s.substring(1), 16);
        double lum = 0.2126 * lin((rgb >> 16) & 0xff) + 0.7152 * lin((rgb >> 8) & 0xff) + 0.0722 * lin(rgb & 0xff);
        // white text needs a contrast of at least 3:1 (big, bold writing)
        return (1.05 / (lum + 0.05)) >= 3.0 ? s.toLowerCase(java.util.Locale.ROOT) : null;
    }

    private static double lin(int c) {
        double v = c / 255.0;
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    /** The automatic card colour: a shade worked out from the username (the same as the app always used). */
    public static String autoColor(String username) {
        int hue = 210;
        try {
            byte[] d = java.security.MessageDigest.getInstance("MD5")
                    .digest((username == null ? "" : username.toLowerCase(java.util.Locale.ROOT))
                            .getBytes(StandardCharsets.UTF_8));
            hue = (((d[0] & 0xff) << 8) | (d[1] & 0xff)) % 360;
        } catch (java.security.NoSuchAlgorithmException e) {
            // keep the default
        }
        int rgb = java.awt.Color.HSBtoRGB(hue / 360f, 0.50f, 0.48f) & 0xffffff;
        return String.format("#%06x", rgb);
    }

    /** The colour to draw a person's card in: their choice, or the automatic one. */
    public synchronized String cardColor(String username) {
        Profile p = profiles.get(key(username));
        return p != null && !p.color.isEmpty() ? p.color : autoColor(username);
    }

    /** The person's own photo, or null. */
    public synchronized byte[] photo(String username) {
        Profile p = profiles.get(key(username));
        if (p == null || p.photo.isEmpty()) {
            return null;
        }
        try {
            return Files.readAllBytes(photoDir.resolve(p.photo));
        } catch (IOException e) {
            return null;
        }
    }

    /** People with their own photo, newest first. */
    public synchronized List<Profile> withPhotos() {
        List<Profile> out = new ArrayList<Profile>();
        for (Profile p : profiles.values()) {
            if (!p.photo.isEmpty()) {
                out.add(p.copy());
            }
        }
        java.util.Collections.sort(out, (x, y) -> Long.compare(y.photoAt, x.photoAt));
        return out;
    }

    private void deletePhotoFile(String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        try {
            Files.deleteIfExists(photoDir.resolve(name));
        } catch (IOException e) {
            // left behind; harmless
        }
    }

    public synchronized void init() throws IOException {
        profiles.clear();
        profilerOn = true;
        testSuspended = false;
        lastGenerated = 0;
        if (Files.exists(file)) {
            load();
        }
    }

    static String key(String username) {
        return username == null ? "" : username.toLowerCase(Locale.ROOT);
    }

    /** Removes tabs, line breaks, other control characters and '|' (used in the file), trims and shortens. */
    public static String clean(String s, int max) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < 0x20 || ch == 0x7F) {
                sb.append(' ');
            } else if (ch == '|') {
                sb.append('/');
            } else {
                sb.append(ch);
            }
        }
        String t = sb.toString().trim().replaceAll(" {2,}", " ");
        return t.length() > max ? t.substring(0, max).trim() : t;
    }

    // ---- Reading ----

    public synchronized Profile get(String username) {
        Profile p = profiles.get(key(username));
        return p == null ? null : p.copy();
    }

    public synchronized List<Profile> all() {
        List<Profile> out = new ArrayList<Profile>();
        for (Profile p : profiles.values()) {
            out.add(p.copy());
        }
        return out;
    }

    public synchronized List<Profile> testPeople() {
        List<Profile> out = new ArrayList<Profile>();
        for (Profile p : profiles.values()) {
            if (p.test) {
                out.add(p.copy());
            }
        }
        return out;
    }

    public synchronized int testCount() {
        int n = 0;
        for (Profile p : profiles.values()) {
            if (p.test) {
                n++;
            }
        }
        return n;
    }

    public synchronized List<Profile> deletionRequests() {
        List<Profile> out = new ArrayList<Profile>();
        for (Profile p : profiles.values()) {
            if ("pending".equals(p.deletion)) {
                out.add(p.copy());
            }
        }
        return out;
    }

    public synchronized boolean profilerOn() {
        return profilerOn;
    }

    public synchronized long lastGenerated() {
        return lastGenerated;
    }

    // ---- Changing ----

    private Profile getOrCreate(String username, long now) {
        String k = key(username);
        Profile p = profiles.get(k);
        if (p == null) {
            p = new Profile();
            p.username = username;
            p.created = now;
            p.seed = username.toLowerCase(Locale.ROOT).hashCode() * 2654435761L;
            profiles.put(k, p);
        }
        return p;
    }

    /** Adds a profile made by the profiler. */
    public synchronized void addTestPerson(String username, String password, PeopleGenerator.Person person, long now) {
        Profile p = getOrCreate(username, now);
        p.firstName = clean(person.firstName, MAX_NAME);
        p.lastName = clean(person.lastName, MAX_NAME);
        p.stageName = clean(person.stageName, MAX_NAME);
        p.city = clean(person.city, MAX_PLACE);
        p.country = clean(person.country, MAX_PLACE);
        p.education = clean(person.education, MAX_EDUCATION);
        p.hobbies = new ArrayList<String>();
        for (String h : person.hobbies) {
            p.hobbies.add(clean(h, MAX_HOBBY));
        }
        p.test = true;
        p.testPassword = password;
        p.seed = person.seed;
        p.created = now;
        lastGenerated = now;
        dirty = true;
    }

    /** Saves what a person typed into their profile. Returns the saved copy. */
    public synchronized Profile edit(String username, String first, String last, String stage, String city,
                                     String country, String education, List<String> hobbies, long now) {
        Profile p = getOrCreate(username, now);
        if (first != null) {
            p.firstName = clean(first, MAX_NAME);
        }
        if (last != null) {
            p.lastName = clean(last, MAX_NAME);
        }
        if (stage != null) {
            p.stageName = clean(stage, MAX_NAME);
        }
        if (city != null) {
            p.city = clean(city, MAX_PLACE);
        }
        if (country != null) {
            p.country = clean(country, MAX_PLACE);
        }
        if (education != null) {
            p.education = clean(education, MAX_EDUCATION);
        }
        if (hobbies != null) {
            List<String> hs = new ArrayList<String>();
            for (String h : hobbies) {
                String c = clean(h, MAX_HOBBY);
                if (!c.isEmpty() && !hs.contains(c) && hs.size() < MAX_HOBBIES) {
                    hs.add(c);
                }
            }
            p.hobbies = hs;
        }
        dirty = true;
        return p.copy();
    }

    /** Adds a friend (one way). Returns false if they were already a friend. */
    public synchronized boolean addFriend(String username, String friend, long now) {
        Profile p = getOrCreate(username, now);
        for (String f : p.friends) {
            if (f.equalsIgnoreCase(friend)) {
                return false;
            }
        }
        if (p.friends.size() >= MAX_FRIENDS) {
            throw new IllegalStateException("you can have up to " + MAX_FRIENDS + " friends");
        }
        p.friends.add(friend);
        dirty = true;
        return true;
    }

    public synchronized boolean removeFriend(String username, String friend) {
        Profile p = profiles.get(key(username));
        if (p == null) {
            return false;
        }
        for (int i = 0; i < p.friends.size(); i++) {
            if (p.friends.get(i).equalsIgnoreCase(friend)) {
                p.friends.remove(i);
                dirty = true;
                return true;
            }
        }
        return false;
    }

    public synchronized void requestDeletion(String username, String reason, long now) {
        Profile p = getOrCreate(username, now);
        p.deletion = "pending";
        p.deletionAt = now;
        p.deletionReason = clean(reason, MAX_REASON);
        p.deletionNote = "";
        dirty = true;
    }

    public synchronized boolean cancelDeletion(String username) {
        Profile p = profiles.get(key(username));
        if (p == null || !"pending".equals(p.deletion)) {
            return false;
        }
        p.deletion = "";
        p.deletionReason = "";
        dirty = true;
        return true;
    }

    public synchronized boolean declineDeletion(String username, String note) {
        Profile p = profiles.get(key(username));
        if (p == null || !"pending".equals(p.deletion)) {
            return false;
        }
        p.deletion = "declined";
        p.deletionNote = clean(note, MAX_REASON);
        dirty = true;
        return true;
    }

    /** Forgets a person: their profile and their place in everyone's friend list. */
    /** The member's username changed: the profile (and friends' lists) follow. */
    public synchronized void rename(String from, String to) {
        Profile p = profiles.remove(key(from));
        if (p != null) {
            p.username = to;
            profiles.put(key(to), p);
        }
        for (Profile x : profiles.values()) {
            for (int i = 0; i < x.friends.size(); i++) {
                if (x.friends.get(i).equalsIgnoreCase(from)) {
                    x.friends.set(i, to);
                }
            }
        }
        dirty = true;
    }

    public synchronized void remove(String username) {
        Profile gone = profiles.remove(key(username));
        if (gone != null) {
            deletePhotoFile(gone.photo);
            dirty = true;
        }
        for (Profile p : profiles.values()) {
            for (int i = p.friends.size() - 1; i >= 0; i--) {
                if (p.friends.get(i).equalsIgnoreCase(username)) {
                    p.friends.remove(i);
                    dirty = true;
                }
            }
        }
    }

    public synchronized boolean testSuspended() {
        return testSuspended;
    }

    public synchronized void setTestSuspended(boolean suspended) {
        if (testSuspended != suspended) {
            testSuspended = suspended;
            dirty = true;
        }
    }

    public synchronized void setProfilerOn(boolean on) {
        if (profilerOn != on) {
            profilerOn = on;
            dirty = true;
        }
    }

    // ---- Storage ----

    private static String list(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(s);
        }
        return sb.toString();
    }

    private static List<String> unlist(String s) {
        List<String> out = new ArrayList<String>();
        if (s != null && !s.isEmpty()) {
            out.addAll(Arrays.asList(s.split("\\|")));
        }
        return out;
    }

    private void load() throws IOException {
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            try {
                if ("E".equals(f[0])) {
                    profilerOn = "1".equals(f[1]);
                } else if ("S".equals(f[0])) {
                    testSuspended = "1".equals(f[1]);
                } else if ("G".equals(f[0])) {
                    lastGenerated = Long.parseLong(f[1]);
                } else if ("P".equals(f[0]) && f.length >= 19) {
                    Profile p = new Profile();
                    p.username = f[1];
                    p.firstName = f[2];
                    p.lastName = f[3];
                    p.stageName = f[4];
                    p.city = f[5];
                    p.country = f[6];
                    p.education = f[7];
                    p.hobbies = unlist(f[8]);
                    p.friends = unlist(f[9]);
                    p.test = "1".equals(f[10]);
                    p.testPassword = f[11];
                    p.created = Long.parseLong(f[12]);
                    p.seed = Long.parseLong(f[13]);
                    p.deletion = f[14];
                    p.deletionAt = Long.parseLong(f[15]);
                    p.deletionReason = f[16];
                    p.deletionNote = f[17];
                    if (f.length >= 21 && !"end".equals(f[18])) {
                        p.photo = f[18];
                        p.photoAt = Long.parseLong(f[19]);
                    }
                    if (f.length >= 22 && !"end".equals(f[20]) && checkColor(f[20]) != null) {
                        p.color = f[20].toLowerCase(java.util.Locale.ROOT);
                    }
                    profiles.put(key(p.username), p);
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
            System.out.println("[profiles] could not save: " + e.getMessage());
        }
    }

    private void save() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# VStream profiles - keep private (holds the test accounts' passwords)\n");
        sb.append("E\t").append(profilerOn ? "1" : "0").append('\n');
        sb.append("S\t").append(testSuspended ? "1" : "0").append('\n');
        sb.append("G\t").append(lastGenerated).append('\n');
        for (Profile p : profiles.values()) {
            sb.append("P\t").append(p.username).append('\t').append(p.firstName).append('\t').append(p.lastName)
                    .append('\t').append(p.stageName).append('\t').append(p.city).append('\t').append(p.country)
                    .append('\t').append(p.education).append('\t').append(list(p.hobbies)).append('\t')
                    .append(list(p.friends)).append('\t').append(p.test ? "1" : "0").append('\t')
                    .append(p.testPassword).append('\t').append(p.created).append('\t').append(p.seed).append('\t')
                    .append(p.deletion).append('\t').append(p.deletionAt).append('\t').append(p.deletionReason)
                    .append('\t').append(p.deletionNote).append('\t').append(p.photo).append('\t').append(p.photoAt)
                    .append('\t').append(p.color).append("\tend\n");
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

    /** Names (lower case) of everyone with a profile. */
    public synchronized Set<String> keys() {
        return new LinkedHashSet<String>(profiles.keySet());
    }
}
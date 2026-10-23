package com.vstream.Vidiserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Accounts: admin, broadcasters (each with a channel and a stream key) and chatters.
 * Stored in a text file with salted PBKDF2 password hashes. Never log or list the hashes or keys.
 */
public final class UserStore {

    public enum Role { ADMIN, BROADCASTER, CHATTER, GUEST }

    public static final class UserException extends Exception {
        private static final long serialVersionUID = 1L;

        public UserException(String message) {
            super(message);
        }
    }

    /** A safe view of an account: no password hash and no key. */
    public static final class Info {
        public final String username;
        public final Role role;
        public final String channelId;
        public final boolean disabled;
        public final boolean hasPassword;

        Info(String username, Role role, String channelId, boolean disabled, boolean hasPassword) {
            this.username = username;
            this.role = role;
            this.channelId = channelId;
            this.disabled = disabled;
            this.hasPassword = hasPassword;
        }
    }

    private static final class User {
        final String username;
        final Role role;
        String salt;
        String hash;
        String channelId;
        String token;
        boolean disabled;

        User(String username, Role role) {
            this.username = username;
            this.role = role;
        }

        boolean hasPassword() {
            return hash != null && !hash.isEmpty() && salt != null && !salt.isEmpty();
        }
    }

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{3,24}");
    private static final int ITERATIONS = 120000;
    private static final byte[] DUMMY_SALT = new byte[16];
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String NONE = "-";
    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final Path file;
    private final LiveChannelRegistry registry;
    private final Consumer<String> onKick;
    private final Map<String, User> users = new LinkedHashMap<String, User>();
    private final Map<String, Long> lastActive = new java.util.HashMap<String, Long>();

    public UserStore(Path file, LiveChannelRegistry registry, Consumer<String> onKick) {
        this.file = file;
        this.registry = registry;
        this.onKick = onKick;
    }

    /**
     * Loads the user file, or creates it on the first start (an admin with a random password,
     * plus any stream keys found in the old streamkeys.txt). Returns the new admin password
     * on the first start, otherwise null. Registers every broadcaster's channel.
     */
    public synchronized String init(Path legacyKeys) throws IOException {
        String adminPassword = null;
        if (Files.exists(file)) {
            load();
        } else {
            adminPassword = randomPassword(14);
            User admin = new User("admin", Role.ADMIN);
            applyPassword(admin, adminPassword);
            users.put("admin", admin);
            importLegacy(legacyKeys);
            save();
        }
        for (User u : users.values()) {
            if (u.role == Role.BROADCASTER && u.channelId != null && u.token != null) {
                registry.registerChannelWithId(u.channelId, u.username,
                        StreamUrls.hlsPlayback(u.channelId), u.token);
                if (u.disabled) {
                    registry.removeChannel(u.channelId);
                }
            }
        }
        return adminPassword;
    }

    private void importLegacy(Path legacy) throws IOException {
        if (legacy == null || !Files.exists(legacy)) {
            return;
        }
        List<String> tokens = new ArrayList<String>();
        for (String line : Files.readAllLines(legacy, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.matches("[0-9a-f]{48}")) {
                tokens.add(t);
            }
        }
        for (int i = 0; i < tokens.size() && i < 20; i++) {
            User u = new User("broadcaster" + (i + 1), Role.BROADCASTER);
            u.channelId = String.format("CHANNEL-%03d", i + 1);
            u.token = tokens.get(i);
            users.put(u.username.toLowerCase(Locale.ROOT), u);
        }
        if (!tokens.isEmpty()) {
            System.out.println("[accounts] Imported " + Math.min(tokens.size(), 20)
                    + " existing stream keys as broadcaster1... (set their passwords in the admin page)");
        }
    }

    // ---- Queries ----

    public synchronized Info info(String username) {
        User u = find(username);
        return u == null ? null : info(u);
    }

    public synchronized List<Info> listInfos() {
        List<Info> result = new ArrayList<Info>();
        for (User u : users.values()) {
            result.add(info(u));
        }
        return result;
    }

    /** "CHANNEL-001?token=..." for a broadcaster, otherwise null. Treat the result as secret. */
    public synchronized String getStreamKey(String username) {
        User u = find(username);
        if (u == null || u.role != Role.BROADCASTER || u.channelId == null || u.token == null) {
            return null;
        }
        return u.channelId + "?token=" + u.token;
    }

    /** Returns the account if the password is right and the account is enabled, otherwise null. */
    public Info authenticate(String username, String password) {
        String salt = null;
        String stored = null;
        Info result = null;
        synchronized (this) {
            User u = find(username);
            if (u != null && u.hasPassword() && !u.disabled) {
                salt = u.salt;
                stored = u.hash;
                result = info(u);
            }
        }
        if (password == null) {
            return null;
        }
        if (result == null) {
            hash(password, DUMMY_SALT); // keep the timing similar for unknown names
            return null;
        }
        String candidate = hash(password, Base64.getDecoder().decode(salt));
        boolean same = MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8),
                stored.getBytes(StandardCharsets.UTF_8));
        if (same) {
            touchActive(username);
        }
        return same ? result : null;
    }

    // ---- Changes ----

    public synchronized Info createUser(String username, String password, Role role) throws UserException {
        checkUsername(username);
        checkPassword(password);
        if (role == null || role == Role.ADMIN) {
            throw new UserException("only broadcaster and chatter accounts can be created");
        }
        String key = username.toLowerCase(Locale.ROOT);
        if (users.containsKey(key)) {
            throw new UserException("that username is already taken");
        }
        User u = new User(username, role);
        applyPassword(u, password);
        if (role == Role.BROADCASTER) {
            String id = registry.registerChannel(username, "");
            LiveChannel channel = registry.getChannel(id);
            channel.setStreamAddress(StreamUrls.hlsPlayback(id));
            u.channelId = id;
            u.token = channel.getPublishToken();
        }
        users.put(key, u);
        lastActive.put(key, System.currentTimeMillis());
        try {
            save();
        } catch (IOException e) {
            users.remove(key);
            if (u.channelId != null) {
                registry.removeChannel(u.channelId);
            }
            throw new UserException("could not save the user list: " + e.getMessage());
        }
        return info(u);
    }

    public synchronized void setPassword(String username, String password) throws UserException {
        User u = require(username);
        checkPassword(password);
        applyPassword(u, password);
        saveOrThrow();
    }

    public synchronized void setDisabled(String username, boolean disabled) throws UserException {
        User u = require(username);
        if (u.role == Role.ADMIN) {
            throw new UserException("the admin account cannot be disabled");
        }
        if (u.disabled == disabled) {
            return;
        }
        u.disabled = disabled;
        saveOrThrow();
        if (u.role == Role.BROADCASTER && u.channelId != null) {
            if (disabled) {
                registry.removeChannel(u.channelId);
                onKick.accept(u.channelId);
            } else {
                registry.registerChannelWithId(u.channelId, u.username,
                        StreamUrls.hlsPlayback(u.channelId), u.token);
            }
        }
    }

    /** Makes a new stream key, kicks the current publisher and returns the new key. */
    public synchronized String rotateKey(String username) throws UserException {
        User u = require(username);
        if (u.role != Role.BROADCASTER || u.channelId == null) {
            throw new UserException("only broadcaster accounts have a stream key");
        }
        u.token = LiveChannelRegistry.newToken();
        saveOrThrow();
        LiveChannel channel = registry.getChannel(u.channelId);
        if (channel != null) {
            channel.setPublishToken(u.token);
        }
        onKick.accept(u.channelId);
        return u.channelId + "?token=" + u.token;
    }

    // ---- Helpers ----

    /** Remembers that the user was active now (used to remove idle guest accounts). */
    /** When the person last signed in or used the app (0 = not known). */
    public synchronized long lastActive(String username) {
        Long t = lastActive.get(username == null ? "" : username.toLowerCase(Locale.ROOT));
        return t == null ? 0 : t;
    }

    public synchronized void touchActive(String username) {
        if (username != null) {
            lastActive.put(username.toLowerCase(Locale.ROOT), System.currentTimeMillis());
        }
    }

    private long lastActiveOf(User u) {
        Long t = lastActive.get(u.username.toLowerCase(Locale.ROOT));
        return t == null ? System.currentTimeMillis() : t;
    }

    /** Removes guest accounts that were not active since (now - maxIdleMs). Returns their names. */
    public synchronized List<String> purgeIdleGuests(long now, long maxIdleMs) {
        List<String> removed = new ArrayList<String>();
        java.util.Iterator<Map.Entry<String, User>> it = users.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, User> e = it.next();
            User u = e.getValue();
            if (u.role != Role.GUEST) {
                continue;
            }
            Long t = lastActive.get(e.getKey());
            if (t != null && now - t > maxIdleMs) {
                removed.add(u.username);
                it.remove();
                lastActive.remove(e.getKey());
            }
        }
        if (!removed.isEmpty()) {
            flush();
        }
        return removed;
    }

    /** Writes the user file (activity times included). */
    public synchronized void flush() {
        try {
            save();
        } catch (IOException e) {
            System.out.println("[accounts] could not save: " + e.getMessage());
        }
    }

    /** Deletes a registered or guest account for good. Broadcaster and admin accounts cannot be deleted. */
    public synchronized boolean deleteAccount(String username) throws UserException {
        User u = find(username);
        if (u == null) {
            return false;
        }
        if (u.role == Role.ADMIN || u.role == Role.BROADCASTER) {
            throw new UserException("this kind of account cannot be deleted");
        }
        String key = u.username.toLowerCase(Locale.ROOT);
        users.remove(key);
        lastActive.remove(key);
        saveOrThrow();
        return true;
    }

    private User find(String username) {
        return username == null ? null : users.get(username.toLowerCase(Locale.ROOT));
    }

    private User require(String username) throws UserException {
        User u = find(username);
        if (u == null) {
            throw new UserException("no such user");
        }
        return u;
    }

    private static Info info(User u) {
        return new Info(u.username, u.role, u.channelId, u.disabled, u.hasPassword());
    }

    /** Checks a new username and password the same way createUser does, without creating anything. */
    public static void checkNew(String username, String password) throws UserException {
        checkUsername(username);
        checkPassword(password);
    }

    private static void checkUsername(String username) throws UserException {
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new UserException("username must be 3 to 24 letters, numbers or _");
        }
    }

    private static void checkPassword(String password) throws UserException {
        if (password == null || password.length() < 8 || password.length() > 128) {
            throw new UserException("password must be 8 to 128 characters");
        }
    }

    private static void applyPassword(User u, String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        u.salt = Base64.getEncoder().encodeToString(salt);
        u.hash = hash(password, salt);
    }

    private static String hash(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256);
            byte[] h = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            spec.clearPassword();
            return Base64.getEncoder().encodeToString(h);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String randomPassword(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    // ---- Storage ----

    private void load() throws IOException {
        users.clear();
        lastActive.clear();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] p = line.split("\t", -1);
            if (p.length < 7) {
                continue;
            }
            Role role;
            try {
                role = Role.valueOf(p[1]);
            } catch (IllegalArgumentException e) {
                continue;
            }
            User u = new User(p[0], role);
            u.salt = none(p[2]);
            u.hash = none(p[3]);
            u.channelId = none(p[4]);
            u.token = none(p[5]);
            u.disabled = "1".equals(p[6]);
            long seen = System.currentTimeMillis();
            if (p.length >= 8) {
                try {
                    seen = Long.parseLong(p[7]);
                } catch (NumberFormatException e) {
                    seen = System.currentTimeMillis();
                }
            }
            lastActive.put(p[0].toLowerCase(Locale.ROOT), seen);
            users.put(p[0].toLowerCase(Locale.ROOT), u);
        }
    }

    private static String none(String s) {
        return NONE.equals(s) ? null : s;
    }

    private static String orNone(String s) {
        return s == null || s.isEmpty() ? NONE : s;
    }

    private void saveOrThrow() throws UserException {
        try {
            save();
        } catch (IOException e) {
            throw new UserException("could not save the user list: " + e.getMessage());
        }
    }

    private void save() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# VStream users - keep private. username, role, salt, hash, channel, key, disabled\n");
        for (User u : users.values()) {
            sb.append(u.username).append('\t').append(u.role.name()).append('\t')
                    .append(orNone(u.salt)).append('\t').append(orNone(u.hash)).append('\t')
                    .append(orNone(u.channelId)).append('\t').append(orNone(u.token)).append('\t')
                    .append(u.disabled ? "1" : "0").append('\t').append(lastActiveOf(u)).append('\n');
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
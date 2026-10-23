package com.vstream.Vidiserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Email addresses of accounts, and 6-digit codes that prove an address belongs to the person.
 *   New accounts: startSignup() keeps the sign-up waiting (in memory, 30 minutes) and emails a code;
 *   finishSignup() creates the account only when the right code comes back.
 *   Existing accounts: startChange() emails a code to a new address; confirmChange() saves it as verified.
 * One verified address belongs to one account. Saved in emails.txt (username, address, verified, when).
 */
public final class EmailService {

    public static final long CODE_MS = 30 * 60000L;
    private static final long RESEND_GAP_MS = 45000L;
    private static final int MAX_SENDS = 5;
    private static final int MAX_TRIES = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A problem to show the person (with an HTTP status). */
    public static final class EmailException extends Exception {
        private static final long serialVersionUID = 1L;
        public final int status;

        public EmailException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    public static final class Entry {
        public final String username;
        public final String email;
        public final boolean verified;
        public final long at;

        Entry(String username, String email, boolean verified, long at) {
            this.username = username;
            this.email = email;
            this.verified = verified;
            this.at = at;
        }
    }

    private static final class Pending {
        String username;
        String password;
        UserStore.Role role;
        String email;
        String code;
        long expires;
        int tries;
        int sends;
        long lastSend;
    }

    private final Path file;
    private final UserStore users;
    private final Mailer mailer;
    private final LongSupplier clock;
    private final Map<String, Entry> entries = new LinkedHashMap<String, Entry>();
    private final Map<String, Pending> signups = new HashMap<String, Pending>();
    private final Map<String, Pending> changes = new HashMap<String, Pending>();
    private boolean dirty = false;

    public EmailService(Path file, UserStore users, Mailer mailer, LongSupplier clock) {
        this.file = file;
        this.users = users;
        this.mailer = mailer;
        this.clock = clock;
    }

    private static String key(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    /** "m*****@gmail.com" - enough for the person to recognise their address. */
    public static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return email;
        }
        String name = email.substring(0, at);
        String shown = name.length() <= 2 ? name.substring(0, 1) : name.substring(0, 2);
        StringBuilder sb = new StringBuilder(shown);
        for (int i = shown.length(); i < Math.min(name.length(), 8); i++) {
            sb.append('*');
        }
        return sb.append(email.substring(at)).toString();
    }

    // ---- Saved addresses ----

    public synchronized Entry get(String username) {
        return entries.get(key(username));
    }

    /** The account that has verified this address, or null (deleted accounts do not count). */
    public synchronized String ownerOf(String email) {
        String e = key(email);
        for (Entry x : entries.values()) {
            if (x.verified && x.email.equalsIgnoreCase(e) && users.info(x.username) != null) {
                return x.username;
            }
        }
        return null;
    }

    public synchronized int verifiedCount() {
        int n = 0;
        for (Entry x : entries.values()) {
            if (x.verified) {
                n++;
            }
        }
        return n;
    }

    private void save(String username, String email, boolean verified) {
        entries.put(key(username), new Entry(username, email.trim().toLowerCase(Locale.ROOT), verified, clock.getAsLong()));
        dirty = true;
    }

    public synchronized void remove(String username) {
        if (entries.remove(key(username)) != null) {
            dirty = true;
        }
    }

    // ---- New accounts ----

    /** Checks everything, keeps the sign-up waiting and emails the code. Returns the masked address. */
    /** The admin sets an account's email (counted as confirmed) - for accounts the admin makes, e.g. a radio station's. */
    public synchronized void adminSet(String username, String email) throws EmailException {
        String mail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (!Mailer.validAddress(mail)) {
            throw new EmailException(400, "enter a valid email address");
        }
        String owner = ownerOf(mail);
        if (owner != null && !owner.equalsIgnoreCase(username)) {
            throw new EmailException(400, "another account (" + owner + ") already uses that email address");
        }
        save(username, mail, true);
        flushIfDirty();
    }

    public String startSignup(String username, String password, String email) throws EmailException {
        String user = username == null ? "" : username.trim();
        String mail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        try {
            UserStore.checkNew(user, password);
        } catch (UserStore.UserException e) {
            throw new EmailException(400, e.getMessage());
        }
        if (!Mailer.validAddress(mail)) {
            throw new EmailException(400, "enter a valid email address");
        }
        Pending p;
        synchronized (this) {
            if (users.info(user) != null) {
                throw new EmailException(400, "that username is taken");
            }
            if (ownerOf(mail) != null) {
                throw new EmailException(400, "an account with that email address already exists - sign in instead");
            }
            long now = clock.getAsLong();
            purge(now);
            Pending old = signups.get(key(user));
            if (old != null && !old.email.equals(mail) && old.expires > now) {
                throw new EmailException(409, "someone is creating an account with that username right now - choose another");
            }
            if (old != null && now - old.lastSend < RESEND_GAP_MS) {
                throw new EmailException(429, "a code was just sent - check your email (and the spam folder)");
            }
            p = new Pending();
            p.username = user;
            p.password = password;
            p.role = UserStore.Role.CHATTER;
            p.email = mail;
            p.sends = old == null ? 0 : old.sends;
            if (p.sends >= MAX_SENDS) {
                throw new EmailException(429, "too many codes - wait 30 minutes and try again");
            }
            newCode(p, now);
            signups.put(key(user), p);
        }
        sendCode(p, "Welcome to Memoryq! Confirm your email address to finish creating your account");
        return mask(mail);
    }

    public String resendSignup(String username) throws EmailException {
        Pending p;
        synchronized (this) {
            long now = clock.getAsLong();
            purge(now);
            p = signups.get(key(username));
            if (p == null) {
                throw new EmailException(404, "that sign-up has expired - start again");
            }
            checkResend(p, now);
            newCode(p, now);
        }
        sendCode(p, "Here is your new code to finish creating your Memoryq account");
        return mask(p.email);
    }

    /** Creates the account when the code is right. */
    public synchronized UserStore.Info finishSignup(String username, String code) throws EmailException {
        long now = clock.getAsLong();
        purge(now);
        Pending p = signups.get(key(username));
        if (p == null) {
            throw new EmailException(404, "that sign-up has expired - start again");
        }
        check(p, code, signups);
        if (ownerOf(p.email) != null) {
            signups.remove(key(username));
            throw new EmailException(400, "an account with that email address already exists - sign in instead");
        }
        UserStore.Info info;
        try {
            info = users.createUser(p.username, p.password, p.role);
        } catch (UserStore.UserException e) {
            signups.remove(key(username));
            throw new EmailException(400, e.getMessage());
        }
        signups.remove(key(username));
        save(info.username, p.email, true);
        flushIfDirty();
        return info;
    }

    // ---- Existing accounts ----

    public String startChange(String username, String email) throws EmailException {
        String mail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (!Mailer.validAddress(mail)) {
            throw new EmailException(400, "enter a valid email address");
        }
        Pending p;
        synchronized (this) {
            String owner = ownerOf(mail);
            if (owner != null) {
                throw new EmailException(400, owner.equalsIgnoreCase(username)
                        ? "that address is already confirmed on your account" : "that email address is used by another account");
            }
            long now = clock.getAsLong();
            purge(now);
            Pending old = changes.get(key(username));
            if (old != null) {
                checkResend(old, now);
            }
            p = new Pending();
            p.username = username;
            p.email = mail;
            p.sends = old == null ? 0 : old.sends;
            newCode(p, now);
            changes.put(key(username), p);
        }
        sendCode(p, "Confirm this email address for your Memoryq account");
        return mask(mail);
    }

    public synchronized Entry confirmChange(String username, String code) throws EmailException {
        long now = clock.getAsLong();
        purge(now);
        Pending p = changes.get(key(username));
        if (p == null) {
            throw new EmailException(404, "the code has expired - ask for a new one");
        }
        check(p, code, changes);
        if (ownerOf(p.email) != null) {
            changes.remove(key(username));
            throw new EmailException(400, "that email address is used by another account");
        }
        changes.remove(key(username));
        save(username, p.email, true);
        flushIfDirty();
        return get(username);
    }

    /** The address waiting for its code on this account (masked), or "". */
    public synchronized String pendingChange(String username) {
        purge(clock.getAsLong());
        Pending p = changes.get(key(username));
        return p == null ? "" : mask(p.email);
    }

    // ---- Codes ----

    private void checkResend(Pending p, long now) throws EmailException {
        if (now - p.lastSend < RESEND_GAP_MS) {
            throw new EmailException(429, "a code was just sent - wait a minute before asking for another");
        }
        if (p.sends >= MAX_SENDS) {
            throw new EmailException(429, "too many codes - wait 30 minutes and try again");
        }
    }

    private static void newCode(Pending p, long now) {
        p.code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1000000));
        p.expires = now + CODE_MS;
        p.tries = 0;
        p.sends++;
        p.lastSend = now;
    }

    private void check(Pending p, String code, Map<String, Pending> from) throws EmailException {
        String c = code == null ? "" : code.replaceAll("[^0-9]", "");
        if (++p.tries > MAX_TRIES) {
            from.remove(key(p.username));
            throw new EmailException(429, "too many wrong codes - start again");
        }
        if (c.length() != 6 || !MessageDigest.isEqual(c.getBytes(StandardCharsets.US_ASCII),
                p.code.getBytes(StandardCharsets.US_ASCII))) {
            throw new EmailException(400, "that code is not right - check the newest email");
        }
    }

    private void purge(long now) {
        for (Map<String, Pending> m : java.util.Arrays.asList(signups, changes)) {
            Iterator<Pending> it = m.values().iterator();
            while (it.hasNext()) {
                if (it.next().expires <= now) {
                    it.remove();
                }
            }
        }
    }

    private void sendCode(Pending p, String intro) throws EmailException {
        String text = "Hi " + p.username + ",\n\n" + intro + ". Your code is:\n\n"
                + "    " + p.code + "\n\n"
                + "It works for 30 minutes. If you did not ask for this, you can ignore this email - "
                + "nothing changes without the code.\n\n"
                + "Memoryq\nhttps://memoryq.com\n";
        String err = mailer.send(p.email, "Your Memoryq code: " + p.code, text);
        if (err != null) {
            synchronized (this) {
                p.lastSend = 0;
            }
            throw new EmailException(503, err);
        }
    }

    // ---- Storage ----

    public synchronized void init() throws IOException {
        entries.clear();
        if (!Files.exists(file)) {
            return;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            try {
                if ("A".equals(f[0]) && f.length >= 5) {
                    entries.put(key(f[1]), new Entry(f[1], f[2], "1".equals(f[3]), Long.parseLong(f[4])));
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
        StringBuilder sb = new StringBuilder("# Memoryq account email addresses: username, address, verified, when\n");
        for (Entry e : entries.values()) {
            sb.append("A\t").append(e.username).append('\t').append(e.email).append('\t')
                    .append(e.verified ? "1" : "0").append('\t').append(e.at).append('\n');
        }
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, sb.toString().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (IOException e) {
            System.out.println("[email] could not save: " + e.getMessage());
        }
    }
}
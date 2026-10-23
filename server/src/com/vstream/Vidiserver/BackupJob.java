package com.vstream.Vidiserver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The nightly backup, to the backup disk (the other drive than the videos):
 *   data/memoryq-DATE.tar.gz  the server's data (accounts, stations, chats, settings, stories...) - the last 14 nights
 *   videos/                   a copy of every uploaded video and its picture
 *   radio-music/              a copy of every station's songs
 * A file that disappears from the server is kept in deleted/DATE/ for 14 days before it goes from the backup too.
 * Restore the data:  sudo systemctl stop vstream; cd /opt/vstream; sudo tar -xzf /srv/backup/data/memoryq-DATE.tar.gz;
 *                    sudo chown -R vstream:vstream /opt/vstream; sudo systemctl start vstream
 */
public final class BackupJob {

    static final int KEEP_DAYS = 14;
    /** Folders of the server that are not data (code, programs) or are copied on their own. */
    static final String[] NOT_IN_DATA = {"out", "out.new", "src", "tools", "mediamtx", "videos", "radio-music", "radio-import/media",
            "downloads", "backup-*", "*.tar.gz", "*.zip"};

    private final Path app;
    private final Path root;
    private final Path videos;
    private final Path music;
    private final LongSupplier clock;
    private ScheduledExecutorService timer;
    private volatile boolean running;
    private volatile long lastRun;
    private volatile long lastOk;
    private volatile String lastMessage = "no backup yet";

    public BackupJob(Path app, Path backupRoot, Path videos, Path music, LongSupplier clock) {
        this.app = app;
        this.root = backupRoot;
        this.videos = videos;
        this.music = music;
        this.clock = clock;
        try {
            Path f = root.resolve("last-backup.txt");
            if (Files.isRegularFile(f)) {
                String[] v = new String(Files.readAllBytes(f), StandardCharsets.UTF_8).trim().split("\t", 3);
                lastRun = Long.parseLong(v[0]);
                lastOk = Long.parseLong(v[1]);
                lastMessage = v.length > 2 ? v[2] : "";
            }
        } catch (IOException | RuntimeException e) {
            // no earlier backup known
        }
    }

    /** Whether the backup disk is there. */
    public boolean available() {
        return MediaDisks.usable(root);
    }

    public long lastRun() {
        return lastRun;
    }

    public long lastOk() {
        return lastOk;
    }

    public String lastMessage() {
        return lastMessage;
    }

    public boolean running() {
        return running;
    }

    /** Every night at 03:30 (server time). */
    public synchronized void start() {
        if (timer != null) {
            return;
        }
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "backup");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = now.toLocalDate().atTime(3, 30);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        long delay = java.time.Duration.between(now, next).toMillis();
        timer.scheduleAtFixedRate(this::run, delay, TimeUnit.DAYS.toMillis(1), TimeUnit.MILLISECONDS);
    }

    /** Starts a backup now (in the background). False when one is running or there is no backup disk. */
    public boolean runSoon() {
        if (running || !available()) {
            return false;
        }
        Thread t = new Thread(this::run, "backup-now");
        t.setDaemon(true);
        t.start();
        return true;
    }

    /** One backup; safe to call at any time (a second one at the same time does nothing). */
    public void run() {
        synchronized (this) {
            if (running) {
                return;
            }
            running = true;
        }
        long start = clock.getAsLong();
        List<String> notes = new ArrayList<String>();
        boolean ok = true;
        try {
            if (!available()) {
                throw new IOException("the backup disk (" + root + ") is not there");
            }
            String day = LocalDate.now().toString();
            // 1. the data
            Path data = root.resolve("data");
            Files.createDirectories(data);
            Path tar = data.resolve("memoryq-" + day + ".tar.gz");
            Path part = data.resolve("memoryq-" + day + ".tar.gz.part");
            List<String> cmd = new ArrayList<String>();
            Collections.addAll(cmd, "tar", "-czf", part.toAbsolutePath().toString());
            for (String x : NOT_IN_DATA) {
                cmd.add("--exclude=./" + x);
            }
            Collections.addAll(cmd, "-C", app.toAbsolutePath().toString(), ".");
            String err = run(cmd);
            if (err != null || !Files.isRegularFile(part) || Files.size(part) < 100) {
                Files.deleteIfExists(part);
                throw new IOException("the data could not be packed" + (err == null ? "" : ": " + err));
            }
            Files.move(part, tar, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.setPosixFilePermissions(tar, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException | IOException e) {
                // not a Linux disk
            }
            notes.add("data " + MediaDisks.gb(Files.size(tar)));
            int old = 0;
            List<Path> tars = new ArrayList<Path>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(data, "memoryq-*.tar.gz")) {
                for (Path p : ds) {
                    tars.add(p);
                }
            }
            Collections.sort(tars);
            for (int i = 0; i < tars.size() - KEEP_DAYS; i++) {
                Files.deleteIfExists(tars.get(i));
                old++;
            }
            if (old > 0) {
                notes.add(old + " old data backup(s) removed");
            }
            // 2. the videos and the songs
            for (Object[] m : new Object[][] {{videos, "videos", Boolean.FALSE}, {music, "radio-music", Boolean.TRUE}}) {
                Path from = (Path) m[0];
                if (from == null || !Files.isDirectory(from)) {
                    continue;
                }
                long[] r = mirror(from, root.resolve((String) m[1]), (Boolean) m[2], day);
                if (r[3] > 0) {
                    ok = false;
                    notes.add(m[1] + ": NOT copied - the backup disk is too full (" + MediaDisks.gb(r[3]) + " more needed)");
                } else {
                    notes.add(m[1] + " " + r[0] + " new" + (r[1] > 0 ? ", " + r[1] + " set aside (gone from the server)" : ""));
                }
            }
            purgeDeleted();
        } catch (IOException | RuntimeException e) {
            ok = false;
            notes.add(0, (available() ? "FAILED: " : "skipped: ") + (e.getMessage() == null ? e.toString() : e.getMessage()));
        } finally {
            lastRun = clock.getAsLong();
            if (ok) {
                lastOk = lastRun;
            }
            lastMessage = String.join(", ", notes) + " (" + Math.max(1, (lastRun - start) / 1000) + " s)";
            try {
                if (available()) {
                    Files.write(root.resolve("last-backup.txt"), (lastRun + "\t" + lastOk + "\t" + lastMessage).getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                // shown in the admin anyway
            }
            System.out.println("[backup] " + lastMessage);
            running = false;
        }
    }

    /**
     * Copies new and changed files of from into to; files gone from from are moved to deleted/DAY/. Returns
     * {copied, set aside, bytes copied, bytes missing when the disk was too full}.
     */
    long[] mirror(Path from, Path to, boolean deep, String day) throws IOException {
        long[] r = new long[4];
        List<Path> need = new ArrayList<Path>();
        long needBytes = 0;
        for (Path f : files(from, deep)) {
            Path t = to.resolve(from.relativize(f).toString());
            if (!Files.isRegularFile(t) || Files.size(t) != Files.size(f)) {
                need.add(f);
                needBytes += Files.size(f);
            }
        }
        long[] space = MediaDisks.space(root);
        if (space != null && needBytes > space[1] - 1073741824L) {
            r[3] = needBytes - (space[1] - 1073741824L);
            return r;
        }
        for (Path f : need) {
            Path t = to.resolve(from.relativize(f).toString());
            Files.createDirectories(t.getParent());
            Path tmp = t.resolveSibling(t.getFileName() + ".part");
            Files.copy(f, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, t, StandardCopyOption.REPLACE_EXISTING);
            r[0]++;
            r[2] += Files.size(t);
        }
        if (Files.isDirectory(to)) {
            for (Path t : files(to, deep)) {
                Path f = from.resolve(to.relativize(t).toString());
                if (!Files.exists(f)) {
                    Path d = root.resolve("deleted").resolve(day).resolve(root.relativize(t).toString());
                    Files.createDirectories(d.getParent());
                    Files.move(t, d, StandardCopyOption.REPLACE_EXISTING);
                    r[1]++;
                }
            }
        }
        return r;
    }

    private static List<Path> files(Path dir, boolean deep) throws IOException {
        final List<Path> out = new ArrayList<Path>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        Files.walkFileTree(dir, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), deep ? Integer.MAX_VALUE : 1,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                        String n = f.getFileName().toString();
                        if (a.isRegularFile() && !n.endsWith(".part") && !n.contains(".part.") && !n.endsWith(".moving")) {
                            out.add(f);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                        return d.getFileName() != null && d.getFileName().toString().equals("tmp") && !d.equals(dir)
                                ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                    }
                });
        return out;
    }

    /** Set-aside files older than 14 days go for good. */
    private void purgeDeleted() throws IOException {
        Path del = root.resolve("deleted");
        if (!Files.isDirectory(del)) {
            return;
        }
        LocalDate limit = LocalDate.now().minusDays(KEEP_DAYS);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(del)) {
            for (Path d : ds) {
                try {
                    if (LocalDate.parse(d.getFileName().toString()).isBefore(limit)) {
                        deleteTree(d);
                    }
                } catch (RuntimeException e) {
                    // not a day folder
                }
            }
        }
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        Files.walkFileTree(p, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.delete(f);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String run(List<String> cmd) throws IOException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] out;
        try (InputStream in = p.getInputStream()) {
            out = AudioTools.readAll(in);
        }
        try {
            if (!p.waitFor(6, TimeUnit.HOURS)) {
                p.destroyForcibly();
                return "it took too long";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "stopped";
        }
        if (p.exitValue() <= 1) {
            return null; // GNU tar answers 1 when a file changed while it was read: still a good backup
        }
        String s = new String(out, StandardCharsets.UTF_8).trim();
        return s.length() > 300 ? s.substring(s.length() - 300) : s;
    }

    // ---- Admin: Server - Disks and backups ----

    /** GET /api/admin/disks/status, POST /api/admin/disks/backup (back up now). extra = more lines (recordings...). */
    public void register(com.sun.net.httpserver.HttpServer server, SessionStore sessions, UserStore users,
                         java.util.function.Supplier<String> extra) {
        server.createContext("/api/admin/disks/", ex -> {
            try {
                String auth = ex.getRequestHeaders().getFirst("Authorization");
                String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : "";
                SessionStore.Session s = token.isEmpty() ? null : sessions.get(token);
                UserStore.Info me = s == null ? null : users.info(s.username);
                String body;
                int status = 200;
                if (me == null || me.role != UserStore.Role.ADMIN) {
                    status = 403;
                    body = "{\"error\":\"admins only\"}";
                } else {
                    String op = ex.getRequestURI().getPath().substring("/api/admin/disks/".length());
                    if ("backup".equals(op) && "POST".equalsIgnoreCase(ex.getRequestMethod())) {
                        if (!available()) {
                            status = 409;
                        } else {
                            runSoon();
                        }
                    }
                    body = status == 409 ? "{\"error\":\"there is no backup disk on this server (run setup-disks.sh)\"}" : statusJson(extra);
                }
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                ex.sendResponseHeaders(status, b.length);
                ex.getResponseBody().write(b);
            } finally {
                ex.close();
            }
        });
    }

    String statusJson(java.util.function.Supplier<String> extra) {
        return "{\"video\":" + ProfileApi.quote(videos == null ? "" : MediaDisks.spaceLine(videos)) + ",\"videoDir\":"
                + ProfileApi.quote(videos == null ? "" : videos.toAbsolutePath().toString())
                + ",\"backup\":" + ProfileApi.quote(available() ? MediaDisks.spaceLine(root) : "no backup disk")
                + ",\"backupOn\":" + available() + ",\"running\":" + running
                + ",\"lastRun\":" + lastRun + ",\"lastOk\":" + lastOk + ",\"message\":" + ProfileApi.quote(lastMessage)
                + ",\"extra\":" + ProfileApi.quote(extra == null ? "" : extra.get()) + "}";
    }

    /** "today 03:30" style, for the admin. */
    static String when(long ms) {
        if (ms <= 0) {
            return "never";
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).toString().replace('T', ' ').substring(0, 16);
    }
}
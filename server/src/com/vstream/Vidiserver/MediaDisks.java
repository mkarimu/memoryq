package com.vstream.Vidiserver;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Where videos live. On the VPS the video disk is mounted at /srv/mkarimu (its own drive) and backups go to
 * /srv/backup (the other drive); both are made by setup-disks.sh. Without them (a PC, tests) everything stays in
 * the server's own folder, as before.
 *   vstream.mediaRoot   (default /srv/mkarimu)  videos/, recordings/, thumbs/
 *   vstream.backupRoot  (default /srv/backup)   data/, videos/, radio-music/
 */
public final class MediaDisks {

    private MediaDisks() {
    }

    public static Path mediaRoot() {
        return Paths.get(System.getProperty("vstream.mediaRoot", "/srv/mkarimu"));
    }

    public static Path backupRoot() {
        return Paths.get(System.getProperty("vstream.backupRoot", "/srv/backup"));
    }

    /** A folder that exists and the server may write to. */
    static boolean usable(Path p) {
        return Files.isDirectory(p) && Files.isWritable(p);
    }

    /** The videos folder: vstream.videoDir if set, else the video disk when it is there, else ./videos. */
    public static Path videoDir() {
        String set = System.getProperty("vstream.videoDir");
        if (set != null && !set.trim().isEmpty()) {
            return Paths.get(set.trim());
        }
        return usable(mediaRoot()) ? mediaRoot().resolve("videos") : Paths.get("videos");
    }

    /** Live recordings (MediaMTX writes them when the video disk is there), or null. */
    public static Path recordingsDir() {
        Path r = mediaRoot().resolve("recordings");
        return usable(mediaRoot()) ? r : null;
    }

    /**
     * Moves the videos of an earlier folder to the new one (once, when the video disk is first used). Files that
     * are already there are left alone. Returns how many files were moved.
     */
    public static int moveVideos(Path from, Path to) {
        if (from == null || to == null || !Files.isDirectory(from)) {
            return 0;
        }
        try {
            if (Files.isSameFile(from, to)) {
                return 0;
            }
        } catch (IOException e) {
            // not the same
        }
        int n = 0;
        try {
            Files.createDirectories(to);
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(from)) {
                for (Path f : ds) {
                    if (!Files.isRegularFile(f) || f.getFileName().toString().contains(".part")) {
                        continue;
                    }
                    Path t = to.resolve(f.getFileName().toString());
                    if (Files.exists(t)) {
                        continue;
                    }
                    Path tmp = to.resolve(f.getFileName() + ".moving");
                    Files.copy(f, tmp, StandardCopyOption.REPLACE_EXISTING);
                    if (Files.size(tmp) != Files.size(f)) {
                        Files.deleteIfExists(tmp);
                        throw new IOException("copy of " + f.getFileName() + " is incomplete");
                    }
                    Files.move(tmp, t);
                    Files.delete(f);
                    n++;
                }
            }
        } catch (IOException e) {
            System.out.println("[disks] moving the videos to " + to + " stopped: " + e.getMessage() + " (the rest is moved at the next start)");
        }
        if (n > 0) {
            System.out.println("[disks] moved " + n + " video file(s) to " + to);
        }
        return n;
    }

    /** {total bytes, free bytes} of the disk a folder is on, or null. */
    public static long[] space(Path p) {
        try {
            if (p == null || !Files.exists(p)) {
                return null;
            }
            FileStore fs = Files.getFileStore(p);
            return new long[] {fs.getTotalSpace(), fs.getUsableSpace()};
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** "393 GB, 12% used (346 GB free)" or "not there". */
    public static String spaceLine(Path p) {
        long[] s = space(p);
        if (s == null || s[0] <= 0) {
            return "not there";
        }
        return gb(s[0]) + ", " + usedPercent(p) + "% used (" + gb(s[1]) + " free)";
    }

    /** How full the disk is (0-100), or -1. */
    public static int usedPercent(Path p) {
        long[] s = space(p);
        return s == null || s[0] <= 0 ? -1 : (int) Math.round(100.0 * (s[0] - s[1]) / s[0]);
    }

    static String gb(long bytes) {
        double g = bytes / 1073741824.0;
        return g >= 10 ? Math.round(g) + " GB" : String.format(java.util.Locale.ROOT, "%.1f GB", g);
    }
}
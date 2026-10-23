package com.vstream.Vidiserver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * Which country an internet address is in, for the listener statistics.
 * Uses the free "IP to Country Lite" list from DB-IP (db-ip.com, CC BY 4.0), downloaded once a month into
 * geo/dbip-country-lite.csv.gz. Without the list every listener is "Unknown".
 */
public final class GeoIp {

    /** Attribution the licence asks for; shown under the Countries statistics. */
    public static final String CREDIT = "IP geolocation by DB-IP (db-ip.com)";

    private final Path file;
    private volatile Table table = null;
    private volatile long loadedAt = 0;

    private static final class Table {
        long[] v4start = new long[0];
        long[] v4end = new long[0];
        String[] v4cc = new String[0];
        long[] v6startHi = new long[0];
        long[] v6startLo = new long[0];
        long[] v6endHi = new long[0];
        long[] v6endLo = new long[0];
        String[] v6cc = new String[0];
    }

    public GeoIp(Path file) {
        this.file = file;
    }

    public boolean ready() {
        return table != null;
    }

    /** Loads the list if it is there; downloads a fresh one in the background when it is missing or old. */
    public void start() {
        Thread t = new Thread(() -> {
            try {
                if (Files.isRegularFile(file)) {
                    load();
                }
                long age = Files.isRegularFile(file) ? System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis() : Long.MAX_VALUE;
                if (age > 32L * 86400000 && !Boolean.getBoolean("vstream.noGeoDownload")) {
                    if (download()) {
                        load();
                    }
                }
            } catch (Exception e) {
                System.out.println("[geo] " + e.getMessage());
            }
        }, "geo-ip");
        t.setDaemon(true);
        t.start();
    }

    /** Fetches this month's list (or last month's). */
    boolean download() {
        LocalDate d = LocalDate.now(ZoneOffset.UTC);
        for (int back = 0; back < 3; back++) {
            LocalDate m = d.minusMonths(back);
            String url = String.format(Locale.ROOT, "https://download.db-ip.com/free/dbip-country-lite-%04d-%02d.csv.gz", m.getYear(), m.getMonthValue());
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(60000);
                c.setRequestProperty("User-Agent", "Memoryq-radio-stats");
                if (c.getResponseCode() != 200) {
                    continue;
                }
                Files.createDirectories(file.toAbsolutePath().getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".part");
                try (InputStream in = c.getInputStream()) {
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                }
                if (Files.size(tmp) < 100000) {
                    Files.deleteIfExists(tmp);
                    continue;
                }
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[geo] downloaded the country list (" + m + ")");
                return true;
            } catch (IOException e) {
                System.out.println("[geo] could not download " + url + ": " + e.getMessage());
            }
        }
        return false;
    }

    /** Reads "start,end,CC" lines (gzip or plain). */
    synchronized void load() throws IOException {
        InputStream raw = Files.newInputStream(file);
        InputStream in = file.getFileName().toString().endsWith(".gz") ? new GZIPInputStream(raw, 65536) : raw;
        List<long[]> v4 = new ArrayList<long[]>();
        List<String> v4c = new ArrayList<String>();
        List<long[]> v6 = new ArrayList<long[]>();
        List<String> v6c = new ArrayList<String>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split(",");
                if (f.length < 3) {
                    continue;
                }
                String cc = f[2].replace("\"", "").trim().toUpperCase(Locale.ROOT);
                String a = f[0].replace("\"", "").trim(), b = f[1].replace("\"", "").trim();
                try {
                    if (a.indexOf(':') < 0) {
                        v4.add(new long[] {v4(a), v4(b)});
                        v4c.add(cc.intern());
                    } else {
                        long[] s = v6(a), e = v6(b);
                        v6.add(new long[] {s[0], s[1], e[0], e[1]});
                        v6c.add(cc.intern());
                    }
                } catch (Exception e) {
                    // a line we do not understand
                }
            }
        }
        Table t = new Table();
        t.v4start = new long[v4.size()];
        t.v4end = new long[v4.size()];
        t.v4cc = v4c.toArray(new String[0]);
        for (int i = 0; i < v4.size(); i++) {
            t.v4start[i] = v4.get(i)[0];
            t.v4end[i] = v4.get(i)[1];
        }
        int n = v6.size();
        t.v6startHi = new long[n];
        t.v6startLo = new long[n];
        t.v6endHi = new long[n];
        t.v6endLo = new long[n];
        t.v6cc = v6c.toArray(new String[0]);
        for (int i = 0; i < n; i++) {
            long[] x = v6.get(i);
            t.v6startHi[i] = x[0];
            t.v6startLo[i] = x[1];
            t.v6endHi[i] = x[2];
            t.v6endLo[i] = x[3];
        }
        table = t;
        loadedAt = System.currentTimeMillis();
        System.out.println("[geo] country list ready: " + v4.size() + " IPv4 + " + n + " IPv6 ranges");
    }

    private static long v4(String ip) throws IOException {
        byte[] b = InetAddress.getByName(ip).getAddress();
        if (b.length != 4) {
            throw new IOException("not IPv4");
        }
        return ((b[0] & 0xFFL) << 24) | ((b[1] & 0xFFL) << 16) | ((b[2] & 0xFFL) << 8) | (b[3] & 0xFFL);
    }

    private static long[] v6(String ip) throws IOException {
        byte[] b = InetAddress.getByName(ip).getAddress();
        if (b.length != 16) {
            throw new IOException("not IPv6");
        }
        long hi = 0, lo = 0;
        for (int i = 0; i < 8; i++) {
            hi = (hi << 8) | (b[i] & 0xFFL);
            lo = (lo << 8) | (b[i + 8] & 0xFFL);
        }
        return new long[] {hi, lo};
    }

    /** Two-letter country code, "LAN" for a home / office network, or "" when not known. */
    public String country(String ip) {
        if (ip == null || ip.isEmpty()) {
            return "";
        }
        if (!ip.matches("[0-9a-fA-F:.]+")) {
            return ""; // never look up names
        }
        try {
            InetAddress a = InetAddress.getByName(ip);
            if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()
                    || (a.getAddress().length == 16 && (a.getAddress()[0] & 0xFE) == 0xFC)) {
                return "LAN";
            }
            Table t = table;
            if (t == null) {
                return "";
            }
            byte[] b = a.getAddress();
            if (b.length == 4) {
                long v = ((b[0] & 0xFFL) << 24) | ((b[1] & 0xFFL) << 16) | ((b[2] & 0xFFL) << 8) | (b[3] & 0xFFL);
                int i = upper(t.v4start, v);
                return i >= 0 && v <= t.v4end[i] ? t.v4cc[i] : "";
            }
            long hi = 0, lo = 0;
            for (int i = 0; i < 8; i++) {
                hi = (hi << 8) | (b[i] & 0xFFL);
                lo = (lo << 8) | (b[i + 8] & 0xFFL);
            }
            int lo0 = 0, hi0 = t.v6startHi.length - 1, found = -1;
            while (lo0 <= hi0) {
                int mid = (lo0 + hi0) >>> 1;
                if (cmp(t.v6startHi[mid], t.v6startLo[mid], hi, lo) <= 0) {
                    found = mid;
                    lo0 = mid + 1;
                } else {
                    hi0 = mid - 1;
                }
            }
            return found >= 0 && cmp(hi, lo, t.v6endHi[found], t.v6endLo[found]) <= 0 ? t.v6cc[found] : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** Index of the last start <= v, or -1. */
    private static int upper(long[] starts, long v) {
        int lo = 0, hi = starts.length - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (starts[mid] <= v) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    private static int cmp(long ah, long al, long bh, long bl) {
        int c = Long.compareUnsigned(ah, bh);
        return c != 0 ? c : Long.compareUnsigned(al, bl);
    }

    /** "KE" -> "Kenya". */
    public static String name(String cc) {
        if (cc == null || cc.isEmpty()) {
            return "Unknown";
        }
        if ("LAN".equals(cc)) {
            return "Local network";
        }
        if ("ZZ".equals(cc)) {
            return "Unknown";
        }
        String n = new Locale("", cc).getDisplayCountry(Locale.ENGLISH);
        return n == null || n.isEmpty() || n.equals(cc) ? cc : n;
    }
}
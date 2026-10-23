package com.vstream.broadcaster;

import android.content.SharedPreferences;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Finds the VStream server on this phone's Wi-Fi by checking for its channel list on port 8080. */
final class ServerFinder {

    private static final Object LOCK = new Object();
    private static final long RESCAN_MS = 30000;
    private static long lastScan = 0;

    private ServerFinder() {
    }

    /**
     * Makes sure the saved server address answers, looking for the server on this Wi-Fi if it does not, and
     * saves what it finds. Without force, a failed search is not repeated for 30 seconds. Call it off the main
     * thread. Returns the server address, or null if no server was found.
     */
    static String ensure(SharedPreferences prefs, boolean force) {
        synchronized (LOCK) {
            String saved = prefs.getString("host", "");
            if (!saved.isEmpty() && isServer(saved, 1500)) {
                return saved;
            }
            if (!Api.DEFAULT_HOST.equalsIgnoreCase(saved) && isServer(Api.DEFAULT_HOST, 5000)) {
                prefs.edit().putString("host", Api.DEFAULT_HOST).apply();
                return Api.DEFAULT_HOST;
            }
            long now = SystemClock.elapsedRealtime();
            if (!force && lastScan > 0 && now - lastScan < RESCAN_MS) {
                return null;
            }
            lastScan = now;
            String found = scanSubnet();
            if (found != null) {
                prefs.edit().putString("host", found).apply();
            }
            return found;
        }
    }

    /** Returns the saved address if it still answers, otherwise scans the Wi-Fi network. */
    static String find(String saved) {
        if (saved != null && !saved.isEmpty() && isServer(saved, 1000)) {
            return saved;
        }
        if (isServer(Api.DEFAULT_HOST, 5000)) {
            return Api.DEFAULT_HOST;
        }
        return scanSubnet();
    }

    static boolean isServer(String host, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, Api.isDomain(host) ? 443 : 8080), timeoutMs);
        } catch (IOException e) {
            return false;
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(Api.base(host) + "/channels").openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(1500);
            if (c.getResponseCode() != 200) {
                return false;
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null && sb.length() < 4096) {
                sb.append(line);
            }
            reader.close();
            return sb.toString().contains("\"channels\"");
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** This phone's Wi-Fi network prefix, for example "10.158.91.". Null if not on Wi-Fi. */
    static String localSubnetBase() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || !ni.getName().startsWith("wlan")) {
                    continue;
                }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) {
                        String ip = a.getHostAddress();
                        return ip.substring(0, ip.lastIndexOf('.') + 1);
                    }
                }
            }
        } catch (Exception e) {
            // fall through
        }
        return null;
    }

    static String scanSubnet() {
        String base = localSubnetBase();
        if (base == null) {
            return null;
        }
        ExecutorService pool = Executors.newFixedThreadPool(48);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 1; i <= 254; i++) {
            final String host = base + i;
            futures.add(pool.submit((Callable<String>) () -> isServer(host, 400) ? host : null));
        }
        String found = null;
        for (Future<String> f : futures) {
            try {
                String r = f.get();
                if (r != null) {
                    found = r;
                    break;
                }
            } catch (Exception ignored) {
                // keep looking
            }
        }
        pool.shutdownNow();
        return found;
    }
}
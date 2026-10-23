package com.vstream.Vidiserver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.Locale;

/**
 * Downloads a feed. Only http and https, no passwords in the address, redirects checked at every step,
 * at most 2 MB, and addresses that lead to this computer or a private network are refused.
 */
public final class SafeFetcher implements NewsService.Fetcher {

    static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 3;

    private final boolean allowPrivate;

    public SafeFetcher() {
        this(false);
    }

    /** allowPrivate is only for tests that talk to a server on this computer. */
    SafeFetcher(boolean allowPrivate) {
        this.allowPrivate = allowPrivate;
    }

    @Override
    public byte[] fetch(String url) throws IOException {
        String current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URL u = check(current);
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "MemoryqNews/1.0");
            c.setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, */*");
            try {
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && code != 304) {
                    String location = c.getHeaderField("Location");
                    if (location == null || location.isEmpty()) {
                        throw new IOException("the site sent a redirect without a location");
                    }
                    current = new URL(u, location).toString();
                    continue;
                }
                if (code != 200) {
                    throw new IOException("the site answered HTTP " + code);
                }
                return readLimited(c.getInputStream());
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("too many redirects");
    }

    private URL check(String address) throws IOException {
        URL u;
        try {
            u = new URL(address);
        } catch (java.net.MalformedURLException e) {
            throw new IOException("that is not a valid web address");
        }
        String proto = u.getProtocol().toLowerCase(Locale.ROOT);
        if (!proto.equals("http") && !proto.equals("https")) {
            throw new IOException("only http and https addresses are allowed");
        }
        if (u.getUserInfo() != null) {
            throw new IOException("addresses with a user name or password are not allowed");
        }
        String host = u.getHost();
        if (host == null || host.isEmpty()) {
            throw new IOException("that address has no host name");
        }
        if (!allowPrivate) {
            InetAddress[] all;
            try {
                all = InetAddress.getAllByName(host);
            } catch (java.net.UnknownHostException e) {
                throw new IOException("the host name was not found");
            }
            for (InetAddress a : all) {
                if (isBlocked(a)) {
                    throw new IOException("that address is not allowed (it points to a private network)");
                }
            }
        }
        return u;
    }

    /** True for addresses on this computer or on a private network. */
    static boolean isBlocked(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            return first == 0 || (first == 100 && second >= 64 && second <= 127);
        }
        return b.length == 16 && (b[0] & 0xFE) == 0xFC;
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        try {
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_BYTES) {
                    throw new IOException("the feed is too large");
                }
                out.write(buf, 0, n);
            }
        } finally {
            in.close();
        }
        return out.toByteArray();
    }
}
package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Google AdSense on the website: the AdSense code goes into the head of every page the server sends (home, Me, World
 * Radio, TV, Marketplace, Download, Terms...), except pages that must not carry ads (admin, Studio, DJ Pro, sign-in).
 * Also serves https://memoryq.com/ads.txt, which AdSense needs.
 *
 *   GET  /ads.txt                      google.com, pub-..., DIRECT, f08c47fec0942fa0
 *   GET  /api/admin/adsense            the settings      POST /api/admin/adsense   on=1|0, client=ca-pub-..., off=/admin,/studio,...
 * Settings: adsense.txt (on, client, pages without ads). The client id is public (it is in every page), not a secret.
 */
public final class AdSense {

    static final String DEFAULT_CLIENT = "ca-pub-2298775225198657";
    static final Set<String> DEFAULT_OFF = new LinkedHashSet<String>(Arrays.asList("/admin", "/studio", "/djpro", "/login"));
    /** The pages a member of the public can open (the ones that can carry ads). */
    static final String[] PAGES = {"/", "/me", "/world", "/tv", "/market", "/download", "/terms", "/privacy", "/vstream-legal",
        "/admin", "/studio", "/djpro", "/login"};

    private static volatile AdSense current;

    private final Path file;
    private final SessionStore sessions;
    private volatile boolean on = true;
    private volatile String client = DEFAULT_CLIENT;
    private volatile Set<String> off = new LinkedHashSet<String>(DEFAULT_OFF);

    public AdSense(Path file, SessionStore sessions) {
        this.file = file;
        this.sessions = sessions;
    }

    public synchronized AdSense init() {
        if (Files.isRegularFile(file)) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    int eq = line.indexOf('=');
                    if (eq < 0) {
                        continue;
                    }
                    String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
                    if (k.equals("on")) {
                        on = !"0".equals(v);
                    } else if (k.equals("client") && validClient(v)) {
                        client = v;
                    } else if (k.equals("off")) {
                        off = routes(v);
                    }
                }
            } catch (IOException e) {
                System.out.println("[adsense] could not read the settings: " + e.getMessage());
            }
        }
        current = this;
        System.out.println("[adsense] " + (on ? "Google AdSense on (" + client + "), not on " + String.join(" ", off) : "Google AdSense off"));
        return this;
    }

    static boolean validClient(String c) {
        return c != null && c.matches("ca-pub-\\d{10,20}");
    }

    private static Set<String> routes(String v) {
        Set<String> out = new LinkedHashSet<String>();
        for (String r : v.split("[,\\s]+")) {
            if (r.matches("/[a-z0-9/-]{0,40}")) {
                out.add(r);
            }
        }
        return out;
    }

    private synchronized void save() throws IOException {
        String text = "# Google AdSense (Admin -> Ads -> Google AdSense)\non=" + (on ? 1 : 0) + "\nclient=" + client + "\noff=" + String.join(",", off) + "\n";
        Path part = file.resolveSibling(file.getFileName() + ".part");
        Files.write(part, text.getBytes(StandardCharsets.UTF_8));
        Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
    }

    String tag() {
        return "<script async src=\"https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?client=" + client
                + "\"\n     crossorigin=\"anonymous\"></script>";
    }

    /** The page with the AdSense code in its head (when ads are on for this page). */
    String apply(String html, String route) {
        if (!on || off.contains(route) || html.contains("googlesyndication.com/pagead/js/adsbygoogle.js")) {
            return html;
        }
        int head = html.toLowerCase(Locale.ROOT).indexOf("</head>");
        if (head < 0) {
            return html;
        }
        return html.substring(0, head) + tag() + "\n" + html.substring(head);
    }

    /** For the page senders: the page with the AdSense code (unchanged when AdSense is not set up). */
    public static String inject(String html, String route) {
        AdSense a = current;
        return a == null ? html : a.apply(html, route);
    }

    public static byte[] inject(byte[] html, String route) {
        AdSense a = current;
        return a == null ? html : a.apply(new String(html, StandardCharsets.UTF_8), route).getBytes(StandardCharsets.UTF_8);
    }

    public void register(HttpServer server) {
        server.createContext("/ads.txt", this::adsTxt);
        server.createContext("/api/admin/adsense", this::admin);
    }

    private void adsTxt(HttpExchange ex) throws IOException {
        try {
            if (!ex.getRequestURI().getPath().equals("/ads.txt")) {
                SuperAgent.send(ex, 404, "text/plain; charset=utf-8", "not found");
                return;
            }
            String body = "google.com, " + client.substring(3) + ", DIRECT, f08c47fec0942fa0\n";
            ex.getResponseHeaders().set("Cache-Control", "max-age=3600");
            SuperAgent.send(ex, 200, "text/plain; charset=utf-8", body);
        } finally {
            ex.close();
        }
    }

    private String json() {
        StringBuilder sb = new StringBuilder("{\"on\":").append(on).append(",\"client\":").append(VideoApi.jq(client)).append(",\"pages\":[");
        for (int k = 0; k < PAGES.length; k++) {
            sb.append(k > 0 ? "," : "").append("{\"route\":").append(VideoApi.jq(PAGES[k])).append(",\"ads\":").append(!off.contains(PAGES[k])).append('}');
        }
        return sb.append("],\"adsTxt\":").append(VideoApi.jq("google.com, " + client.substring(3) + ", DIRECT, f08c47fec0942fa0")).append('}').toString();
    }

    private void admin(HttpExchange ex) throws IOException {
        try {
            if (!SuperAgent.isAdmin(ex, sessions)) {
                SuperAgent.send(ex, 403, "application/json", "{\"error\":\"super-admin only\"}");
                return;
            }
            if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
                String body = SuperAgent.readBody(ex, 4096);
                String c = MediaAuthHttpServer.queryParam(body, "client");
                if (c != null && !c.trim().isEmpty()) {
                    c = c.trim().replaceFirst("^pub-", "ca-pub-");
                    if (!validClient(c)) {
                        SuperAgent.send(ex, 400, "application/json", "{\"error\":\"the publisher id looks like ca-pub-1234567890123456\"}");
                        return;
                    }
                    client = c;
                }
                String o = MediaAuthHttpServer.queryParam(body, "on");
                if (o != null) {
                    on = "1".equals(o.trim()) || "true".equals(o.trim());
                }
                String f = MediaAuthHttpServer.queryParam(body, "off");
                if (f != null) {
                    Set<String> s = routes(f);
                    s.add("/admin"); // never ads in the admin panel
                    off = s;
                }
                save();
            }
            SuperAgent.send(ex, 200, "application/json", json());
        } finally {
            ex.close();
        }
    }
}
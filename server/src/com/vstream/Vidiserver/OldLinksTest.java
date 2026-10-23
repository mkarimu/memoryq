package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/** Old listening links: the old Centova ports and /listen/NAME/ play the moved stations; the admin can change them. */
public final class OldLinksTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("memoryq-oldlinks");
        Path imp = dir.resolve("import");
        Files.createDirectories(imp);
        System.setProperty("vstream.radioImportDir", imp.toString());
        int oldPort;
        try (ServerSocket ss = new ServerSocket(0)) {
            oldPort = ss.getLocalPort();
        }
        System.setProperty("vstream.oldLinksIp", "127.0.0.1"); // stands in for 184.75.212.211
        UserStore us = new UserStore(dir.resolve("users.txt"), new LiveChannelRegistry(), c -> { });
        us.init(dir.resolve("none.txt"));
        us.createUser("someone", "password123", UserStore.Role.CHATTER);
        SessionStore sessions = new SessionStore();
        String admin = sessions.create("admin", UserStore.Role.ADMIN);
        String someone = sessions.create("someone", UserStore.Role.CHATTER);
        RadioStore store = new RadioStore(dir.resolve("radio.txt"), dir.resolve("music"));
        store.init();
        RadioStore.Station mk = store.createStation("Mkarimu Radio", "", "owner", RadioStore.STATUS_ACTIVE, 0);
        RadioStore.Station other = store.createStation("Other FM", "", "owner", RadioStore.STATUS_ACTIVE, 0);
        // an imported Centova account: mkarimu, on its old port
        Files.write(imp.resolve("imported.tsv"), ("mkarimu\t" + mk.id + "\t1\t\t" + oldPort + "\n").getBytes(StandardCharsets.UTF_8));
        AudioTools tools = new AudioTools(dir.resolve("tools"), dir.resolve("tmp"));
        RadioService radio = new RadioService(store, tools, null, null, null, null, () -> 0L, new Random(1));
        RadioApi api = new RadioApi(radio, us, sessions, () -> 0L, dir.resolve("tmp"));
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        api.register(http);
        http.start();
        int port = http.getAddress().getPort();
        OldLinks ol = api.oldLinks();

        System.out.println("-- The rules --");
        String list = get(port, "/api/admin/old-links/list", admin)[1];
        checkTrue("the imported account's old port and name are there", list.contains("\"kind\":\"port\",\"key\":\"" + oldPort + "\",\"station\":" + mk.id)
                && list.contains("\"kind\":\"name\",\"key\":\"mkarimu\""));
        checkTrue("waiting until the address is opened", list.contains("waiting for the address"));
        check("admins only", get(port, "/api/admin/old-links/list", someone)[0], "403");

        System.out.println("-- The old Centova port --");
        ol.refresh();
        String[] r = get(oldPort, "/stream", null);
        checkTrue("http://old-address:PORT/stream reaches the station (" + r[1] + ")", r[0].equals("503") && r[1].contains("off air"));
        checkTrue("any path plays it (/autodj, /;)", get(oldPort, "/autodj", null)[1].contains("off air"));
        r = get(oldPort, "/listen.pls", null);
        checkTrue("a playlist file gives the old address", r[0].equals("200") && r[1].contains("#EXTM3U") && r[1].contains(":" + oldPort + "/stream"));
        check("no icon", get(oldPort, "/favicon.ico", null)[0], "404");
        checkTrue("shown as listening", get(port, "/api/admin/old-links/list", admin)[1].contains("\"status\":\"listening\""));

        System.out.println("-- /listen/NAME/ --");
        r = get(port, "/listen/mkarimu/Mkarimu", null);
        checkTrue("/listen/mkarimu/... reaches the station", r[0].equals("503") && r[1].contains("off air"));
        checkTrue("names are not case sensitive", get(port, "/listen/MKARIMU/radio.mp3", null)[1].contains("off air"));
        check("an unknown name", get(port, "/listen/nobody/x", null)[0], "404");

        System.out.println("-- The admin changes them --");
        r = post(port, "/api/admin/old-links/save", "kind=port&key=" + oldPort + "&station=" + other.id, admin);
        checkTrue("a port can play another station", r[0].equals("200") && ol.stationFor("port", String.valueOf(oldPort)) == other.id);
        check("ports below 1024 are refused", post(port, "/api/admin/old-links/save", "kind=port&key=80&station=" + mk.id, admin)[0], "400");
        check("unknown stations are refused", post(port, "/api/admin/old-links/save", "kind=name&key=x&station=999", admin)[0], "400");
        check("a name can be added", post(port, "/api/admin/old-links/save", "kind=name&key=gospelfm&station=" + other.id, admin)[0], "200");
        checkTrue("and plays", get(port, "/listen/gospelfm/stream", null)[1].contains("off air"));
        post(port, "/api/admin/old-links/delete", "kind=port&key=" + oldPort, admin);
        checkTrue("removing the admin's rule brings the imported one back", ol.stationFor("port", String.valueOf(oldPort)) == mk.id);
        post(port, "/api/admin/old-links/delete", "kind=port&key=" + oldPort, admin);
        checkTrue("removing an imported one switches it off and closes the port", ol.stationFor("port", String.valueOf(oldPort)) == 0
                && get(oldPort, "/stream", null)[0].equals("closed"));
        OldLinks again = new OldLinks(api, api.importer(), radio, us, sessions, imp.resolve("old-links.tsv"), "127.0.0.1");
        checkTrue("the admin's choices are kept", again.stationFor("port", String.valueOf(oldPort)) == 0 && again.stationFor("name", "gospelfm") == other.id);
        OldLinks away = new OldLinks(api, api.importer(), radio, us, sessions, dir.resolve("x.tsv"), "10.255.255.1");
        away.refresh();
        checkTrue("without the old address on this server, no ports are opened", !away.ipPresent() && away.listJson().contains("waiting for the address 10.255.255.1"));

        System.out.println("-- The setup script --");
        r = get(port, "/radio/oldlinks.sh", null);
        checkTrue("served for the VPS", r[0].equals("200") && r[1].startsWith("#!/bin/bash") && r[1].contains("184.75.212.209"));

        ol.stop();
        http.stop(0);
        System.out.println();
        if (failures == 0) {
            System.out.println("ALL CHECKS PASSED");
        } else {
            System.out.println(failures + " CHECK(S) FAILED");
            System.exit(1);
        }
        System.exit(0);
    }

    private static String[] get(int port, String path, String token) throws Exception {
        return http(port, "GET", path, null, token);
    }

    private static String[] post(int port, String path, String body, String token) throws Exception {
        return http(port, "POST", path, body.getBytes(StandardCharsets.UTF_8), token);
    }

    private static String[] http(int port, String method, String path, byte[] body, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(3000);
        c.setReadTimeout(10000);
        if (token != null) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.getOutputStream().write(body);
        }
        int code;
        try {
            code = c.getResponseCode();
        } catch (java.net.ConnectException e) {
            return new String[]{"closed", ""};
        }
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        byte[] b = in == null ? new byte[0] : AudioTools.readAll(in);
        return new String[]{String.valueOf(code), new String(b, StandardCharsets.UTF_8)};
    }

    private static void check(String name, String actual, String expected) {
        checkTrue(name + " (got " + actual + ")", expected.equals(actual));
    }

    private static void checkTrue(String name, boolean ok) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + name);
        if (!ok) {
            failures++;
        }
    }
}
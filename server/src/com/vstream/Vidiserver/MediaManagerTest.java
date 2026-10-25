package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

/** The Studio's media manager: folders, many songs at once (delete / move / edit / add to a playlist), listening, staff limits. */
public final class MediaManagerTest {

    private static int failures = 0;

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace();
            System.out.println("FAILED: " + t);
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        Path dir = Files.createTempDirectory("memoryq-media");
        long[] now = {1768212000000L};
        RadioStore store = new RadioStore(dir.resolve("radio.txt"), dir.resolve("music"));
        store.init();
        RadioStore.Station st = store.createStation("Gospel FM", "", "owner1", RadioStore.STATUS_ACTIVE, now[0]);
        RadioStore.Station other = store.createStation("Other FM", "", "someone", RadioStore.STATUS_ACTIVE, now[0]);
        long sid = st.id;
        long a = store.addTrack(sid, "a.mp3", "mp3", new byte[3000], "Song A", "", "", 1000, now[0]).id;
        long b = store.addTrack(sid, "b.mp3", "mp3", new byte[3000], "Song B", "", "", 1000, now[0]).id;
        long c = store.addTrack(sid, "c.mp3", "mp3", new byte[3000], "Song C", "", "", 1000, now[0]).id;
        long theirs = store.addTrack(other.id, "x.mp3", "mp3", new byte[3000], "Theirs", "", "", 1000, now[0]).id;
        RadioStore.Playlist pl = new RadioStore.Playlist();
        pl.station = sid;
        pl.name = "Morning";
        long plId = store.savePlaylist(pl, now[0]).id;
        UserStore us = new UserStore(dir.resolve("users.txt"), new LiveChannelRegistry(), x -> { });
        us.init(dir.resolve("none.txt"));
        us.createUser("owner1", "password123", UserStore.Role.CHATTER);
        us.createUser("presen", "password123", UserStore.Role.CHATTER);
        store.setStaff(sid, "presen", RadioStore.ROLE_PRESENTER, now[0]);
        SessionStore sessions = new SessionStore();
        String owner = sessions.create("owner1", UserStore.Role.CHATTER);
        String pres = sessions.create("presen", UserStore.Role.CHATTER);
        RadioService radio = new RadioService(store, new AudioTools(dir.resolve("tools"), dir.resolve("tmp")), null, null, null, null,
                () -> now[0], new Random(1));
        RadioApi api = new RadioApi(radio, us, sessions, () -> now[0], dir.resolve("tmp"));
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        api.register(http);
        http.start();
        int port = http.getAddress().getPort();
        try {
            System.out.println("-- Folders --");
            String[] r = call(port, "POST", "/api/studio/folder/save", "station=" + sid + "&path=" + enc("Gospel/2024"), owner);
            checkTrue("a folder inside a folder (" + r[1] + ")", r[0].equals("200") && r[1].contains("\"path\":\"Gospel/2024\""));
            call(port, "POST", "/api/studio/folder/save", "station=" + sid + "&path=Jingles", owner);
            r = call(port, "GET", "/api/studio/tracks?station=" + sid, null, owner);
            checkTrue("the empty folders are listed, with their parents", r[1].contains("{\"path\":\"Gospel\",\"songs\":0}")
                    && r[1].contains("{\"path\":\"Gospel/2024\",\"songs\":0}") && r[1].contains("{\"path\":\"Jingles\",\"songs\":0}"));
            checkTrue("odd names are cleaned", RadioStore.cleanFolder(" ../a<b>/ c ").equals("ab/c"));
            checkTrue("4 levels deep is refused", call(port, "POST", "/api/studio/folder/save", "station=" + sid + "&path=" + enc("a/b/c/d"), owner)[0].equals("400"));

            System.out.println("-- Many songs at once --");
            r = call(port, "POST", "/api/studio/track/bulk", "station=" + sid + "&action=move&ids=" + a + "," + b + "," + theirs + "&folder=" + enc("Gospel/2024"), owner);
            checkTrue("Move Selected (another station's song is left alone)", r[0].equals("200") && r[1].contains("\"changed\":2")
                    && store.track(a).folder.equals("Gospel/2024") && store.track(theirs).folder.isEmpty());
            r = call(port, "POST", "/api/studio/track/bulk", "station=" + sid + "&action=edit&ids=" + a + "," + b + "&artist=" + enc("Rose Muhando") + "&album=Live", owner);
            checkTrue("Edit Selected (artist, album)", r[0].equals("200") && store.track(a).artist.equals("Rose Muhando") && store.track(b).album.equals("Live")
                    && store.track(a).title.equals("Song A"));
            checkTrue("a bad link is refused", call(port, "POST", "/api/studio/track/bulk", "station=" + sid + "&action=edit&ids=" + a + "&link=nope", owner)[0].equals("400"));
            r = call(port, "POST", "/api/studio/track/bulk", "station=" + sid + "&action=playlist&ids=" + a + "," + b + "&playlist=" + plId, owner);
            checkTrue("Add to playlist", r[0].equals("200") && store.playlist(plId).tracks.containsAll(Arrays.asList(a, b)));
            r = call(port, "POST", "/api/studio/folder/save", "station=" + sid + "&from=Gospel&path=" + enc("Music/Gospel"), owner);
            checkTrue("renaming a folder takes its songs and folders along", r[0].equals("200") && store.track(a).folder.equals("Music/Gospel/2024")
                    && store.folders(sid).containsKey("Music/Gospel/2024") && !store.folders(sid).containsKey("Gospel"));
            checkTrue("not into itself", call(port, "POST", "/api/studio/folder/save", "station=" + sid + "&from=Music&path=" + enc("Music/x"), owner)[0].equals("400"));
            r = call(port, "GET", "/api/studio/tracks?station=" + sid, null, owner);
            checkTrue("the Studio's list shows each song's folder and size", r[1].contains("\"folder\":\"Music/Gospel/2024\",\"size\":3000,\"ext\":\"mp3\""));
            store.flushIfDirty();
            RadioStore again = new RadioStore(dir.resolve("radio.txt"), dir.resolve("music"));
            again.init();
            checkTrue("kept across a restart (folders, empty ones too)", again.track(a).folder.equals("Music/Gospel/2024") && again.folders(sid).containsKey("Jingles"));

            System.out.println("-- Listening --");
            r = call(port, "GET", "/api/studio/track/audio?id=" + a, null, owner);
            checkTrue("the owner can listen to a song", r[0].equals("200") && r[1].length() == 3000);
            checkTrue("but not to another station's", call(port, "GET", "/api/studio/track/audio?id=" + theirs, null, owner)[0].equals("403"));

            System.out.println("-- Presenters --");
            checkTrue("a presenter can move songs", call(port, "POST", "/api/studio/track/bulk", "station=" + sid + "&action=move&ids=" + c + "&folder=Jingles", pres)[0].equals("200"));
            checkTrue("but not delete them", call(port, "POST", "/api/studio/track/bulk", "station=" + sid + "&action=delete&ids=" + c, pres)[0].equals("403")
                    && store.track(c) != null);
            checkTrue("nor delete folders", call(port, "POST", "/api/studio/folder/delete", "station=" + sid + "&path=Jingles", pres)[0].equals("403"));

            System.out.println("-- Deleting --");
            r = call(port, "POST", "/api/studio/folder/delete", "station=" + sid + "&path=Jingles&songs=0", owner);
            checkTrue("a folder deleted without its songs: they move up", r[0].equals("200") && store.track(c) != null && store.track(c).folder.isEmpty()
                    && !store.folders(sid).containsKey("Jingles"));
            r = call(port, "POST", "/api/studio/track/bulk", "station=" + sid + "&action=delete&ids=" + b, owner);
            checkTrue("Delete Selected", r[0].equals("200") && store.track(b) == null);
            r = call(port, "POST", "/api/studio/folder/delete", "station=" + sid + "&path=Music&songs=1", owner);
            checkTrue("a folder deleted with its songs", r[0].equals("200") && r[1].contains("\"deleted\":1") && store.track(a) == null
                    && !Files.exists(dir.resolve("music").resolve(String.valueOf(sid)).resolve("song-" + a + ".mp3")) && store.folders(sid).isEmpty());
            checkTrue("someone else's station is closed", call(port, "POST", "/api/studio/track/bulk", "station=" + other.id + "&action=delete&ids=" + theirs, owner)[0].equals("403"));
        } finally {
            http.stop(0);
        }
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static String enc(String s) throws Exception {
        return java.net.URLEncoder.encode(s, "UTF-8");
    }

    private static String[] call(int port, String method, String path, String body, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
        c.setRequestProperty("Connection", "close");
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        if (token != null) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                b.write(buf, 0, n);
            }
        }
        return new String[]{String.valueOf(code), new String(b.toByteArray(), StandardCharsets.ISO_8859_1)};
    }

    private static void checkTrue(String name, boolean ok) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + name);
        if (!ok) {
            failures++;
        }
    }
}
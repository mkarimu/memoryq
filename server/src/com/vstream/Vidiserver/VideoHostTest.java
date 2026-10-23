package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.List;

/**
 * The video site: videos on the video disk (moved there once), Mkarimu TV at mkarimu.net, live recordings
 * (list, watch, keep as a video, delete, tidy), and the nightly backup (data, videos, songs; set-aside copies).
 */
public final class VideoHostTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true"); // so the test can send Host: mkarimu.net
        Path dir = Files.createTempDirectory("memoryq-videohost");
        Path media = dir.resolve("srv-mkarimu");
        Path backupRoot = dir.resolve("srv-backup");
        Files.createDirectories(media);
        Files.createDirectories(backupRoot);
        System.setProperty("vstream.mediaRoot", media.toString());
        System.setProperty("vstream.backupRoot", backupRoot.toString());
        System.clearProperty("vstream.videoDir");

        System.out.println("-- Videos on the video disk --");
        Path oldVideos = dir.resolve("app").resolve("videos");
        Files.createDirectories(oldVideos.resolve("tmp"));
        Files.write(oldVideos.resolve("1.mp4"), new byte[5000]);
        Files.write(oldVideos.resolve("1.jpg"), new byte[300]);
        Files.write(oldVideos.resolve("2.part.mp4"), new byte[10]);
        Path videoDir = MediaDisks.videoDir();
        check("the video disk is used when it is there", videoDir.toString(), media.resolve("videos").toString());
        int moved = MediaDisks.moveVideos(oldVideos, videoDir);
        checkTrue("the existing videos move there (not half-made ones)", moved == 2 && Files.size(videoDir.resolve("1.mp4")) == 5000
                && !Files.exists(oldVideos.resolve("1.mp4")) && Files.exists(oldVideos.resolve("2.part.mp4")));
        checkTrue("moving again does nothing", MediaDisks.moveVideos(oldVideos, videoDir) == 0);
        checkTrue("recordings go to the video disk", media.resolve("recordings").equals(MediaDisks.recordingsDir()));
        checkTrue("disk space is known", MediaDisks.spaceLine(videoDir).contains("GB") && MediaDisks.usedPercent(videoDir) >= 0);
        System.setProperty("vstream.mediaRoot", dir.resolve("nowhere").toString());
        checkTrue("without the video disk: the server's own folder, no recordings", MediaDisks.videoDir().equals(Paths.get("videos"))
                && MediaDisks.recordingsDir() == null);
        System.setProperty("vstream.mediaRoot", media.toString());

        System.out.println("-- Mkarimu TV at mkarimu.net --");
        Path pages = dir.resolve("pages");
        Files.createDirectories(pages);
        Files.write(pages.resolve("admin.html"), "<html>admin</html>".getBytes(StandardCharsets.UTF_8));
        Files.write(pages.resolve("home.html"), "<html>Memoryq home</html>".getBytes(StandardCharsets.UTF_8));
        Path realTv = Paths.get("tv.html");
        Files.write(pages.resolve("tv.html"), Files.isRegularFile(realTv) ? Files.readAllBytes(realTv)
                : ("<title>Memoryq TV - live now</title><a class=\"brand\" href=\"/\">Memoryq <small>TV</small></a>"
                + "<a class=\"golive\" href=\"/vstream-legal\">Go live</a><a href=\"/me#videos/play/1\">c</a>").getBytes(StandardCharsets.UTF_8));
        UserStore us = new UserStore(dir.resolve("users.txt"), new LiveChannelRegistry(), c -> { });
        us.init(dir.resolve("none.txt"));
        us.createUser("anna", "password123", UserStore.Role.BROADCASTER);
        SessionStore sessions = new SessionStore();
        String admin = sessions.create("admin", UserStore.Role.ADMIN);
        String anna = sessions.create("anna", UserStore.Role.BROADCASTER);
        AccountApi acc = new AccountApi(us, sessions, new Limiter(), new LiveChannelRegistry(), pages.resolve("admin.html"), x -> { }, x -> false);
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        acc.register(http);
        http.createContext("/", ex -> {
            if (!acc.serveHome(ex)) {
                ex.sendResponseHeaders(404, -1);
                ex.close();
            }
        });
        VideoStore store = new VideoStore(dir.resolve("videos.txt"), dir.resolve("video-comments.txt"), videoDir);
        store.init();
        VideoApi videoApi = new VideoApi(store, us, sessions, System::currentTimeMillis);
        videoApi.setTools(() -> null, () -> null);
        Recordings rec = new Recordings(MediaDisks.recordingsDir(), videoApi, us, sessions, System::currentTimeMillis);
        rec.register(http);
        http.start();
        int port = http.getAddress().getPort();
        String[] r = get(port, "/", null, "memoryq.com");
        checkTrue("memoryq.com shows its own home page", r[1].contains("Memoryq home"));
        check("memoryq.com/tv is the TV wall until mkarimu.net is in use", get(port, "/tv", null, "memoryq.com")[0], "200");
        r = get(port, "/", null, "mkarimu.net");
        checkTrue("mkarimu.net's home page is the TV wall, called Mkarimu TV", r[0].equals("200") && r[1].contains("Mkarimu TV")
                && !r[1].contains("Memoryq TV") && !r[1].contains("Memoryq home"));
        checkTrue("signing in, comments and going live stay on Memoryq", r[1].contains("https://memoryq.com/vstream-legal")
                && (r[1].contains("https://memoryq.com/me#") || !r[1].contains("/me#")));
        checkTrue("www.mkarimu.net too", get(port, "/", null, "www.mkarimu.net")[1].contains("Mkarimu TV"));
        r = get(port, "/tv", null, "memoryq.com");
        checkTrue("then memoryq.com/tv sends people to mkarimu.net", r[0].equals("302") && "https://mkarimu.net/".equals(r[2]));

        System.out.println("-- Live recordings --");
        Path chan = media.resolve("recordings").resolve("live").resolve("CHANNEL-3");
        Files.createDirectories(chan);
        Path done = chan.resolve("2026-10-19_18-00-00-000001.mp4");
        Path old = chan.resolve("2026-09-01_10-00-00-000001.mp4");
        Path now = chan.resolve("2026-10-19_19-00-00-000001.mp4");
        byte[] clip = sampleVideo(dir);
        Files.write(done, clip);
        Files.write(old, clip);
        Files.write(now, clip);
        Files.setLastModifiedTime(done, FileTime.fromMillis(System.currentTimeMillis() - 3600000L));
        Files.setLastModifiedTime(old, FileTime.fromMillis(System.currentTimeMillis() - 40L * 86400000L));
        List<Recordings.Rec> list = rec.list();
        checkTrue("three recordings, newest first, one still recording", list.size() == 3 && list.get(0).recording
                && list.get(0).channel.equals("CHANNEL-3") && !list.get(1).recording);
        check("admins only", get(port, "/api/admin/recordings/list", anna, null)[0], "403");
        r = get(port, "/api/admin/recordings/list", admin, null);
        checkTrue("the admin sees them", r[0].equals("200") && r[1].contains("2026-10-19_18-00-00-000001.mp4"));
        r = get(port, "/api/admin/recordings/file?path=" + java.net.URLEncoder.encode("live/CHANNEL-3/2026-10-19_18-00-00-000001.mp4", "UTF-8")
                + "&token=" + admin, null, null);
        checkTrue("and can watch one (token in the link, for the player)", r[0].equals("200") && r[3].equals(String.valueOf(clip.length)));
        check("only files in the recordings folder", get(port, "/api/admin/recordings/file?path=../../videos/1.mp4&token=" + admin, null, null)[0], "404");
        check("a recording still going on cannot be kept", post(port, "/api/admin/recordings/keep",
                "path=live/CHANNEL-3/2026-10-19_19-00-00-000001.mp4&title=x", admin)[0], "400");
        r = post(port, "/api/admin/recordings/keep", "path=live/CHANNEL-3/2026-10-19_18-00-00-000001.mp4&title=Sunday+Service", admin);
        checkTrue("keeping one makes it a video", r[0].equals("200") && !Files.exists(done));
        VideoStore.Video kept = null;
        for (int i = 0; i < 50 && (kept == null || VideoStore.PROCESSING.equals(kept.status)); i++) {
            Thread.sleep(100);
            List<VideoStore.Video> vs = store.list(v -> v.title.equals("Sunday Service"));
            kept = vs.isEmpty() ? null : vs.get(0);
        }
        checkTrue("owned by the channel's broadcaster, ready to play", kept != null && kept.owner.equals("admin") | kept.owner.equals("anna")
                && VideoStore.READY.equals(kept.status) && Files.isRegularFile(store.fileOf(kept)));
        checkTrue("'Keep as video' does not put it on Mkarimu TV", !kept.featured);
        Path tvRec = chan.resolve("2026-10-19_17-00-00-000001.mp4");
        Files.write(tvRec, clip);
        Files.setLastModifiedTime(tvRec, FileTime.fromMillis(System.currentTimeMillis() - 7200000L));
        r = post(port, "/api/admin/recordings/keep", "path=live/CHANNEL-3/2026-10-19_17-00-00-000001.mp4&title=Evening+Praise&tv=1", admin);
        VideoStore.Video onTv = null;
        for (int i = 0; i < 50 && onTv == null; i++) {
            Thread.sleep(100);
            List<VideoStore.Video> vs = store.list(v -> v.title.equals("Evening Praise"));
            onTv = vs.isEmpty() ? null : vs.get(0);
        }
        checkTrue("'Keep + Mkarimu TV' makes it a video on the TV wall", r[0].equals("200") && onTv != null && onTv.featured);
        checkTrue("tidying removes recordings older than 30 days", rec.tidy() == 1 && !Files.exists(old) && Files.exists(now));
        r = post(port, "/api/admin/recordings/delete", "path=live/CHANNEL-3/2026-10-19_19-00-00-000001.mp4", admin);
        checkTrue("the admin can delete one (the empty channel folder goes too)", r[0].equals("200") && !Files.exists(chan));

        System.out.println("-- The nightly backup --");
        Path app = dir.resolve("app");
        Files.write(app.resolve("users.txt"), "anna\n".getBytes(StandardCharsets.UTF_8));
        Files.write(app.resolve("radio.txt"), "S\t1\n".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(app.resolve("out").resolve("com"));
        Files.write(app.resolve("out").resolve("com").resolve("Big.class"), new byte[50000]);
        Path music = app.resolve("radio-music");
        Files.createDirectories(music.resolve("1"));
        Files.write(music.resolve("1").resolve("song-1.mp3"), new byte[4000]);
        Files.createDirectories(backupRoot.resolve("data"));
        for (int d = 1; d <= 16; d++) {
            Files.write(backupRoot.resolve("data").resolve(String.format("memoryq-2020-01-%02d.tar.gz", d)), new byte[10]);
        }
        BackupJob job = new BackupJob(app, backupRoot, videoDir, music, System::currentTimeMillis);
        checkTrue("the backup disk is found", job.available());
        job.run();
        Path tar = backupRoot.resolve("data").resolve("memoryq-" + java.time.LocalDate.now() + ".tar.gz");
        checkTrue("the data is packed (" + job.lastMessage() + ")", Files.isRegularFile(tar) && job.lastOk() > 0);
        String inside = listTar(tar);
        checkTrue("with the data files, without the code", inside.contains("users.txt") && inside.contains("radio.txt")
                && !inside.contains("Big.class") && !inside.contains("song-1.mp3"));
        long kept14;
        try (java.util.stream.Stream<Path> s = Files.list(backupRoot.resolve("data"))) {
            kept14 = s.filter(p -> p.toString().endsWith(".tar.gz")).count();
        }
        check("the last 14 nights are kept", String.valueOf(kept14), "14");
        checkTrue("videos and songs are copied", Files.size(backupRoot.resolve("videos").resolve("1.mp4")) == Files.size(videoDir.resolve("1.mp4"))
                && Files.isRegularFile(backupRoot.resolve("videos").resolve("1.jpg"))
                && Files.size(backupRoot.resolve("radio-music").resolve("1").resolve("song-1.mp3")) == 4000);
        Files.delete(music.resolve("1").resolve("song-1.mp3"));
        job.run();
        checkTrue("a song gone from the server is set aside, not lost", !Files.exists(backupRoot.resolve("radio-music").resolve("1").resolve("song-1.mp3"))
                && Files.exists(backupRoot.resolve("deleted").resolve(java.time.LocalDate.now().toString()).resolve("radio-music").resolve("1").resolve("song-1.mp3")));
        BackupJob again = new BackupJob(app, backupRoot, videoDir, music, System::currentTimeMillis);
        checkTrue("the last backup is remembered after a restart", again.lastOk() == job.lastOk() && again.lastMessage().equals(job.lastMessage()));
        job.register(http, sessions, us, () -> "2 live recordings");
        r = get(port, "/api/admin/disks/status", admin, null);
        checkTrue("Admin - Server - Disks and backups", r[0].equals("200") && r[1].contains("\"backupOn\":true") && r[1].contains("2 live recordings"));
        check("admins only", get(port, "/api/admin/disks/status", anna, null)[0], "403");
        BackupJob none = new BackupJob(app, dir.resolve("no-backup-disk"), videoDir, music, System::currentTimeMillis);
        none.run();
        checkTrue("without a backup disk it says so", !none.available() && none.lastMessage().contains("not there") && none.lastOk() == 0);

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

    /** A few seconds of real video when FFmpeg is there, else some bytes. */
    private static byte[] sampleVideo(Path dir) {
        try {
            Path out = dir.resolve("sample.mp4");
            Process p = new ProcessBuilder("ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=160x120:rate=10",
                    "-t", "2", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-movflags", "frag_keyframe+empty_moov", out.toString())
                    .redirectErrorStream(true).start();
            AudioTools.readAll(p.getInputStream());
            if (p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS) && Files.isRegularFile(out) && Files.size(out) > 1000) {
                return Files.readAllBytes(out);
            }
        } catch (Exception e) {
            // no FFmpeg on this PC
        }
        return new byte[20000];
    }

    private static String listTar(Path tar) throws Exception {
        Process p = new ProcessBuilder("tar", "-tzf", tar.toString()).redirectErrorStream(true).start();
        String s = new String(AudioTools.readAll(p.getInputStream()), StandardCharsets.UTF_8);
        p.waitFor();
        return s;
    }

    private static String[] get(int port, String path, String token, String host) throws Exception {
        return http(port, "GET", path, null, token, host);
    }

    private static String[] post(int port, String path, String body, String token) throws Exception {
        return http(port, "POST", path, body.getBytes(StandardCharsets.UTF_8), token, null);
    }

    /** {status, body, Location, Content-Length}. */
    private static String[] http(int port, String method, String path, byte[] body, String token, String host) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
        c.setInstanceFollowRedirects(false);
        c.setReadTimeout(20000);
        if (host != null) {
            c.setRequestProperty("Host", host);
        }
        if (token != null) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.getOutputStream().write(body);
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        byte[] b = in == null ? new byte[0] : AudioTools.readAll(in);
        return new String[]{String.valueOf(code), new String(b, StandardCharsets.UTF_8), String.valueOf(c.getHeaderField("Location")),
                String.valueOf(b.length)};
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
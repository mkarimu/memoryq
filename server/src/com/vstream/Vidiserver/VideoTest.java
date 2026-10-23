package com.vstream.Vidiserver;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Videos (upload, YouTube, comments, who may do what), Memoryq TV's video list, and the super-admin's user view. */
public final class VideoTest {

    private static int failures = 0;
    private static int port;

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace();
            failures++;
        }
        System.out.println();
        if (failures == 0) {
            System.out.println("ALL CHECKS PASSED");
            System.exit(0);
        }
        System.out.println(failures + " CHECK(S) FAILED");
        System.exit(1);
    }

    private static void run() throws Exception {
        Path dir = Files.createTempDirectory("memoryq-videos");
        Path page = dir.resolve("admin.html");
        Files.write(page, "<html>admin</html>".getBytes(StandardCharsets.UTF_8));
        LiveChannelRegistry reg = new LiveChannelRegistry();
        UserStore us = new UserStore(dir.resolve("users.txt"), reg, k -> { });
        us.init(null);
        for (String u : new String[] {"carol", "dave", "erin"}) {
            us.createUser(u, "password123", UserStore.Role.CHATTER);
        }
        us.createUser("gus", "password123", UserStore.Role.GUEST);
        SessionStore sessions = new SessionStore();
        AccountApi api = new AccountApi(us, sessions, new Limiter(), reg, page, k -> { }, id -> false);
        VideoStore store = new VideoStore(dir.resolve("videos.txt"), dir.resolve("video-comments.txt"), dir.resolve("videos"));
        store.init();
        long[] now = {1768212000000L};
        VideoApi va = new VideoApi(store, us, sessions, () -> now[0]);
        AudioTools tools = new AudioTools(java.nio.file.Paths.get(System.getProperty("vstream.toolsDir", "tools")), dir);
        tools.detect();
        String ff = tools.ffmpegPath();
        String fp = tools.ffprobePath();
        va.setTools(() -> ff, () -> fp);
        va.setYoutubeTitles(id -> "Title from YouTube");
        api.setVideos(va);
        ChatStore chat = new ChatStore(dir.resolve("chat.txt"), () -> now[0]);
        chat.init();
        WallStore wall = new WallStore(dir.resolve("wall.txt"), dir.resolve("wall-img"));
        wall.init();
        api.setUserView(new AdminUserView(us, sessions).videos(store).chat(chat).wall(wall));
        ChannelListHttpServer server = new ChannelListHttpServer(reg, 0);
        server.setAccountApi(api);
        server.start();
        port = server.getPort();
        String admin = sessions.create("admin", UserStore.Role.ADMIN);
        String carol = sessions.create("carol", UserStore.Role.CHATTER);
        String dave = sessions.create("dave", UserStore.Role.CHATTER);
        String gus = sessions.create("gus", UserStore.Role.GUEST);
        String[] r;

        System.out.println("-- YouTube links --");
        check("youtu.be link", VideoStore.youtubeId("https://youtu.be/dQw4w9WgXcQ?t=3"), "dQw4w9WgXcQ");
        check("watch link", VideoStore.youtubeId("https://www.youtube.com/watch?feature=x&v=dQw4w9WgXcQ"), "dQw4w9WgXcQ");
        check("shorts link", VideoStore.youtubeId("youtube.com/shorts/abcdefghijk"), "abcdefghijk");
        checkTrue("other sites are refused", VideoStore.youtubeId("https://evil.com/watch?v=dQw4w9WgXcQ") == null);
        check("anyone can watch, nobody signed in can add", post("/api/videos/youtube", null, "url", "https://youtu.be/dQw4w9WgXcQ")[0], "401");
        check("guest accounts cannot add", post("/api/videos/youtube", gus, "url", "https://youtu.be/dQw4w9WgXcQ")[0], "403");
        r = post("/api/videos/youtube", admin, "url", "https://youtu.be/dQw4w9WgXcQ");
        checkTrue("the super-admin adds a YouTube video; the title comes from YouTube", r[0].equals("200") && r[1].contains("Title from YouTube")
                && r[1].contains("\"featured\":true"));
        long ytAdmin = id(r[1]);
        r = post("/api/videos/youtube", carol, "url", "https://www.youtube.com/watch?v=abcdefghijk", "title", "Carol's clip");
        checkTrue("a member adds one (not on TV until the admin picks it)", r[0].equals("200") && r[1].contains("\"featured\":false"));
        long ytCarol = id(r[1]);
        r = get("/api/videos?scope=tv", null);
        checkTrue("TV lists only the admin's chosen videos", r[1].contains("\"id\":" + ytAdmin) && !r[1].contains("\"id\":" + ytCarol + ","));
        check("only the admin chooses TV videos", post("/api/videos/feature", carol, "id", "" + ytCarol)[0], "403");
        post("/api/videos/feature", admin, "id", "" + ytCarol);
        r = get("/api/videos?scope=tv", null);
        checkTrue("the admin puts a member's video on TV", r[1].indexOf("\"id\":" + ytAdmin) < r[1].indexOf("\"id\":" + ytCarol));
        post("/api/videos/move", admin, "id", "" + ytCarol, "dir", "up");
        r = get("/api/videos?scope=tv", null);
        checkTrue("and changes the order", r[1].indexOf("\"id\":" + ytCarol) < r[1].indexOf("\"id\":" + ytAdmin));

        System.out.println("-- Uploads --");
        boolean full = ff != null && fp != null;
        byte[] mp4Bytes;
        byte[] otherBytes;
        String otherName;
        if (full) {
            Path mp4 = dir.resolve("clip.mp4");
            Path webm = dir.resolve("clip.webm");
            exec(ff, "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=25", "-f", "lavfi", "-i", "sine", "-t", "4",
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", mp4.toString());
            exec(ff, "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=25", "-t", "3", "-c:v", "libvpx", webm.toString());
            mp4Bytes = Files.readAllBytes(mp4);
            otherBytes = Files.readAllBytes(webm);
            otherName = "dance.webm";
        } else {
            // No FFmpeg on this PC: MP4 files are kept as they are (the server converts other formats).
            System.out.println("  (FFmpeg is not on this PC - converting is checked on the server; the rest runs here)");
            mp4Bytes = new byte[5000];
            otherBytes = new byte[4000];
            otherName = "dance.mp4";
            check("without FFmpeg, other formats are refused politely", upload(dave, "dance.webm", otherBytes, "")[0], "409");
        }
        check("only video files", upload(carol, "notes.txt", "x".getBytes(StandardCharsets.UTF_8), "")[0], "400");
        r = upload(carol, "My trip.mp4", mp4Bytes, "&description=Day+one");
        checkTrue("a member uploads an MP4", r[0].equals("200") && r[1].contains("\"status\":\"processing\"") && r[1].contains("My trip"));
        long upMp4 = id(r[1]);
        r = upload(dave, otherName, otherBytes, "&title=Dance");
        long upWebm = id(r[1]);
        waitReady(upMp4, carol);
        waitReady(upWebm, dave);
        r = get("/api/videos/one?id=" + upMp4, carol);
        checkTrue("the MP4 is ready", r[1].contains("\"status\":\"ready\""));
        if (full) {
            checkTrue("with its length and a picture", r[1].contains("\"durationMs\":4") && r[1].contains("/api/videos/thumb/" + upMp4 + ".jpg"));
            r = get("/api/videos/one?id=" + upWebm, dave);
            checkTrue("other formats are converted to MP4", r[1].contains("\"status\":\"ready\"") && probe(fp, store.fileOf(store.get(upWebm))).contains("h264"));
            check("the picture is served", get("/api/videos/thumb/" + upMp4 + ".jpg", null)[0], "200");
        }
        r = rangeGet("/api/videos/file/" + upMp4 + ".mp4", "bytes=0-99");
        checkTrue("videos can be seeked (byte ranges)", r[0].equals("206") && r[1].length() == 100 && r[2].startsWith("bytes 0-99/"));

        System.out.println("-- Who may change what --");
        check("dave cannot edit carol's video", post("/api/videos/edit", dave, "id", "" + upMp4, "title", "Hacked")[0], "403");
        check("dave cannot delete carol's video", post("/api/videos/delete", dave, "id", "" + upMp4)[0], "403");
        r = post("/api/videos/edit", carol, "id", "" + upMp4, "title", "My trip to the coast", "description", "Day one and two");
        checkTrue("carol edits her own video", r[0].equals("200") && r[1].contains("My trip to the coast"));
        r = post("/api/videos/pause", carol, "id", "" + upMp4, "paused", "true");
        checkTrue("carol pauses her video", r[1].contains("\"paused\":\"owner\""));
        check("a paused video is hidden from others", get("/api/videos/one?id=" + upMp4, dave)[0], "404");
        check("and its file too", get("/api/videos/file/" + upMp4 + ".mp4", null)[0], "404");
        checkTrue("carol still sees it in her list", get("/api/videos?scope=mine", carol)[1].contains("\"id\":" + upMp4));
        post("/api/videos/pause", carol, "id", "" + upMp4, "paused", "false");
        check("and shows it again", get("/api/videos/one?id=" + upMp4, dave)[0], "200");
        r = post("/api/videos/pause", admin, "id", "" + upWebm, "paused", "true");
        checkTrue("the super-admin pauses dave's video", r[1].contains("\"paused\":\"admin\""));
        r = post("/api/videos/pause", dave, "id", "" + upWebm, "paused", "false");
        checkTrue("dave cannot undo the admin's pause", r[0].equals("403") && r[1].contains("paused by Memoryq"));

        System.out.println("-- Comments (posts on a video) --");
        check("signed-out people cannot comment", post("/api/videos/comment", null, "id", "" + upMp4, "text", "hi")[0], "401");
        r = post("/api/videos/comment", dave, "id", "" + upMp4, "text", "Great <b>trip</b>!");
        checkTrue("dave comments on carol's video", r[0].equals("200") && r[1].contains("Great \\u003cb>trip"));
        long c1 = Long.parseLong(find(r[1], "\"id\":(\\d+),\"author\":\"dave\""));
        r = post("/api/videos/comment/edit", dave, "id", "" + c1, "text", "Great trip!");
        checkTrue("dave edits his comment", r[1].contains("\"edited\":true") && r[1].contains("Great trip!"));
        check("carol cannot edit dave's words", post("/api/videos/comment/edit", carol, "id", "" + c1, "text", "x")[0], "403");
        r = post("/api/videos/comment", erinTok(sessions), "id", "" + upMp4, "text", "spam spam");
        long c2 = Long.parseLong(find(r[1], "\"id\":(\\d+),\"author\":\"erin\""));
        check("dave cannot delete erin's comment", post("/api/videos/comment/delete", dave, "id", "" + c2)[0], "403");
        r = post("/api/videos/comment/delete", carol, "id", "" + c2);
        checkTrue("carol removes a comment on her own video", r[0].equals("200") && !r[1].contains("spam"));
        r = get("/api/videos?scope=all", null);
        checkTrue("the public list shows ready, unpaused videos", r[1].contains("\"id\":" + upMp4) && !r[1].contains("\"id\":" + upWebm + ","));

        us.setDisabled("carol", true);
        checkTrue("a suspended person's videos are hidden", !get("/api/videos?scope=all", null)[1].contains("\"id\":" + upMp4 + ",")
                && get("/api/videos/file/" + upMp4 + ".mp4", null)[0].equals("404"));
        us.setDisabled("carol", false);
        System.out.println("-- Super-admin: one account, everything --");
        chat.createGroup("Carol fans", "carol", true);
        chat.post("g-carol-fans", "carol", "hello fans", 0);
        wall.create("carol", "My first post", null, "", false, false, now[0]);
        check("not for members", get("/api/admin/user360?u=carol", carol)[0], "403");
        r = get("/api/admin/user360?u=carol", admin);
        checkTrue("account, videos, comments, wall posts, chat and groups in one view", r[0].equals("200")
                && r[1].contains("\"username\":\"carol\"") && r[1].contains("My trip to the coast") && r[1].contains("Carol's clip")
                && r[1].contains("My first post") && r[1].contains("hello fans") && r[1].contains("\"as\":\"owner\""));
        checkTrue("dave's comments show on his view", get("/api/admin/user360?u=dave", admin)[1].contains("Great trip!"));
        check("unknown account", get("/api/admin/user360?u=nobody", admin)[0], "404");

        System.out.println("-- Deleting --");
        check("carol deletes her video", post("/api/videos/delete", carol, "id", "" + upMp4)[0], "200");
        checkTrue("its file and picture are gone", !Files.exists(store.dir().resolve(upMp4 + ".mp4")) && !Files.exists(store.thumbOf(upMp4)));
        us.deleteAccount("dave");
        checkTrue("a deleted account's videos are removed", store.purge(u -> us.info(u) != null) == 1 && store.get(upWebm) == null);
        VideoStore again = new VideoStore(dir.resolve("videos.txt"), dir.resolve("video-comments.txt"), dir.resolve("videos"));
        again.init();
        checkTrue("videos are kept after a restart", again.get(ytCarol) != null && again.get(ytCarol).featured);

        server.stop();
    }

    private static String erinTok(SessionStore s) {
        return s.create("erin", UserStore.Role.CHATTER);
    }

    private static void waitReady(long id, String tok) throws Exception {
        for (int i = 0; i < 120; i++) {
            String b = get("/api/videos/one?id=" + id, tok)[1];
            if (!b.contains("\"status\":\"processing\"")) {
                return;
            }
            Thread.sleep(250);
        }
    }

    private static void exec(String... cmd) throws Exception {
        new ProcessBuilder(cmd).inheritIO().start().waitFor();
    }

    private static String probe(String fp, Path f) throws Exception {
        Process p = new ProcessBuilder(fp, "-v", "error", "-show_entries", "stream=codec_name", "-of", "csv=p=0", f.toString()).start();
        String s = new String(readAll(p.getInputStream()), StandardCharsets.UTF_8);
        p.waitFor();
        return s;
    }

    private static long id(String json) {
        return Long.parseLong(find(json, "\"id\":(\\d+)"));
    }

    private static String find(String s, String re) {
        Matcher m = Pattern.compile(re).matcher(s);
        return m.find() ? m.group(1) : "0";
    }

    private static String[] get(String path, String tok) throws Exception {
        return http("GET", path, null, tok, null);
    }

    private static String[] rangeGet(String path, String range) throws Exception {
        return http("GET", path, null, null, range);
    }

    private static String[] post(String path, String tok, String... kv) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            sb.append(i > 0 ? "&" : "").append(URLEncoder.encode(kv[i], "UTF-8")).append('=').append(URLEncoder.encode(kv[i + 1], "UTF-8"));
        }
        return http("POST", path, sb.toString().getBytes(StandardCharsets.UTF_8), tok, null);
    }

    private static String[] upload(String tok, String name, byte[] data, String extra) throws Exception {
        return http("POST", "/api/videos/upload?name=" + URLEncoder.encode(name, "UTF-8") + extra, data, tok, null);
    }

    private static String[] http(String method, String path, byte[] body, String tok, String range) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
        c.setReadTimeout(20000);
        if (tok != null) {
            c.setRequestProperty("Authorization", "Bearer " + tok);
        }
        if (range != null) {
            c.setRequestProperty("Range", range);
        }
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.getOutputStream().write(body);
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        byte[] b = in == null ? new byte[0] : readAll(in);
        String cr = c.getHeaderField("Content-Range");
        return new String[] {String.valueOf(code), new String(b, StandardCharsets.ISO_8859_1).startsWith("{") ? new String(b, StandardCharsets.UTF_8)
                : new String(b, StandardCharsets.ISO_8859_1), cr == null ? "" : cr};
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
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
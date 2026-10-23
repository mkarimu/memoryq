package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Streamers: each stream's length, totals and loyalty; the admin keeps a stream as a video, puts it on TV and takes it
 * off again, or takes it down; members see their own; e-mails and the news feed when someone goes live; an anonymous
 * tester registers and keeps their streams.
 */
public final class StreamersTest {

    private static int failures = 0;
    private static final List<String> smtpMails = Collections.synchronizedList(new ArrayList<String>());

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
        Path dir = Files.createTempDirectory("memoryq-streamers");
        long base = (System.currentTimeMillis() - 5L * 3600000L) / 1000L * 1000L;
        long[] now = {base};
        UserStore us = new UserStore(dir.resolve("users.txt"), new LiveChannelRegistry(), c -> { });
        us.init(dir.resolve("none.txt"));
        us.createUser("nelson", "password123", UserStore.Role.CHATTER);
        us.createUser("amina", "password123", UserStore.Role.CHATTER);
        us.createUser("tester_123456", "TesterPass99", UserStore.Role.CHATTER);
        SessionStore sessions = new SessionStore();
        String admin = sessions.create("admin", UserStore.Role.ADMIN);
        String nelson = sessions.create("nelson", UserStore.Role.CHATTER);
        String amina = sessions.create("amina", UserStore.Role.CHATTER);
        String tester = sessions.create("tester_123456", UserStore.Role.CHATTER);

        ServerSocket smtp = new ServerSocket(0);
        Thread st = new Thread(() -> fakeSmtp(smtp));
        st.setDaemon(true);
        st.start();
        Mailer mailer = new Mailer("127.0.0.1", smtp.getLocalPort(), "no-reply@memoryq.com", "Memoryq", "support@memoryq.com", false);
        EmailService emails = new EmailService(dir.resolve("emails.txt"), us, mailer, () -> now[0]);
        emails.init();
        emails.adminSet("nelson", "nelson@example.com");

        StreamLog log = new StreamLog(dir.resolve("stream-log.txt"), () -> now[0]);
        log.init();
        StreamHistory history = new StreamHistory(dir.resolve("stream-history.txt"), () -> now[0]);
        history.init();
        Path recDir = dir.resolve("recordings");
        VideoStore videos = new VideoStore(dir.resolve("videos.txt"), dir.resolve("video-comments.txt"), dir.resolve("videos"));
        videos.init();
        VideoApi videoApi = new VideoApi(videos, us, sessions, () -> now[0]);
        videoApi.setTools(() -> null, () -> null);
        Recordings rec = new Recordings(recDir, videoApi, us, sessions, () -> now[0]);
        rec.setHistory(history);
        rec.setLog(log);
        List<String> sent = Collections.synchronizedList(new ArrayList<String>());
        List<String> kicked = Collections.synchronizedList(new ArrayList<String>());
        StreamersApi api = new StreamersApi(log, us, sessions, () -> now[0]).recordings(rec).videos(videos).history(history)
                .emails(emails).kick(kicked::add)
                .mail((to, subject, text) -> sent.add(to + " | " + subject + " | " + text), u -> emails.get(u) == null ? null : emails.get(u).email,
                        "boss@memoryq.com", "memoryq.com")
                .alertsFile(dir.resolve("stream-alerts.txt"));
        NewsService news = new NewsService(dir.resolve("news.txt"), () -> now[0], null, null, 100, 5);
        news.init();
        NewsApi newsApi = new NewsApi(news, us, sessions);
        newsApi.setPinned(api::liveNews);
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        api.register(http);
        newsApi.register(http);
        rec.register(http);
        http.start();
        int port = http.getAddress().getPort();
        Set<String> liveChannels = new HashSet<String>();
        try {
            System.out.println("-- Members ask for an e-mail when someone goes live --");
            String[] r = call(port, "POST", "/api/me/streams/alerts", "on=1", nelson);
            checkTrue("nelson switches it on", r[0].equals("200") && r[1].contains("\"alerts\":true"));

            System.out.println("-- A stream: start, a reconnect, the end --");
            StreamLog.Session a = log.started("CHANNEL-101", "nelson", "Morning show");
            liveChannels.add("CHANNEL-101");
            history.started("CHANNEL-101", "nelson", "Morning show");
            waitFor(() -> sent.size() >= 1);
            checkTrue("the admin gets an e-mail with the link (" + sent.size() + ")", has(sent, "boss@memoryq.com | Live now on Memoryq: nelson - Morning show")
                    && has(sent, "https://memoryq.com/tv#CHANNEL-101"));
            checkTrue("nelson does not get an e-mail about himself", !has(sent, "nelson@example.com"));
            r = call(port, "GET", "/api/news?seed=1&offset=0&limit=10", null, null);
            checkTrue("first in the news feed at once: LIVE NOW (" + r[1] + ")", r[0].equals("200") && r[1].contains("LIVE NOW: nelson - Morning show")
                    && r[1].contains("\"live\":true") && r[1].contains("https://memoryq.com/tv#CHANNEL-101"));
            now[0] += 60000;
            log.tick(liveChannels::contains);
            liveChannels.remove("CHANNEL-101");
            now[0] += 30000;
            log.tick(liveChannels::contains);
            StreamLog.Session again = log.started("CHANNEL-101", "nelson", "Morning show");
            liveChannels.add("CHANNEL-101");
            checkTrue("a reconnect within 2 minutes is the same stream", again.id == a.id && log.list(null, true, 10).size() == 1);
            for (int m = 2; m <= 30; m++) {
                now[0] = base + m * 60000L;
                log.tick(liveChannels::contains);
            }
            liveChannels.remove("CHANNEL-101");
            now[0] = base + 30 * 60000L + 30000L;
            log.tick(liveChannels::contains);
            checkTrue("not ended after 30 seconds off", log.get(a.id).live());
            now[0] = base + 31 * 60000L + 30000L;
            log.tick(liveChannels::contains);
            StreamLog.Session done = log.get(a.id);
            checkTrue("ended when last seen: 30 minutes", !done.live() && done.durationMs(now[0]) == 30L * 60000L);
            r = call(port, "GET", "/api/news?seed=1&offset=0&limit=10", null, null);
            checkTrue("and gone from the news feed", !r[1].contains("LIVE NOW"));
            Path recA = recording(recDir, "CHANNEL-101", base, base + 30 * 60000L);

            System.out.println("-- Totals and loyalty --");
            now[0] = base + 60 * 60000L;
            StreamLog.Session b = log.started("CHANNEL-101", "nelson", "Evening show");
            checkTrue("an hour later: a new stream", b.id != a.id);
            for (int m = 61; m <= 180; m++) {
                now[0] = base + m * 60000L;
                log.tick(c -> true);
            }
            now[0] += 120000;
            log.tick(c -> false);
            StreamLog.Totals t = log.totals("nelson");
            checkTrue("2 streams, 2 h 30 min, longest 2 h (" + t.totalMs / 60000 + " min)", t.streams == 2 && t.totalMs == 150L * 60000L
                    && t.longestMs == 120L * 60000L && t.averageMs() == 75L * 60000L);
            checkTrue("points: a minute each + 10 a stream = 170, Bronze (" + t.points + " " + t.level + ")", t.points == 170 && "Bronze".equals(t.level)
                    && "Silver".equals(t.nextLevel) && t.pointsToNext == 330);
            StreamLog reread = new StreamLog(dir.resolve("stream-log.txt"), () -> now[0]);
            reread.init();
            checkTrue("kept across a restart", reread.totals("nelson").points == 170 && reread.list(null, true, 10).size() == 2);

            System.out.println("-- The admin's Streamers page --");
            checkTrue("admins only", call(port, "GET", "/api/admin/streamers/list", null, nelson)[0].equals("403"));
            r = call(port, "GET", "/api/admin/streamers/list", null, admin);
            checkTrue("everyone who streamed, with time and loyalty", r[0].equals("200") && r[1].contains("\"user\":\"nelson\"")
                    && r[1].contains("\"streams\":2") && r[1].contains("\"points\":170") && r[1].contains("\"level\":\"Bronze\""));
            r = call(port, "GET", "/api/admin/streamers/sessions?user=nelson", null, admin);
            checkTrue("nelson's streams with the recording", r[0].equals("200") && r[1].contains("Morning show") && r[1].contains("Evening show")
                    && r[1].contains("CHANNEL-101/") && r[1].contains("\"video\":null"));

            System.out.println("-- Keep as video, on TV, off TV, on again --");
            checkTrue("TV needs a kept video first", call(port, "POST", "/api/admin/streamers/tv", "session=" + a.id + "&on=1", admin)[0].equals("409"));
            r = call(port, "POST", "/api/admin/streamers/keep", "session=" + a.id + "&tv=1", admin);
            checkTrue("Keep + TV (" + r[1] + ")", r[0].equals("200") && r[1].contains("\"onTv\":true") && !Files.exists(recA));
            long vid = log.get(a.id).videoId;
            checkTrue("the video belongs to nelson, titled after the stream", vid > 0 && videos.get(vid).owner.equals("nelson")
                    && videos.get(vid).title.equals("Morning show"));
            r = call(port, "POST", "/api/admin/streamers/tv", "session=" + a.id + "&on=0", admin);
            checkTrue("off TV: still a video", r[0].equals("200") && r[1].contains("\"onTv\":false") && !videos.get(vid).featured);
            r = call(port, "POST", "/api/admin/streamers/tv", "session=" + a.id + "&on=1", admin);
            checkTrue("and back on TV", r[0].equals("200") && videos.get(vid).featured);

            System.out.println("-- Taking a stream down --");
            sent.clear();
            now[0] += 60000;
            StreamLog.Session bad = log.started("CHANNEL-102", "amina", "Not OK");
            waitFor(() -> sent.size() >= 2);
            checkTrue("amina goes live: the admin and nelson (asked for it) get an e-mail", has(sent, "boss@memoryq.com")
                    && has(sent, "nelson@example.com | amina is live on Memoryq"));
            Path recBad = recording(recDir, "CHANNEL-102", now[0], now[0] + 1000);
            Files.setLastModifiedTime(recBad, FileTime.fromMillis(System.currentTimeMillis()));
            r = call(port, "POST", "/api/admin/streamers/delete", "session=" + bad.id, admin);
            checkTrue("the admin takes it down: stopped, recording deleted, not counted", r[0].equals("200") && kicked.contains("CHANNEL-102")
                    && !Files.exists(recBad) && log.get(bad.id).removed && log.totals("amina").streams == 0);
            r = call(port, "GET", "/api/news?seed=1&offset=0&limit=10", null, null);
            checkTrue("not in the news feed", !r[1].contains("Not OK"));
            int before = sent.size();
            log.started("CHANNEL-103", "amina", "Again");
            Thread.sleep(300);
            checkTrue("no second e-mail about amina within 30 minutes", sent.size() == before);

            System.out.println("-- Members see their own --");
            r = call(port, "GET", "/api/me/streams", null, nelson);
            checkTrue("nelson: totals, loyalty, streams, the video on TV", r[0].equals("200") && r[1].contains("\"points\":170")
                    && r[1].contains("\"level\":\"Bronze\"") && r[1].contains("\"anonymous\":false") && r[1].contains("\"onTv\":true"));
            checkTrue("not someone else's", !r[1].contains("amina"));
            checkTrue("cannot take down someone else's", call(port, "POST", "/api/me/streams/delete", "session=" + b.id, amina)[0].equals("404"));
            r = call(port, "POST", "/api/me/streams/delete", "session=" + b.id, nelson);
            checkTrue("takes down his own", r[0].equals("200") && log.get(b.id).removed && log.totals("nelson").streams == 1);

            System.out.println("-- An anonymous tester registers and keeps the streams --");
            now[0] += 3600000L;
            log.started("CHANNEL-104", "tester_123456", "Test run");
            history.started("CHANNEL-104", "tester_123456", "Test run");
            r = call(port, "GET", "/api/me/streams", null, tester);
            checkTrue("the tester is anonymous", r[1].contains("\"anonymous\":true"));
            checkTrue("members with an account do not need it", call(port, "POST", "/api/vstream/claim",
                    "username=x&password=password123&email=x%40example.com", nelson)[0].equals("409"));
            r = call(port, "POST", "/api/vstream/claim", "username=joyce&password=JoycePass77&email=joyce%40example.com", tester);
            checkTrue("a code is sent (" + r[1] + ")", r[0].equals("200") && r[1].contains("\"verify\":true"));
            waitFor(() -> !smtpMails.isEmpty());
            String code = code(smtpMails.get(smtpMails.size() - 1));
            checkTrue("someone else cannot finish it", call(port, "POST", "/api/vstream/claim/verify", "username=joyce&code=" + code, amina)[0].equals("409"));
            r = call(port, "POST", "/api/vstream/claim/verify", "username=joyce&code=" + code, tester);
            checkTrue("registered: a new login (" + r[1] + ")", r[0].equals("200") && r[1].contains("\"username\":\"joyce\"") && r[1].contains("\"token\""));
            checkTrue("the streams moved to joyce, the tester login is closed", log.totals("joyce").streams == 1 && log.totals("tester_123456").streams == 0
                    && us.info("tester_123456") == null && us.authenticate("joyce", "JoycePass77") != null
                    && "joyce".equals(history.at("CHANNEL-104", now[0]).user));
            checkTrue("the old tester session no longer works", call(port, "GET", "/api/me/streams", null, tester)[0].equals("401"));
        } finally {
            http.stop(0);
            smtp.close();
        }
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** A finished recording file named like MediaMTX does (the start in local time), last written at the end. */
    private static Path recording(Path recDir, String channel, long start, long end) throws Exception {
        String name = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").format(Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()))
                + "-000001.mp4";
        Path f = recDir.resolve("live").resolve(channel).resolve(name);
        Files.createDirectories(f.getParent());
        Files.write(f, new byte[400 * 1024]);
        Files.setLastModifiedTime(f, FileTime.fromMillis(end));
        return f;
    }

    private interface Cond {
        boolean ok();
    }

    private static void waitFor(Cond c) throws InterruptedException {
        for (int i = 0; i < 100 && !c.ok(); i++) {
            Thread.sleep(50);
        }
    }

    private static boolean has(List<String> list, String part) {
        synchronized (list) {
            for (String s : list) {
                if (s.contains(part)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String code(String mail) {
        int i = mail.indexOf("\n\n");
        String body = new String(Base64.getMimeDecoder().decode(mail.substring(i + 2)), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\\n    (\\d{6})\\n").matcher(body.replace("\r\n", "\n"));
        return m.find() ? m.group(1) : "";
    }

    private static void fakeSmtp(ServerSocket server) {
        while (true) {
            try (Socket s = server.accept()) {
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                OutputStream out = s.getOutputStream();
                say(out, "220 fake ESMTP");
                String line;
                StringBuilder data = null;
                while ((line = in.readLine()) != null) {
                    if (data != null) {
                        if (line.equals(".")) {
                            smtpMails.add(data.toString());
                            data = null;
                            say(out, "250 queued");
                        } else {
                            data.append(line).append('\n');
                        }
                        continue;
                    }
                    if (line.startsWith("EHLO")) {
                        say(out, "250-fake\r\n250 8BITMIME");
                    } else if (line.equals("DATA")) {
                        data = new StringBuilder();
                        say(out, "354 go");
                    } else if (line.equals("QUIT")) {
                        say(out, "221 bye");
                        break;
                    } else {
                        say(out, "250 ok");
                    }
                }
            } catch (Exception e) {
                if (server.isClosed()) {
                    return;
                }
            }
        }
    }

    private static void say(OutputStream out, String s) throws Exception {
        out.write((s + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static String[] call(int port, String method, String path, String body, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
        c.setRequestProperty("Connection", "close"); // a fresh connection each time (Windows resets reused ones)
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
        return new String[]{String.valueOf(code), new String(b.toByteArray(), StandardCharsets.UTF_8)};
    }

    private static void checkTrue(String name, boolean ok) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + name);
        if (!ok) {
            failures++;
        }
    }
}
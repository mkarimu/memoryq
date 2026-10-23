package com.vstream.Vidiserver;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Tests the cross-source duplicate check and the "written by AI" flag. */
public class NewsDedupeTest {

    private static int failures = 0;

    static final class AiSummarizer implements NewsService.Summarizer {
        int calls = 0;

        @Override
        public NewsService.Summary summarize(String sourceName, String title, String description) {
            calls++;
            return new NewsService.Summary("H: " + title, "S: " + title, "world", 1, 1, true);
        }

        @Override
        public boolean ready() {
            return true;
        }

        @Override
        public String model() {
            return "ai";
        }
    }

    public static void main(String[] args) throws Exception {
        canonicalTests();
        crossSourceTests();
        flagTests();
        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
    }

    private static String canon(String s) {
        return NewsService.canonicalLink(s);
    }

    private static void canonicalTests() {
        System.out.println();
        System.out.println("-- Cleaned links --");
        check("tracking tags, www. and a trailing slash are ignored",
                canon("https://www.bbc.co.uk/sport/football/articles/abc?at_medium=RSS&at_campaign=rss")
                        .equals(canon("https://bbc.co.uk/sport/football/articles/abc/")));
        check("utm tags are ignored", canon("https://x.com/a?utm_source=f&utm_medium=g").equals(canon("https://x.com/a")));
        check("a real parameter is kept", !canon("https://x.com/a?id=1").equals(canon("https://x.com/a?id=2")));
        check("parameter order does not matter", canon("https://x.com/a?b=2&a=1").equals(canon("https://x.com/a?a=1&b=2")));
        check("different articles differ", !canon("https://x.com/a").equals(canon("https://x.com/b")));
        check("different hosts differ", !canon("https://x.com/a").equals(canon("https://y.com/a")));
        check("the host is not case sensitive", canon("https://EXAMPLE.com/a").equals(canon("https://example.com/a")));
        check("junk does not crash", canon(null).isEmpty() && !canon("not a url at all").isEmpty());
    }

    private static byte[] rss(long nowMs, String[][] stories) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>x</title>");
        for (int i = 0; i < stories.length; i++) {
            long when = nowMs - i * 60000L;
            sb.append("<item><title>").append(stories[i][0]).append("</title><link>")
                    .append(stories[i][1].replace("&", "&amp;")).append("</link><description>About ").append(stories[i][0])
                    .append("</description><guid>").append(stories[i][1].replace("&", "&amp;")).append("</guid><pubDate>")
                    .append(DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(when).atOffset(ZoneOffset.UTC)))
                    .append("</pubDate></item>");
        }
        return sb.append("</channel></rss>").toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void crossSourceTests() throws Exception {
        System.out.println();
        System.out.println("-- The same story from two feeds --");
        Path dir = Files.createTempDirectory("vstream-dedupe");
        AtomicLong now = new AtomicLong(System.currentTimeMillis());
        NewsTest.FakeFetcher ff = new NewsTest.FakeFetcher();
        ff.feeds.put("https://a.example/rss", rss(now.get(), new String[][]{
            {"Shared story", "https://www.example.com/s/1?at_medium=RSS&at_campaign=rss"},
            {"Only in A", "https://example.com/s/2"}}));
        ff.feeds.put("https://b.example/rss", rss(now.get(), new String[][]{
            {"Shared story again", "https://example.com/s/1?utm_source=feed"},
            {"Only in B", "https://example.com/s/3"}}));
        AiSummarizer ai = new AiSummarizer();
        NewsService ns = new NewsService(dir.resolve("news.txt"), now::get, ff, ai, 1000, 10);
        ns.init();
        ns.addSource("Alpha", "https://a.example/rss");
        ns.addSource("Beta", "https://b.example/rss");
        ns.refreshAll();
        check("the shared story is stored once", ns.allItems(100).size() == 3);
        check("and it was only summarised once", ai.calls == 3);
        ns.refreshAll();
        check("a second refresh adds nothing and calls nothing", ns.allItems(100).size() == 3 && ai.calls == 3);
        NewsService.Item shared = null;
        for (NewsService.Item it : ns.allItems(100)) {
            if (it.headline.contains("Shared")) {
                shared = it;
            }
        }
        ns.setHidden(shared.id, true);
        ns.refreshAll();
        check("a hidden story is not added again from the other feed", ns.allItems(100).size() == 3 && ai.calls == 3);

        ff.feeds.put("https://c.example/rss", rss(now.get(), new String[][]{
            {"Twice in one feed", "https://example.com/t/1"},
            {"Twice in one feed (copy)", "https://www.example.com/t/1/?at_medium=RSS"}}));
        ns.addSource("Gamma", "https://c.example/rss");
        int before = ai.calls;
        ns.refreshAll();
        check("two copies inside one feed give one story", ns.allItems(100).size() == 4 && ai.calls == before + 1);

        System.out.println();
        System.out.println("-- Copies already saved --");
        Path old = Files.createTempFile("vstream-old", ".txt");
        long t = now.get();
        String file = "N\t2\t3\n"
                + "S\t1\tA\thttps://a.example/rss\t1\t0\t\n"
                + "I\t1\t1\tA\thttps://x.com/a?utm_source=1\tHeadline one\tSummary one\tworld\t0\t" + t + "\t0\tkey1\n"
                + "I\t2\t1\tA\thttps://www.x.com/a\tHeadline two\tSummary two\tworld\t0\t" + t + "\t0\tkey2\n"
                + "I\t3\t1\tA\thttps://x.com/b\tHeadline three\tSummary three\tworld\t0\t" + t + "\t0\tkey3\n";
        Files.write(old, file.getBytes(StandardCharsets.UTF_8));
        NewsService loaded = new NewsService(old, now::get, ff, ai, 1000, 10);
        loaded.init();
        List<NewsService.Item> items = loaded.allItems(100);
        check("a copy already in the file is removed when the server starts, the oldest is kept",
                items.size() == 2 && containsHeadline(items, "Headline one") && !containsHeadline(items, "Headline two"));
        check("stories saved before this change are not marked as AI", !items.get(0).ai && !items.get(1).ai);
    }

    private static boolean containsHeadline(List<NewsService.Item> items, String h) {
        for (NewsService.Item it : items) {
            if (it.headline.equals(h)) {
                return true;
            }
        }
        return false;
    }

    private static void flagTests() throws Exception {
        System.out.println();
        System.out.println("-- Who wrote the summary --");
        check("an AI answer is marked as AI", OpenAiSummarizer.parseAnswer("HEADLINE: h\nSUMMARY: s\nTOPIC: tech", 1, 1).ai);
        check("the built-in summary is not", !new ExtractiveSummarizer().summarize("X", "A headline", "A decent sentence about prices rising today.").ai);

        Path dir = Files.createTempDirectory("vstream-flag");
        AtomicLong now = new AtomicLong(System.currentTimeMillis());
        NewsTest.FakeFetcher ff = new NewsTest.FakeFetcher();
        ff.feeds.put("https://a.example/rss", rss(now.get(), new String[][]{{"Story one", "https://example.com/f/1"}}));
        NewsService ns = new NewsService(dir.resolve("news.txt"), now::get, ff, new AiSummarizer(), 1000, 10);
        ns.init();
        ns.addSource("Alpha", "https://a.example/rss");
        ns.refreshAll();
        check("a story written by the AI carries the flag", ns.allItems(10).get(0).ai);
        NewsService again = new NewsService(dir.resolve("news.txt"), now::get, ff, new AiSummarizer(), 1000, 10);
        again.init();
        check("and keeps it after a restart", again.allItems(10).size() == 1 && again.allItems(10).get(0).ai);

        Path dir2 = Files.createTempDirectory("vstream-flag2");
        NewsService builtIn = new NewsService(dir2.resolve("news.txt"), now::get, ff, new ExtractiveSummarizer(), 1000, 10);
        builtIn.init();
        builtIn.addSource("Alpha", "https://a.example/rss");
        builtIn.refreshAll();
        check("a built-in story does not", builtIn.allItems(10).size() == 1 && !builtIn.allItems(10).get(0).ai);

        LiveChannelRegistry reg = new LiveChannelRegistry();
        UserStore us = new UserStore(dir.resolve("users.txt"), reg, id -> { });
        us.init(null);
        ChannelListHttpServer server = new ChannelListHttpServer(reg, 0);
        server.setNewsApi(new NewsApi(ns, us, new SessionStore()));
        server.start();
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + server.getPort() + "/api/news?limit=5").openConnection();
        InputStream in = c.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        check("the public feed carries the flag", sb.toString().contains("\"ai\":true") && !sb.toString().contains("hidden"));
        server.stop();
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            System.out.println("PASS  " + name);
        } else {
            failures++;
            System.out.println("FAIL  " + name);
        }
    }
}
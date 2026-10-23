package com.vstream.Vidiserver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Tests the built-in summarizer, the AI-or-built-in chain, and the RSS output. */
public class EngineTest {

    private static int failures = 0;
    private static final long HOUR = 3600000L;

    static final class FakeAi implements NewsService.Summarizer {
        boolean ready = true;
        int calls = 0;
        String mode = "ok";

        @Override
        public NewsService.Summary summarize(String sourceName, String title, String description) throws IOException {
            calls++;
            switch (mode) {
                case "auth":
                    throw new NewsService.AuthException("key refused (HTTP 401)");
                case "limit":
                    throw new NewsService.LimitException("quota");
                case "io":
                    throw new IOException("HTTP 400: Unsupported parameter: reasoning");
                case "skip":
                    return null;
                case "generic":
                    return new NewsService.Summary("Headline", "The situation carries significance for everyone.", "world", 1, 1);
                default:
                    return new NewsService.Summary("AI headline", "AI summary.", "tech", 10, 5);
            }
        }

        @Override
        public boolean ready() {
            return ready;
        }

        @Override
        public String model() {
            return "ai-model";
        }
    }

    public static void main(String[] args) throws Exception {
        textTests();
        engineTests();
        chainTests();
        serviceAndRssTests();
        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
    }

    private static void textTests() {
        System.out.println();
        System.out.println("-- Cleaning and splitting --");
        List<String> s = ExtractiveSummarizer.splitSentences(
                "The U.S. Army said on Monday it would pay $4.5 million. Dr. Smith agreed. \"No,\" he said. Next sentence here.");
        check("abbreviations and decimals do not split sentences",
                s.size() == 4 && s.get(0).startsWith("The U.S. Army") && s.get(0).endsWith("million.") && s.get(1).startsWith("Dr. Smith"));
        s = ExtractiveSummarizer.splitSentences("J. K. Rowling wrote it. She agreed.");
        check("initials do not split sentences", s.size() == 2);

        check("a site suffix is removed from the headline",
                "Government announces new housing plan".equals(ExtractiveSummarizer.cleanHeadline("Government announces new housing plan - BBC News", "BBC News")));
        check("the same with a shorter source name",
                "Government announces new housing plan".equals(ExtractiveSummarizer.cleanHeadline("Government announces new housing plan | BBC News", "BBC")));
        check("a WATCH: prefix is removed", "Rocket lifts off".equals(ExtractiveSummarizer.cleanHeadline("WATCH: Rocket lifts off", "X")));
        check("a dash inside a headline is kept",
                "Fans - and players - react to the result".equals(ExtractiveSummarizer.cleanHeadline("Fans - and players - react to the result", "Example News")));
        String longTitle = repeat("word ", 60);
        String ct = ExtractiveSummarizer.cleanHeadline(longTitle, "X");
        check("a long headline is cut with an ellipsis", ct.length() <= 120 && ct.endsWith("\u2026"));

        check("a wire dateline, a time and an advert label are removed",
                "Prices rose sharply.".equals(ExtractiveSummarizer.cleanDescription("LONDON (Reuters) - Prices rose sharply. 5 hours ago Advertisement")));
        check("a WordPress footer is removed",
                "A short intro here.".equals(ExtractiveSummarizer.cleanDescription("A short intro here. The post Big Story appeared first on Example Blog.")));
        check("a read-more line is removed",
                "Prices rose.".equals(ExtractiveSummarizer.cleanDescription("Prices rose. Read more about it here.")));

        List<String> kept = ExtractiveSummarizer.filterSentences(Arrays.asList(
                "Prices rise sharply in October.",
                "Inflation hit 4.2% as food costs climbed, the statistics office said.",
                "Read more about this story.",
                "Inflation hit 4.2% as food costs climbed, the statistics office said.",
                "Sign up for our newsletter.",
                "Short bit"), "Prices rise sharply in October");
        check("a repeat of the headline, boilerplate, duplicates and fragments are dropped",
                kept.size() == 1 && kept.get(0).startsWith("Inflation hit 4.2%"));
        check("generic filler is recognised", ExtractiveSummarizer.looksGeneric("The situation carries significance for public understanding of the subject.")
                && !ExtractiveSummarizer.looksGeneric("The council voted nine to four on Tuesday."));

        List<String> five = Arrays.asList(
                "The council voted nine to four on Tuesday to approve twelve miles of new cycle lanes across the city.",
                "Work is due to start in March and should be finished by the autumn, officials said.",
                "Local businesses had raised concerns about parking during the consultation period.",
                "Cycling groups welcomed the decision.");
        String p = ExtractiveSummarizer.pick(five, "Council approves new cycle lanes");
        check("the summary keeps the lead sentence, at most two sentences and 320 characters",
                p.contains("voted nine to four") && p.length() <= 320 && ExtractiveSummarizer.splitSentences(p).size() <= 2);
        String longOne = "The ministry said " + repeat("many different officials ", 30) + "agreed.";
        String cut = ExtractiveSummarizer.pick(Arrays.asList(longOne), "x");
        check("one very long sentence is cut to fit", cut.length() <= 320 && cut.endsWith("\u2026"));

        check("sports", "sports".equals(ExtractiveSummarizer.classify("Football club signs striker ahead of the new season", "")));
        check("science", "science".equals(ExtractiveSummarizer.classify("Scientists discover new planet using telescope", "")));
        check("tech", "tech".equals(ExtractiveSummarizer.classify("Apple unveils new iPhone with AI chip", "")));
        check("politics", "politics".equals(ExtractiveSummarizer.classify("Senate votes on election bill", "")));
        check("business", "business".equals(ExtractiveSummarizer.classify("Stocks fall as inflation worries investors", "")));
        check("health", "health".equals(ExtractiveSummarizer.classify("Hospital doctors warn of flu outbreak", "")));
        check("entertainment", "entertainment".equals(ExtractiveSummarizer.classify("Film festival honours director", "")));
        check("world", "world".equals(ExtractiveSummarizer.classify("Ceasefire talks as troops leave border", "")));
        check("other", "other".equals(ExtractiveSummarizer.classify("Local bakery wins award for best bread", "")));
    }

    private static void engineTests() {
        System.out.println();
        System.out.println("-- The built-in summarizer --");
        ExtractiveSummarizer e = new ExtractiveSummarizer();
        NewsService.Summary s = e.summarize("Example News", "Council approves new cycle lanes - Example News",
                "The council voted 9-4 on Tuesday to approve 12 miles of new cycle lanes. Work starts in March and should finish by autumn. Read more");
        check("headline, summary and source rules work together",
                s != null && "Council approves new cycle lanes".equals(s.headline) && s.summary.contains("voted 9-4")
                        && !s.summary.contains("Read more") && s.summary.length() <= 320 && s.inTokens == 0);
        s = e.summarize("X", "A headline with no description", "");
        check("a story with no description becomes a headline-only card", s != null && s.summary.isEmpty());
        check("an empty title gives nothing", e.summarize("X", "", "Some text here that is long enough.") == null
                && e.summarize("X", "   ", "x") == null);
        s = e.summarize("X", "Company posts record profits",
                "The company reported record profits for the third quarter, beating analysts' expectations by a wide margin and sending its shares up. Executives said the strong performance was driven by growth in");
        check("a cut-off tail is dropped", s.summary.endsWith("shares up.") && !s.summary.contains("growth in"));
        s = e.summarize("X", "Company posts record profits",
                "Executives said the strong performance was driven by growth in overseas markets and cost cutting at");
        check("a single cut-off sentence is kept and marked with an ellipsis", !s.summary.isEmpty() && s.summary.endsWith("\u2026"));
        s = e.summarize("X", "Prices rise sharply in October", "Prices rise sharply in October");
        check("a description that only repeats the headline gives no summary", s.summary.isEmpty());
        s = e.summarize("X", "Something happened today", "The situation carries significance for public understanding of the subject.");
        check("generic filler is never used as a summary", s.summary.isEmpty());
    }

    private static void chainTests() throws Exception {
        System.out.println();
        System.out.println("-- The AI-or-built-in chain --");
        final AtomicLong now = new AtomicLong(System.currentTimeMillis());
        String title = "A decent headline about prices";
        String desc = "Prices rose by four percent last month. Officials said more rises were possible.";

        FakeAi ai = new FakeAi();
        ChainSummarizer ch = new ChainSummarizer(ai, new ExtractiveSummarizer(), now::get, 100);
        NewsService.Summary s = ch.summarize("Src", title, desc);
        check("with a working AI the AI answer is used", "AI headline".equals(s.headline) && ai.calls == 1 && ch.note().contains("AI calls today: 1"));
        check("the chain is always ready, and reports the AI", ch.ready() && ch.aiReady() && ch.model().contains("ai-model"));

        ai.mode = "auth";
        s = ch.summarize("Src", title, desc);
        check("a refused key falls back to the built-in summary", title.equals(s.headline) && ch.note().contains("refused"));
        int calls = ai.calls;
        ch.summarize("Src", title, desc);
        check("the AI is then paused", ai.calls == calls);
        now.addAndGet(6 * HOUR + 60000);
        ai.mode = "ok";
        s = ch.summarize("Src", title, desc);
        check("after the pause the AI is tried again", "AI headline".equals(s.headline) && ch.note().indexOf("refused") < 0);

        ai.mode = "limit";
        s = ch.summarize("Src", title, desc);
        check("a quota error falls back and says so", title.equals(s.headline) && ch.note().contains("quota"));
        calls = ai.calls;
        now.addAndGet(29 * 60000L);
        ch.summarize("Src", title, desc);
        check("the AI stays paused for 30 minutes", ai.calls == calls);
        now.addAndGet(2 * 60000L);
        ai.mode = "ok";
        ch.summarize("Src", title, desc);
        check("and comes back after that", ai.calls == calls + 1);

        ai.mode = "io";
        calls = ai.calls;
        ch.summarize("Src", title, desc);
        ch.summarize("Src", title, desc);
        s = ch.summarize("Src", title, desc);
        check("three ordinary failures pause the AI, and the real error is shown",
                ai.calls == calls + 3 && title.equals(s.headline) && ch.note().contains("Unsupported parameter") && ch.note().contains("paused"));
        ch.summarize("Src", title, desc);
        check("no more AI calls while paused", ai.calls == calls + 3);

        FakeAi ai2 = new FakeAi();
        ChainSummarizer ch2 = new ChainSummarizer(ai2, new ExtractiveSummarizer(), now::get, 100);
        ai2.mode = "skip";
        check("an AI answer of SKIP is respected", ch2.summarize("Src", title, desc) == null && ai2.calls == 1);
        ai2.mode = "generic";
        s = ch2.summarize("Src", title, desc);
        check("an AI answer that is generic filler is replaced by the built-in summary",
                title.equals(s.headline) && ch2.note().contains("generic"));

        FakeAi ai3 = new FakeAi();
        ChainSummarizer ch3 = new ChainSummarizer(ai3, new ExtractiveSummarizer(), now::get, 2);
        ch3.summarize("Src", title, desc);
        ch3.summarize("Src", title, desc);
        s = ch3.summarize("Src", title, desc);
        check("the AI daily cap is respected", ai3.calls == 2 && title.equals(s.headline) && ch3.note().contains("daily"));
        now.addAndGet(25 * HOUR);
        ch3.summarize("Src", title, desc);
        check("and resets the next day", ai3.calls == 3);

        FakeAi ai4 = new FakeAi();
        ai4.ready = false;
        ChainSummarizer ch4 = new ChainSummarizer(ai4, new ExtractiveSummarizer(), now::get, 100);
        s = ch4.summarize("Src", title, desc);
        check("without an AI key the built-in summarizer does all the work",
                title.equals(s.headline) && ai4.calls == 0 && ch4.ready() && !ch4.aiReady() && "built-in".equals(ch4.model()));
    }

    private static byte[] feedWithDescriptions(long nowMs) {
        String[][] rows = {
            {"Council approves new cycle lanes - Example News", "The council voted 9-4 on Tuesday to approve 12 miles of new cycle lanes. Work starts in March and should finish by autumn."},
            {"Football club signs striker for record fee", "The club confirmed the deal on Monday. The striker has signed a four-year contract."},
            {"Headline only story with no description", ""},
            {"Rates &lt; 5% &amp; falling \"fast\"", "Analysts expect further cuts next year, according to a survey of economists published on Friday."}
        };
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>x</title>");
        for (int i = 0; i < rows.length; i++) {
            long when = nowMs - i * 60000L;
            sb.append("<item><title>").append(rows[i][0]).append("</title><link>https://example.com/n/").append(i)
                    .append("</link><description>").append(rows[i][1]).append("</description><guid>g-").append(i)
                    .append("</guid><pubDate>")
                    .append(DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(when).atOffset(ZoneOffset.UTC)))
                    .append("</pubDate></item>");
        }
        return sb.append("</channel></rss>").toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void serviceAndRssTests() throws Exception {
        System.out.println();
        System.out.println("-- News without any AI key, and the RSS output --");
        Path dir = Files.createTempDirectory("vstream-engine");
        final AtomicLong now = new AtomicLong(System.currentTimeMillis());
        NewsTest.FakeFetcher ff = new NewsTest.FakeFetcher();
        ff.feeds.put("https://a.example/rss", feedWithDescriptions(now.get()));
        FakeAi noKey = new FakeAi();
        noKey.ready = false;
        ChainSummarizer chain = new ChainSummarizer(noKey, new ExtractiveSummarizer(), now::get, 100);
        NewsService ns = new NewsService(dir.resolve("news.txt"), now::get, ff, chain, 1000, 10);
        ns.init();
        ns.addSource("Example News", "https://a.example/rss");
        check("a refresh is allowed without a key", ns.refreshBlocker() == null);
        ns.refreshAll();
        List<NewsService.Item> items = ns.allItems(100);
        check("all four stories were saved, with no AI call", items.size() == 4 && noKey.calls == 0);
        NewsService.Item cycle = byHeadline(items, "Council approves new cycle lanes");
        check("the first story has a clean headline and a short summary",
                cycle != null && cycle.summary.contains("voted 9-4") && cycle.summary.length() <= 320);
        NewsService.Item football = byHeadline(items, "Football club signs striker for record fee");
        check("the topic is worked out", football != null && "sports".equals(football.topic));
        NewsService.Item bare = byHeadline(items, "Headline only story with no description");
        check("a story with no description is a headline-only card", bare != null && bare.summary.isEmpty());
        check("characters in a headline come through intact", byHeadline(items, "Rates < 5% & falling \"fast\"") != null);
        check("every story keeps its source and a link to the original",
                cycle.source.equals("Example News") && cycle.link.startsWith("https://example.com/n/"));
        NewsService.Status st = ns.status();
        check("the status says there is no AI key and explains the built-in engine",
                !st.keyFound && "built-in".equals(st.model) && st.engineNote.contains("built-in"));

        LiveChannelRegistry reg = new LiveChannelRegistry();
        UserStore us = new UserStore(dir.resolve("users.txt"), reg, id -> { });
        us.init(null);
        SessionStore ss = new SessionStore();
        String adminTok = ss.create("admin", UserStore.Role.ADMIN);
        ChannelListHttpServer server = new ChannelListHttpServer(reg, 0);
        server.setNewsApi(new NewsApi(ns, us, ss));
        server.start();
        int port = server.getPort();

        String[] r = http(port, "GET", "/api/news.rss", null, null);
        check("the RSS feed is public and has the right type", r[0].equals("200") && r[2].contains("application/rss+xml"));
        List<RssParser.FeedItem> parsed = RssParser.parse(r[1].getBytes(StandardCharsets.UTF_8));
        check("our own parser reads it back, all four stories", parsed.size() == 4);
        boolean oddTitle = false;
        boolean linkOk = true;
        for (RssParser.FeedItem it : parsed) {
            oddTitle |= it.title.equals("Rates < 5% & falling \"fast\"");
            linkOk &= it.link.startsWith("https://example.com/n/");
        }
        check("special characters survive the round trip, and links point to the originals", oddTitle && linkOk);
        check("the XML is escaped", r[1].contains("&lt;") && r[1].contains("&amp;") && !r[1].contains("Rates < 5%"));
        check("topics are included", r[1].contains("<category>sports</category>"));
        ns.setHidden(football.id, true);
        r = http(port, "GET", "/api/news.rss", null, null);
        check("a hidden story leaves the RSS feed", RssParser.parse(r[1].getBytes(StandardCharsets.UTF_8)).size() == 3);
        r = http(port, "POST", "/api/news.rss", "", null);
        check("POST to the RSS feed is refused", r[0].equals("405"));
        r = http(port, "GET", "/api/news?limit=10", null, null);
        check("the JSON feed still works", r[0].equals("200") && r[1].contains("\"total\":3"));
        r = http(port, "GET", "/api/admin/news/status", null, adminTok);
        check("the admin status carries the engine note and no key", r[0].equals("200") && r[1].contains("\"engineNote\":\"built-in")
                && r[1].contains("\"keyFound\":false") && !r[1].contains("sk-"));
        server.stop();
    }

    private static NewsService.Item byHeadline(List<NewsService.Item> items, String headline) {
        for (NewsService.Item it : items) {
            if (it.headline.equals(headline)) {
                return it;
            }
        }
        return null;
    }

    private static String repeat(String part, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(part);
        }
        return sb.toString();
    }

    private static String[] http(int port, String method, String path, String body, String bearer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
        if (bearer != null) {
            c.setRequestProperty("Authorization", "Bearer " + bearer);
        }
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes(StandardCharsets.UTF_8));
            os.close();
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
        }
        String type = c.getHeaderField("Content-Type");
        c.disconnect();
        return new String[]{String.valueOf(code), sb.toString(), type == null ? "" : type};
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
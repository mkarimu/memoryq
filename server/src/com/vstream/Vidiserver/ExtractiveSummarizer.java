package com.vstream.Vidiserver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The built-in summarizer: no key, no network. It works only on what a feed publishes for syndication
 * (the title and the short description). It cleans the text, splits it into sentences, drops boilerplate,
 * duplicates and filler, scores what is left (lead position, length, headline overlap, word frequency,
 * numbers and names) and keeps at most two sentences. The topic comes from keyword lists.
 */
public final class ExtractiveSummarizer implements NewsService.Summarizer {

    static final int MAX_SUMMARY = 320;
    static final int MAX_HEADLINE = 120;

    private static final Set<String> ABBREVIATIONS = new HashSet<String>(Arrays.asList(
            "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "inc", "ltd", "corp",
            "gen", "sen", "rep", "gov", "lt", "col", "sgt", "capt", "rev", "hon", "jan", "feb", "mar", "apr",
            "jun", "jul", "aug", "sept", "oct", "nov", "dec"));

    private static final Set<String> STOP = new HashSet<String>(Arrays.asList(
            "the", "and", "for", "that", "with", "from", "this", "have", "has", "had", "are", "was", "were",
            "will", "would", "could", "should", "been", "being", "but", "not", "you", "your", "they", "their",
            "them", "his", "her", "she", "him", "its", "our", "who", "whom", "what", "when", "where", "which",
            "while", "than", "then", "there", "here", "about", "after", "before", "over", "under", "into",
            "out", "off", "any", "all", "can", "may", "might", "also", "more", "most", "said", "says", "say",
            "new", "one", "two", "just", "now", "very", "much", "many", "some", "such", "only", "other",
            "because", "since", "per", "via", "how", "why", "did", "does", "doing", "get", "got", "own"));

    private static final Pattern SENTENCE_END = Pattern.compile(
            "([.!?][\"'\u201D\u2019)\\]]*)\\s+(?=[\"'\u201C\u2018(\\[]?[A-Z0-9])");

    private static final Pattern[] REMOVALS = {
        Pattern.compile("(?i)\\bThe post\\b.{0,250}?\\bappeared first on\\b[^.]{0,120}\\.?"),
        Pattern.compile("(?i)\\b(?:continue reading|read more|read the full (?:story|article)|read full (?:story|article)|click here|view comments?)\\b[^.!?]{0,120}[.!?]?"),
        Pattern.compile("\\[(?:\\u2026|\\.\\.\\.)\\]"),
        Pattern.compile("(?i)\\b\\d+\\s+(?:seconds?|minutes?|hours?|days?|weeks?)\\s+ago\\b"),
        Pattern.compile("(?i)\\badvertisement\\b"),
        Pattern.compile("(?i)\\b(?:image source|image caption|photo credit|photo|credit)\\s*:\\s*[^.!?]{0,100}"),
        Pattern.compile("(?i)\\bsave\\s+add as preferred on google\\b|\\badd as preferred on google\\b"),
        Pattern.compile("(?i)\\b(?:share page|copy link|more on this story|related topics)\\b"),
        Pattern.compile("(?i)\\b(?:sign up (?:for|to)|subscribe (?:to|for|now)|follow us)\\b[^.!?]{0,100}[.!?]?")
    };

    private static final Pattern DATELINE = Pattern.compile(
            "^(?:[A-Z][A-Za-z .,'-]{1,40}\\s*)?\\((?:Reuters|AP|AFP|PA|AAP)\\)\\s*[-\\u2013\\u2014:]\\s*");

    private static final String[] BAD_FRAGMENTS = {
        "save add as preferred", "add as preferred on google", "image source", "image caption",
        "this content is not available", "there was an error", "your device may not support", "media playback",
        "bbc is not responsible", "external sites", "follow bbc", "share this", "copy link", "related topics",
        "more on this story", "advertisement", "sign up", "log in", "subscribe", "everything you need to know",
        "how to watch", "watch live", "listen live", "bbc iplayer", "bbc sounds", "all rights reserved",
        "newsletter", "click here", "read more", "continue reading", "appeared first on"
    };

    private static final String[] FILLER = {
        "the situation carries significance", "public understanding of the subject",
        "people, institutions, or communities", "most important point is the development",
        "additional verified reporting", "wider public conversation", "readers are likely to look for clearer background",
        "for now, the article centers", "safest reading", "current news agenda", "story remains active",
        "may gain more context over time", "central point is clear", "article has been rewritten", "feed supplied",
        "source summary", "limited detail", "unsupported detail", "confirmed basis"
    };

    /** Topic, then its keywords. Earlier topics win ties. */
    private static final String[][] LEXICON = {
        {"sports", "football", "soccer", "cricket", "tennis", "rugby", "golf", "nba", "nfl", "mlb", "nhl",
            "premier league", "champions league", "olympics", "olympic", "match", "goal", "goals", "coach",
            "tournament", "season", "league", "player", "players", "striker", "world cup", "medal", "race",
            "formula 1", "boxing", "ufc", "marathon", "wicket", "innings"},
        {"entertainment", "film", "films", "movie", "movies", "actor", "actress", "album", "singer", "concert",
            "festival", "oscar", "oscars", "grammy", "netflix", "television", "series", "celebrity", "music",
            "band", "tour", "box office", "streaming", "hollywood", "director", "theatre", "comedy", "bafta"},
        {"tech", "tech", "technology", "software", "ai", "artificial intelligence", "app", "apps", "apple",
            "google", "microsoft", "meta", "openai", "iphone", "android", "cyber", "cyberattack", "hackers",
            "hacking", "data breach", "robot", "robots", "chip", "chips", "semiconductor", "internet", "online",
            "smartphone", "algorithm", "cloud", "gadget", "samsung"},
        {"science", "scientists", "research", "researchers", "space", "nasa", "planet", "climate", "species",
            "fossil", "physics", "quantum", "telescope", "astronomers", "universe", "genome", "experiment",
            "discovery", "asteroid", "mars", "moon", "satellite", "rocket", "laboratory", "biology", "earthquake",
            "volcano"},
        {"health", "health", "hospital", "hospitals", "doctors", "patients", "disease", "virus", "vaccine",
            "cancer", "medical", "medicine", "nhs", "treatment", "drug", "drugs", "mental", "outbreak", "covid",
            "flu", "surgery", "diet", "obesity", "pandemic", "symptoms", "clinic"},
        {"business", "market", "markets", "stocks", "shares", "economy", "economic", "inflation", "bank", "banks",
            "investors", "trade", "tariff", "tariffs", "company", "companies", "earnings", "profit", "profits",
            "revenue", "ceo", "merger", "acquisition", "jobs", "unemployment", "interest rates", "retail", "sales",
            "billion", "oil", "prices", "price", "housing", "mortgage", "startup", "funding", "shareholders"},
        {"politics", "election", "elections", "vote", "voters", "voting", "senate", "senator", "congress",
            "parliament", "minister", "president", "presidential", "campaign", "lawmakers", "democrat",
            "democrats", "republican", "republicans", "labour", "tory", "tories", "legislation", "governor",
            "mayor", "ballot", "white house", "prime minister", "cabinet", "policy", "referendum", "opposition",
            "coalition", "mps"},
        {"world", "war", "ceasefire", "troops", "military", "attack", "attacks", "missile", "border", "refugees",
            "sanctions", "nato", "united nations", "embassy", "conflict", "hostages", "diplomatic", "invasion",
            "killed", "protest", "protests", "crisis", "flood", "floods", "wildfire", "storm", "hurricane",
            "police", "arrested", "court", "trial", "judge", "murder", "investigation"}
    };

    @Override
    public boolean ready() {
        return true;
    }

    @Override
    public String model() {
        return "built-in";
    }

    @Override
    public NewsService.Summary summarize(String sourceName, String title, String description) {
        String headline = cleanHeadline(title, sourceName);
        if (headline.isEmpty()) {
            return null;
        }
        List<String> kept = filterSentences(splitSentences(cleanDescription(description)), headline);
        String summary = pick(kept, headline);
        if (!summary.isEmpty() && !endsComplete(summary)) {
            summary = summary + "\u2026";
        }
        return new NewsService.Summary(headline, summary, classify(headline, summary), 0, 0);
    }

    // ---- Headline ----

    static String cleanHeadline(String title, String source) {
        String t = title == null ? "" : title.replaceAll("\\s+", " ").trim();
        t = t.replaceFirst("(?i)^(?:watch|listen|video|audio|photos?|gallery|podcast)\\s*[:|-]\\s*", "");
        String best = "";
        int cut = -1;
        for (String sep : new String[]{" - ", " | ", " \u2013 ", " \u2014 "}) {
            cut = Math.max(cut, t.lastIndexOf(sep));
        }
        if (cut > 0) {
            String suffix = t.substring(cut).replaceFirst("^\\s*[-|\u2013\u2014]\\s*", "").trim();
            String s = source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
            String low = suffix.toLowerCase(Locale.ROOT);
            if (suffix.length() >= 3 && !s.isEmpty() && (low.contains(s) || s.contains(low))) {
                best = t.substring(0, cut).trim();
            }
        }
        if (best.isEmpty()) {
            best = t;
        }
        return cut(best, MAX_HEADLINE);
    }

    // ---- Description ----

    static String cleanDescription(String desc) {
        String t = desc == null ? "" : desc;
        for (Pattern p : REMOVALS) {
            t = p.matcher(t).replaceAll(" ");
        }
        t = t.replaceAll("\\s+", " ").trim();
        t = DATELINE.matcher(t).replaceFirst("");
        t = t.replaceAll("(?:\\u2026|\\.\\.\\.)\\s*$", "").trim();
        return t;
    }

    /** Splits into sentences without breaking at U.S., Dr., initials or decimals. */
    static List<String> splitSentences(String text) {
        List<String> out = new ArrayList<String>();
        Matcher m = SENTENCE_END.matcher(text);
        int start = 0;
        while (m.find()) {
            int punct = m.start(1);
            if (isAbbreviation(text.substring(start, punct), text.charAt(punct))) {
                continue;
            }
            out.add(text.substring(start, m.end(1)).trim());
            start = m.end();
        }
        if (start < text.length()) {
            String rest = text.substring(start).trim();
            if (!rest.isEmpty()) {
                out.add(rest);
            }
        }
        return out;
    }

    private static boolean isAbbreviation(String before, char punct) {
        if (punct != '.') {
            return false;
        }
        int sp = Math.max(before.lastIndexOf(' '), before.lastIndexOf('('));
        String tok = before.substring(sp + 1).replaceAll("^[\"'\u201C\u2018(\\[]+", "");
        if (tok.isEmpty()) {
            return false;
        }
        if (tok.length() == 1 && Character.isLetter(tok.charAt(0))) {
            return true;
        }
        String low = tok.toLowerCase(Locale.ROOT);
        return ABBREVIATIONS.contains(low) || low.matches("[a-z](\\.[a-z])+");
    }

    /** Drops boilerplate, repeats of the headline, duplicates, filler, fragments and cut-off sentences. */
    static List<String> filterSentences(List<String> sentences, String headline) {
        String headNorm = normalize(headline);
        Set<String> headTokens = allTokens(headline);
        List<String> complete = new ArrayList<String>();
        List<String> partial = new ArrayList<String>();
        Set<String> seen = new HashSet<String>();
        for (String raw : sentences) {
            String s = raw.trim();
            if (s.isEmpty() || wordCount(s) < 5) {
                continue;
            }
            if (containsAny(s.toLowerCase(Locale.ROOT), BAD_FRAGMENTS)) {
                continue;
            }
            String norm = normalize(s);
            if (norm.isEmpty()) {
                continue;
            }
            if (headNorm.length() >= 20 && norm.contains(headNorm)) {
                continue;
            }
            if (jaccard(allTokens(s), headTokens) >= 0.75) {
                continue;
            }
            if (!seen.add(norm)) {
                continue;
            }
            if (looksGeneric(s)) {
                continue;
            }
            if (isTerminated(s)) {
                complete.add(s);
            } else if (wordCount(s) >= 8) {
                partial.add(s);
            }
        }
        return complete.isEmpty() ? partial : complete;
    }

    /** Picks up to two sentences, at most 320 characters, back in their original order. */
    static String pick(List<String> kept, String headline) {
        if (kept.isEmpty()) {
            return "";
        }
        Set<String> headTokens = new HashSet<String>(significantTokens(headline));
        Map<String, Integer> freq = new HashMap<String, Integer>();
        int maxFreq = 1;
        for (String s : kept) {
            for (String w : significantTokens(s)) {
                Integer c = freq.get(w);
                int n = c == null ? 1 : c + 1;
                freq.put(w, n);
                maxFreq = Math.max(maxFreq, n);
            }
        }
        final double[] score = new double[kept.size()];
        for (int i = 0; i < kept.size(); i++) {
            String s = kept.get(i);
            int words = wordCount(s);
            double sc = i == 0 ? 3.0 : (i == 1 ? 2.0 : (i == 2 ? 1.0 : 0.5));
            if (words >= 10 && words <= 35) {
                sc += 1.0;
            } else if (words < 10) {
                sc += 0.2;
            } else if (words > 45) {
                sc -= 1.0;
            }
            List<String> toks = significantTokens(s);
            if (!toks.isEmpty()) {
                double f = 0;
                int overlap = 0;
                for (String w : toks) {
                    f += freq.get(w);
                    if (headTokens.contains(w)) {
                        overlap++;
                    }
                }
                sc += 2.0 * (f / toks.size()) / maxFreq;
                sc += 1.5 * Math.min(1.0, overlap / (double) Math.max(3, headTokens.size()));
            }
            score[i] = sc + Math.min(1.0, factDensity(s));
        }
        Integer[] order = new Integer[kept.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                int c = Double.compare(score[b], score[a]);
                return c != 0 ? c : Integer.compare(a, b);
            }
        });
        List<Integer> chosen = new ArrayList<Integer>();
        int len = 0;
        for (int idx : order) {
            int add = kept.get(idx).length() + (chosen.isEmpty() ? 0 : 1);
            if (chosen.size() < 2 && len + add <= MAX_SUMMARY) {
                chosen.add(idx);
                len += add;
            }
        }
        if (chosen.isEmpty()) {
            return cut(kept.get(order[0]), MAX_SUMMARY);
        }
        Collections.sort(chosen);
        StringBuilder sb = new StringBuilder();
        for (int idx : chosen) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(kept.get(idx));
        }
        return sb.toString();
    }

    // ---- Topic and filler ----

    static String classify(String headline, String summary) {
        String title = " " + normalize(headline) + " ";
        String body = " " + normalize(summary) + " ";
        String best = "other";
        int bestScore = 0;
        for (String[] row : LEXICON) {
            int sc = 0;
            for (int i = 1; i < row.length; i++) {
                String key = " " + row[i] + " ";
                if (title.contains(key)) {
                    sc += 2;
                }
                if (body.contains(key)) {
                    sc += 1;
                }
            }
            if (sc > bestScore) {
                bestScore = sc;
                best = row[0];
            }
        }
        return best;
    }

    /** True for the empty, generic sentences some AI answers fall back on. */
    static boolean looksGeneric(String text) {
        return containsAny(text == null ? "" : text.toLowerCase(Locale.ROOT), FILLER);
    }

    // ---- Helpers ----

    private static boolean containsAny(String lower, String[] fragments) {
        for (String f : fragments) {
            if (lower.contains(f)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTerminated(String s) {
        String t = s.replaceAll("[\"'\u201D\u2019)\\]]+$", "");
        return !t.isEmpty() && ".!?".indexOf(t.charAt(t.length() - 1)) >= 0;
    }

    private static boolean endsComplete(String s) {
        return isTerminated(s) || s.endsWith("\u2026");
    }

    private static int wordCount(String s) {
        String t = s.trim();
        return t.isEmpty() ? 0 : t.split("\\s+").length;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static Set<String> allTokens(String s) {
        Set<String> out = new HashSet<String>();
        for (String w : normalize(s).split(" ")) {
            if (w.length() >= 3) {
                out.add(w);
            }
        }
        return out;
    }

    private static List<String> significantTokens(String s) {
        List<String> out = new ArrayList<String>();
        for (String w : normalize(s).split(" ")) {
            if (w.length() >= 3 && !STOP.contains(w)) {
                out.add(w);
            }
        }
        return out;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        int shared = 0;
        for (String w : a) {
            if (b.contains(w)) {
                shared++;
            }
        }
        return shared / (double) (a.size() + b.size() - shared);
    }

    /** Numbers and mid-sentence capitalised words (names, places) per word. */
    private static double factDensity(String s) {
        String[] words = s.trim().split("\\s+");
        int facts = 0;
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            if (w.matches(".*\\d.*") || (i > 0 && w.length() > 1 && Character.isUpperCase(w.charAt(0)))) {
                facts++;
            }
        }
        return words.length == 0 ? 0 : (double) facts / words.length * 3.0;
    }

    /** At most max characters, cut at a word boundary and ending with an ellipsis. */
    static String cut(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int end = text.lastIndexOf(' ', max - 2);
        if (end < max / 2) {
            end = max - 2;
        }
        return text.substring(0, end).replaceAll("[\\s,;:\\-]+$", "") + "\u2026";
    }
}
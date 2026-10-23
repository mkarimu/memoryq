package com.vstream.Vidiserver;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes what the DJ says: a rundown of three news stories with a little analysis, a shout-out to a good chat
 * post, and the introduction of the next song. With an OpenAI key the news and shout-outs are written by AI;
 * without one (or if it fails) they use built-in phrasing. Everything is plain text ready for the voice.
 */
public final class RadioWriter {

    /** Something that can write text from instructions (OpenAI, or a test stand-in). */
    public interface Ai {
        boolean ready();

        String write(String instructions, String input) throws IOException;

        /** The words sung or spoken in an audio file (null when this AI cannot listen). */
        default String transcribe(java.nio.file.Path audio) throws IOException {
            return null;
        }
    }

    // ---- What a song is about ----

    static final String MESSAGE_INSTRUCTIONS = "You help a friendly radio presenter called Alma. You get the title of a song and the words "
            + "sung in it (written down by a machine, so some words may be wrong; the song may be in English, Kiswahili or another language). "
            + "Find the most important message the song implies - its heart: what it is really saying to the listener, or the feeling it "
            + "wants to leave. Write it as ONE or TWO short spoken sentences in English (at most 40 words) that Alma can say on air, warm "
            + "and natural, like a presenter sharing a thought. Do not quote the song (never more than four words in a row from it), do "
            + "not say the title or the singer, and do not mention lyrics, words or a transcript. If there are no real words (instrumental), "
            + "the words make no sense, or the message is hateful, sexual or violent, answer exactly: NONE";

    /** The song's message for the presenter from its words, or "" (none, or no AI). */
    public String songMessage(String title, String words) throws IOException {
        if (ai == null || !ai.ready() || words == null || words.trim().split("\\s+").length < 12) {
            return "";
        }
        String w = words.trim();
        if (w.length() > 6000) {
            w = w.substring(0, 6000);
        }
        String out = ai.write(MESSAGE_INSTRUCTIONS, "Song title: " + title + "\nWords heard in the song:\n" + w);
        out = out == null ? "" : out.replaceAll("\\s+", " ").trim();
        if (out.isEmpty() || out.toUpperCase(java.util.Locale.ROOT).startsWith("NONE") || out.length() > 400) {
            return "";
        }
        return out;
    }

    /** Before the song: its message, then the song. */
    public String messageBefore(RadioStore.Track next, String message, String requestedBy, boolean sayArtist) {
        String song = !sayArtist || next.artist.isEmpty() ? next.title : next.artist + ", with " + next.title;
        String m = message.trim().replaceAll("[.!?]*$", ".");
        String by = requestedBy == null || requestedBy.isEmpty() ? "" : " This one is for " + requestedBy + ".";
        return forSpeech(pick("Here is a song with something to say. " + m + by + " Listen for it in " + song + ".",
                "Before the next song, a little thought. " + m + by + " Here is " + song + ".",
                m + by + " That is the heart of our next song. Here is " + song + ".",
                "Listen closely to this one. " + m + by + " " + song + "."));
    }

    /** After the song: what it was saying. */
    public String messageAfter(RadioStore.Track prev, String message, boolean sayArtist) {
        String song = !sayArtist || prev.artist.isEmpty() ? prev.title : prev.title + " by " + prev.artist;
        String m = message.trim().replaceAll("[.!?]*$", ".");
        return forSpeech(pick("That was " + song + ". " + m,
                "You just heard " + song + ". If you listened closely: " + m,
                song + ". What a song. " + m,
                "That was " + song + ", and here is what I take from it. " + m));
    }

    /** A listener's song request. */
    public static final class Request {
        public final long trackId;
        public final String user;
        public final String name;

        public Request(long trackId, String user, String name) {
            this.trackId = trackId;
            this.user = user;
            this.name = name;
        }
    }

    /** A chat post worth a shout-out. */
    public static final class Shout {
        public String user = "";
        public String name = "";
        public String room = "";
        public String text = "";
        public int replies;
        public int people;
        public int reactions;
    }

    private final Ai ai;
    private final Random random;

    public RadioWriter(Ai ai, Random random) {
        this.ai = ai;
        this.random = random;
    }

    public boolean aiReady() {
        return ai != null && ai.ready();
    }

    private String pick(String... options) {
        return options[random.nextInt(options.length)];
    }

    // ---- News ----

    /** The three stories to talk about: newest first, from different sources where possible, not used before. */
    public static List<NewsService.Item> chooseNews(List<NewsService.Item> latest, Set<Long> used, int count) {
        List<NewsService.Item> out = new ArrayList<NewsService.Item>();
        Set<String> sources = new LinkedHashSet<String>();
        for (int pass = 0; pass < 2 && out.size() < count; pass++) {
            for (NewsService.Item it : latest) {
                if (out.size() >= count) {
                    break;
                }
                if (used.contains(it.id) || out.contains(it) || it.headline == null || it.headline.isEmpty()) {
                    continue;
                }
                String src = it.source == null ? "" : it.source.toLowerCase(Locale.ROOT);
                if (pass == 0 && sources.contains(src)) {
                    continue;
                }
                sources.add(src);
                out.add(it);
            }
        }
        return out;
    }

    public String news(List<NewsService.Item> items, String dj, String station, boolean useAi) {
        if (items.isEmpty()) {
            return "";
        }
        if (useAi && aiReady()) {
            StringBuilder in = new StringBuilder();
            for (int i = 0; i < items.size(); i++) {
                NewsService.Item it = items.get(i);
                in.append("STORY ").append(i + 1).append("\nSource: ").append(one(it.source)).append("\nHeadline: ")
                        .append(one(it.headline)).append("\nSummary: ").append(one(it.summary)).append("\n\n");
            }
            String instructions = "You are " + dj + ", the DJ on the radio station " + station + ". Between songs you "
                    + "give listeners a quick rundown of what is trending in the news. Write exactly what you say on "
                    + "air, about 110 to 150 words, for these stories. Name each source naturally (for example "
                    + "'CNN reports...' or 'Over at the BBC today...'). After each story add one short sentence of "
                    + "analysis: why it matters or what to watch next. Be warm and lively but respectful with serious "
                    + "news. Only use facts from the stories. End by saying listeners can find more on those stories "
                    + "on the sources' websites. Write for the ear: short, punchy sentences of different lengths, "
                    + "natural spoken rhythm, the odd exclamation mark where it fits, a question to the listeners now "
                    + "and then. Plain spoken text only: no lists, no emojis, no links, no stage directions, no sound "
                    + "effects.";
            try {
                String said = ai.write(instructions, in.toString());
                String clean = forSpeech(said);
                if (clean.length() > 40) {
                    return clean;
                }
            } catch (IOException e) {
                // use the built-in version
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(pick("Alright, alright! Time for a quick look at what's trending. ",
                "Okay everybody, let's catch up on the news! ",
                "Here's what people are talking about right now. Ready? ",
                "Quick news break before more music! Here we go. "));
        List<String> sourceNames = new ArrayList<String>();
        for (int i = 0; i < items.size(); i++) {
            NewsService.Item it = items.get(i);
            String src = one(it.source);
            if (!src.isEmpty() && !sourceNames.contains(src)) {
                sourceNames.add(src);
            }
            String lead;
            if (i == 0) {
                lead = src.isEmpty() ? "First up: " : pick("From " + src + ": ", src + " reports: ", "First, " + src + " says ");
            } else if (i == items.size() - 1) {
                lead = src.isEmpty() ? "And finally: " : pick("And " + src + " today talks about this: ", "Finally, from " + src + ": ");
            } else {
                lead = src.isEmpty() ? "Next: " : pick("Over at " + src + ": ", "Meanwhile " + src + " reports: ");
            }
            sb.append(lead).append(sentence(it.headline)).append(' ');
            String first = firstSentence(it.summary);
            if (!first.isEmpty() && !first.equalsIgnoreCase(sentence(it.headline))) {
                sb.append(first).append(' ');
            }
        }
        if (!sourceNames.isEmpty()) {
            sb.append("For more on those, visit ").append(join(sourceNames)).append(" online. ");
        }
        return forSpeech(sb.toString());
    }

    // ---- Shout-outs ----

    public String shout(Shout s, String dj, boolean useAi) {
        if (s == null || s.text.isEmpty()) {
            return "";
        }
        String who = s.name.isEmpty() ? s.user : s.name;
        if (useAi && aiReady()) {
            String input = "Person: " + one(who) + "\nChat room: " + one(s.room) + "\nTheir message: " + one(s.text)
                    + "\nReplies to it: " + s.replies + "\nPeople in the conversation: " + s.people
                    + "\nReactions: " + s.reactions;
            String instructions = "You are " + dj + ", a friendly radio DJ. Give a short on-air shout-out (2 to 3 "
                    + "sentences, under 60 words) to this listener for their chat message, quoting or paraphrasing it, "
                    + "and mention the conversation it started in that chat room. Keep it kind and fun; never mock "
                    + "anyone. Plain spoken text only: no emojis, no links.";
            try {
                String clean = forSpeech(ai.write(instructions, input));
                if (clean.length() > 20) {
                    return clean;
                }
            } catch (IOException e) {
                // built-in version
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(pick("Shout-out time! ", "Big shout-out going out now! ", "Ooh, time for a shout-out! "));
        sb.append(pick("This one goes to ", "Love to ", "Hello to ")).append(who).append(" in the ").append(s.room)
                .append(" chat, who said: ").append(quoteText(s.text)).append(' ');
        if (s.replies > 0 || s.people > 1) {
            int n = Math.max(s.people, s.replies + 1);
            sb.append(pick("That got the conversation going, with " + n + " of you joining in. ",
                    n + " people jumped into that chat. ", "And the room had plenty to say about it. "));
        }
        sb.append(pick("Keep the messages coming! ", "Keep chatting, we see you! ", "Thanks for being part of the family! "));
        return forSpeech(sb.toString());
    }

    // ---- Song intros ----

    public String intro(RadioStore.Track next, RadioStore.Track previous, String station, boolean newsJustRead) {
        return intro(next, previous, station, newsJustRead, "", "");
    }

    /**
     * The song intro. requestedBy (may be empty) names the listener who asked for the song; promo (may be empty)
     * is the station's own line ("Get my songs on Spotify...") said now and then.
     */
    public String intro(RadioStore.Track next, RadioStore.Track previous, String station, boolean newsJustRead,
                        String requestedBy, String promo) {
        return intro(next, previous, station, newsJustRead, requestedBy, promo, true);
    }

    /**
     * sayArtist = false: only the song's title is said (artists' names are often mispronounced by the voice, and
     * reading a name out sounds unnatural).
     */
    public String intro(RadioStore.Track next, RadioStore.Track previous, String station, boolean newsJustRead,
                        String requestedBy, String promo, boolean sayArtist) {
        if (next == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (newsJustRead) {
            sb.append(pick("Now, back to our music. ", "Back to the music. ", "And now, more music. "));
        } else if (previous != null && random.nextInt(3) == 0) {
            sb.append("That was ").append(sayArtist ? previous.label() : previous.title).append(". ");
        }
        String song = !sayArtist || next.artist.isEmpty() ? next.title : next.artist + ", with " + next.title;
        if (requestedBy != null && !requestedBy.isEmpty()) {
            sb.append(pick("This one's a request from " + requestedBy + "! Here's " + song + ". ",
                    requestedBy + " asked for this one, and you got it! " + song + ". ",
                    "By request, for " + requestedBy + ": " + song + "! "));
        } else {
            sb.append(pick("Next song we have for you is " + song + "! ",
                    "Up next on " + station + ": " + song + ". Turn it up! ",
                    "Here's " + song + ". You're gonna love this one! ",
                    "Coming up, " + song + ". Enjoy! "));
        }
        if (promo != null && !promo.trim().isEmpty() && random.nextInt(3) == 0) {
            sb.append(promo.trim());
            if (!promo.trim().matches(".*[.!?]$")) {
                sb.append('.');
            }
            sb.append(' ');
        }
        return forSpeech(sb.toString());
    }

    // ---- Alma's stories ----

    /** The story part Alma reads at a break: a lead-in, the text as it is written, and the end of a book. */
    public String story(RadioStories.Part p, String dj, String station) {
        if (p == null || p.text.isEmpty()) {
            return "";
        }
        String book = one(p.title);
        String by = p.author == null || p.author.trim().isEmpty() ? "" : ", by " + one(p.author);
        StringBuilder sb = new StringBuilder();
        if (p.first()) {
            sb.append(pick("It's story time on " + station + "! Get comfortable, because today we begin a new story: " + book + by + ". ",
                    "Story time! Today on " + station + " we start reading " + book + by + ". Here we go. ",
                    "Settle in, everyone. This is " + dj + ", and today we begin " + book + by + ". "));
        } else {
            sb.append(pick("Let's get back to our story, " + book + ". ",
                    "And now, back to " + book + ". Where were we? Ah, yes. ",
                    "Time for more of our story, " + book + ". ",
                    "Story time again on " + station + ". We continue with " + book + ". "));
        }
        sb.append(p.text.trim());
        if (!p.text.trim().matches(".*[.!?\"'\u201d\u2019]$")) {
            sb.append('.');
        }
        sb.append(' ');
        if (p.last()) {
            sb.append(pick("And that is the end of " + book + ". Thank you for listening with me. ",
                    "The end. That was " + book + by + ". I hope you enjoyed it as much as I did. "));
        }
        return forSpeech(sb.toString());
    }

    /** What Alma says over the start of the song after a story part. */
    public String storyBridge(RadioStore.Track next, RadioStories.Part p, boolean sayArtist) {
        String book = p == null ? "our story" : one(p.title);
        String song = next == null ? "" : (!sayArtist || next.artist.isEmpty() ? next.title : next.artist + ", with " + next.title);
        if (p != null && p.last()) {
            return forSpeech(song.isEmpty() ? "Now, some music." : pick("Now, some music. Here's " + song + ".", "And now, " + song + "."));
        }
        if (song.isEmpty()) {
            return forSpeech(pick("Let's listen to some music before we continue with our story of " + book + ".",
                    "Stay with us - more of " + book + " after this song."));
        }
        return forSpeech(pick("Let's listen to " + song + " before we continue with our story of " + book + ".",
                "We'll pause the story here. Here's " + song + ", and then more of " + book + ".",
                "Hold that thought! First, " + song + ". Then we continue with " + book + "."));
    }

    // ---- Over a song: which station this is ----

    /** A short line Alma says over a song, like a presenter: the station, Memoryq, sometimes the song. */
    public String stationId(String dj, String station, RadioStore.Track playing, boolean sayArtist) {
        String song = playing == null ? "" : (!sayArtist || playing.artist.isEmpty() ? playing.title : playing.title + " by " + playing.artist);
        String[] lines = {
            "You're listening to " + station + ", on Memoryq.",
            "This is " + station + ". I'm " + dj + ", and you're on Memoryq.",
            station + ", on Memoryq. Keep it locked!",
            "You're tuned in to " + station + ". Thanks for listening!",
            song.isEmpty() ? station + ", the place to be. On Memoryq." : "This is " + song + ", here on " + station + ".",
            "It's " + dj + " with you on " + station + ". Stay with us!"
        };
        return forSpeech(lines[random.nextInt(lines.length)]);
    }

    // ---- Text helpers ----

    private static final Pattern URL = Pattern.compile("https?://\\S+|www\\.\\S+");

    /** Text the voice can read: no links, emojis, markdown or odd symbols; one paragraph. */
    public static String forSpeech(String s) {
        if (s == null) {
            return "";
        }
        String t = URL.matcher(s).replaceAll("");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length(); ) {
            int cp = t.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp) || Character.isWhitespace(cp) || ".,;:!?'\"-()&%$".indexOf(cp) >= 0
                    || cp == 0x2019 || cp == 0x2014 || cp == 0x2013) {
                sb.appendCodePoint(Character.isWhitespace(cp) ? ' ' : cp);
            } else if (cp == '*' || cp == '#' || cp == '_' || cp == '`') {
                // markdown marks are dropped
            } else if (cp == '/' || cp == '+') {
                sb.append(' ');
            }
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    private static String one(String s) {
        return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String sentence(String s) {
        String t = one(s);
        if (t.isEmpty()) {
            return t;
        }
        char last = t.charAt(t.length() - 1);
        return last == '.' || last == '!' || last == '?' ? t : t + ".";
    }

    private static String firstSentence(String s) {
        String t = one(s);
        Matcher m = Pattern.compile("^(.{20,220}?[.!?])(\\s|$)").matcher(t);
        if (m.find()) {
            return m.group(1);
        }
        return t.length() <= 220 ? sentence(t) : "";
    }

    private static String quoteText(String s) {
        String t = one(s);
        if (t.length() > 160) {
            t = t.substring(0, 157).replaceAll("\\s+\\S*$", "") + "...";
        }
        return "\"" + t + "\".";
    }

    private static String join(List<String> names) {
        if (names.size() == 1) {
            return names.get(0);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append(i == names.size() - 1 ? " and " : ", ");
            }
            sb.append(names.get(i));
        }
        return sb.toString();
    }

    // ---- OpenAI ----

    /** Writes with OpenAI's Responses API, using the same key as the news summaries (OPENAI_API_KEY). */
    public static final class OpenAi implements Ai {
        private final String key;
        private final String model;
        private final String endpoint;

        public OpenAi(String key, String model, String endpoint) {
            this.key = key == null ? "" : key.trim();
            this.model = model;
            this.endpoint = endpoint;
        }

        public static OpenAi fromEnvironment() {
            return new OpenAi(System.getenv("OPENAI_API_KEY"), System.getProperty("vstream.radioModel", "gpt-5-mini"),
                    "https://api.openai.com/v1/responses");
        }

        @Override
        public boolean ready() {
            return !key.isEmpty();
        }

        @Override
        public String write(String instructions, String input) throws IOException {
            HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
            try {
                c.setRequestMethod("POST");
                c.setConnectTimeout(10000);
                c.setReadTimeout(60000);
                c.setDoOutput(true);
                c.setRequestProperty("Authorization", "Bearer " + key);
                c.setRequestProperty("Content-Type", "application/json");
                String body = "{\"model\":" + ProfileApi.quote(model) + ",\"instructions\":" + ProfileApi.quote(instructions)
                        + ",\"input\":" + ProfileApi.quote(input) + ",\"max_output_tokens\":1200,\"store\":false"
                        + ",\"reasoning\":{\"effort\":\"minimal\"}}";
                OutputStream os = c.getOutputStream();
                os.write(body.getBytes(StandardCharsets.UTF_8));
                os.close();
                int code = c.getResponseCode();
                byte[] raw = AudioTools.readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
                if (code < 200 || code >= 300) {
                    throw new IOException("OpenAI answered HTTP " + code);
                }
                return outputText(new String(raw, StandardCharsets.UTF_8));
            } finally {
                c.disconnect();
            }
        }

        /** OpenAI's speech-to-text: the words in a short audio file (MP3, up to 25 MB). */
        @Override
        public String transcribe(java.nio.file.Path audio) throws IOException {
            if (key.isEmpty()) {
                return null;
            }
            String model = System.getProperty("vstream.transcribeModel", "gpt-4o-mini-transcribe");
            String boundary = "----memoryq" + Long.toHexString(System.nanoTime());
            HttpURLConnection c = (HttpURLConnection) new URL("https://api.openai.com/v1/audio/transcriptions").openConnection();
            try {
                c.setRequestMethod("POST");
                c.setConnectTimeout(10000);
                c.setReadTimeout(180000);
                c.setDoOutput(true);
                c.setRequestProperty("Authorization", "Bearer " + key);
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                OutputStream os = c.getOutputStream();
                String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n" + model + "\r\n"
                        + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"response_format\"\r\n\r\njson\r\n"
                        + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"song.mp3\"\r\n"
                        + "Content-Type: audio/mpeg\r\n\r\n";
                os.write(head.getBytes(StandardCharsets.UTF_8));
                os.write(java.nio.file.Files.readAllBytes(audio));
                os.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                os.close();
                int code = c.getResponseCode();
                byte[] raw = AudioTools.readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
                if (code < 200 || code >= 300) {
                    throw new IOException("OpenAI speech-to-text answered HTTP " + code);
                }
                return jsonText(new String(raw, StandardCharsets.UTF_8));
            } finally {
                c.disconnect();
            }
        }

        /** The "text" value of a small JSON answer. */
        static String jsonText(String json) throws IOException {
            Matcher m = Pattern.compile("\"text\"\\s*:\\s*\"").matcher(json);
            if (!m.find()) {
                throw new IOException("no text in the answer");
            }
            StringBuilder sb = new StringBuilder();
            for (int i = m.end(); i < json.length(); i++) {
                char ch = json.charAt(i);
                if (ch == '"') {
                    return sb.toString();
                }
                if (ch == '\\' && i + 1 < json.length()) {
                    char e = json.charAt(++i);
                    if (e == 'n') {
                        sb.append(' ');
                    } else if (e == 'u' && i + 4 < json.length()) {
                        sb.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                        i += 4;
                    } else if (e != 'r' && e != 't') {
                        sb.append(e);
                    }
                } else {
                    sb.append(ch);
                }
            }
            throw new IOException("cut-off answer");
        }

        /** The text of the first "output_text" part of a Responses API answer. */
        static String outputText(String json) throws IOException {
            int at = json.indexOf("\"output_text\"");
            if (at < 0) {
                throw new IOException("no text in the answer");
            }
            Matcher m = Pattern.compile("\"text\"\\s*:\\s*\"").matcher(json);
            if (!m.find(at)) {
                throw new IOException("no text in the answer");
            }
            StringBuilder sb = new StringBuilder();
            for (int i = m.end(); i < json.length(); i++) {
                char ch = json.charAt(i);
                if (ch == '"') {
                    return sb.toString();
                }
                if (ch == '\\' && i + 1 < json.length()) {
                    char e = json.charAt(++i);
                    switch (e) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append(' '); break;
                        case 'r': break;
                        case 'u':
                            if (i + 4 < json.length()) {
                                sb.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                                i += 4;
                            }
                            break;
                        default: sb.append(e);
                    }
                } else {
                    sb.append(ch);
                }
            }
            throw new IOException("cut-off answer");
        }
    }
}
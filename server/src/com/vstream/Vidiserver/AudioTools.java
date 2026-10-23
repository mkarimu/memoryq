package com.vstream.Vidiserver;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The outside programs the radio uses: FFmpeg (read song tags, decode songs, encode the stream) and a voice for
 * the DJ: Piper (natural, free, offline), the Windows built-in voice, or FFmpeg's simple "flite" voice.
 * Everything is found next to the server in the tools folder, or on the PATH.
 */
public final class AudioTools {

    public static final int RATE = 44100;
    public static final int CHANNELS = 2;

    public static final class Tags {
        public String title = "";
        public String artist = "";
        public String album = "";
        public long durationMs;
    }

    /** Where FFmpeg's messages go (thrown away): NUL on Windows, /dev/null elsewhere. */
    private static final File NULL_FILE = new File(System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
            .contains("win") ? "NUL" : "/dev/null");

    private final Path toolsDir;
    private final Path tmpDir;
    private volatile String ffmpeg;
    private volatile String ffprobe;
    private volatile String piper;
    private volatile List<Path> voices = Collections.emptyList();
    private volatile boolean flite;
    private volatile boolean sapi;
    private volatile String note = "";
    private volatile String lastVoiceError = "";

    public AudioTools(Path toolsDir, Path tmpDir) {
        this.toolsDir = toolsDir;
        this.tmpDir = tmpDir;
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** Looks for the programs again (after the admin installs something). */
    public synchronized void detect() {
        String exe = windows() ? ".exe" : "";
        ffmpeg = find(System.getProperty("vstream.ffmpeg"), "ffmpeg" + exe, "ffmpeg");
        ffprobe = ffmpeg == null ? null : find(null, "ffprobe" + exe, "ffprobe");
        if (ffmpeg != null && ffprobe == null) {
            Path sibling = Paths.get(ffmpeg).resolveSibling("ffprobe" + exe);
            ffprobe = Files.isRegularFile(sibling) ? sibling.toString() : null;
        }
        piper = find(System.getProperty("vstream.piper"), "piper" + exe, null);
        List<Path> v = new ArrayList<Path>();
        Path vdir = toolsDir.resolve("piper-voices");
        if (Files.isDirectory(vdir)) {
            try (Stream<Path> s = Files.list(vdir)) {
                s.filter(p -> p.getFileName().toString().endsWith(".onnx")
                        && Files.isRegularFile(p.resolveSibling(p.getFileName() + ".json"))).sorted().forEach(v::add);
            } catch (IOException e) {
                // no voices
            }
        }
        voices = v;
        flite = ffmpeg != null && runOk(ffmpeg, "-hide_banner", "-filters").contains("flite");
        sapi = windows();
        StringBuilder n = new StringBuilder();
        n.append(ffmpeg == null ? "FFmpeg NOT found" : "FFmpeg ready");
        n.append(", DJ voice: ").append(voiceEngine());
        note = n.toString();
    }

    /** The FFmpeg program in use, or null. */
    public String ffmpegPath() {
        return ffmpeg;
    }

    public String ffprobePath() {
        return ffprobe;
    }

    public boolean ready() {
        return ffmpeg != null && ffprobe != null;
    }

    public String note() {
        return note;
    }

    /** Why the last attempt to speak failed ("" if it worked). */
    public String lastVoiceError() {
        return lastVoiceError;
    }

    /** Which voice the DJ uses: piper, windows, flite or none. */
    public String voiceEngine() {
        if (piper != null && !voices.isEmpty()) {
            return "piper";
        }
        if (sapi) {
            return "windows";
        }
        if (flite) {
            return "flite";
        }
        return "none";
    }

    /** The Piper voice names (file names without .onnx), then the OpenAI voices when a key is set. */
    public List<String> voiceNames() {
        List<String> out = new ArrayList<String>();
        for (Path p : voices) {
            out.add(p.getFileName().toString().replaceAll("\\.onnx$", ""));
        }
        if (openAiReady()) {
            for (String v : OPENAI_VOICES) {
                out.add("openai:" + v);
            }
        }
        return out;
    }

    /** A program from the tools folder (searched a few levels deep), the given property, or the PATH. */
    private String find(String property, String fileName, String pathName) {
        if (property != null && !property.isEmpty() && Files.isRegularFile(Paths.get(property))) {
            return property;
        }
        if (Files.isDirectory(toolsDir)) {
            try (Stream<Path> s = Files.walk(toolsDir, 5)) {
                Path hit = s.filter(p -> p.getFileName().toString().equalsIgnoreCase(fileName) && Files.isRegularFile(p))
                        .findFirst().orElse(null);
                if (hit != null) {
                    return hit.toAbsolutePath().toString();
                }
            } catch (IOException e) {
                // keep looking
            }
        }
        if (pathName != null) {
            String out = runOk(pathName, "-version");
            if (!out.isEmpty()) {
                return pathName;
            }
        }
        return null;
    }

    private static String runOk(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getOutputStream().close();
            String out = new String(readAll(p.getInputStream()), StandardCharsets.UTF_8);
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "";
            }
            return p.exitValue() == 0 ? out : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** Runs a program and returns its exit code and the end of what it printed. */
    private static String[] runOut(File dir, String input, String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            if (dir != null) {
                pb.directory(dir);
            }
            Process p = pb.start();
            OutputStream os = p.getOutputStream();
            if (input != null) {
                os.write(input.getBytes(StandardCharsets.UTF_8));
            }
            os.close();
            String out = new String(readAll(p.getInputStream()), StandardCharsets.UTF_8).trim();
            if (!p.waitFor(90, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new String[]{"timeout", out};
            }
            if (out.length() > 300) {
                out = out.substring(out.length() - 300);
            }
            return new String[]{String.valueOf(p.exitValue()), out};
        } catch (Exception e) {
            return new String[]{"error", e.getMessage() == null ? e.toString() : e.getMessage()};
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0) {
            buf.write(b, 0, n);
        }
        return buf.toByteArray();
    }

    // ---- Song tags ----

    private static final Pattern DURATION = Pattern.compile("(?m)^duration=([0-9.]+)");

    /** Title, artist, album and length of a sound file (empty values when unknown). */
    public Tags probe(Path file) throws IOException {
        if (ffprobe == null) {
            throw new IOException("FFmpeg is not installed");
        }
        String out = runOk(ffprobe, "-v", "error", "-show_entries", "format=duration:format_tags=title,artist,album",
                "-of", "default=noprint_wrappers=1", file.toAbsolutePath().toString());
        if (out.isEmpty()) {
            throw new IOException("this file is not a sound file FFmpeg can read");
        }
        Tags t = new Tags();
        for (String line : out.split("\\r?\\n")) {
            String l = line.trim();
            String lower = l.toLowerCase(Locale.ROOT);
            if (lower.startsWith("tag:title=")) {
                t.title = l.substring(10).trim();
            } else if (lower.startsWith("tag:artist=")) {
                t.artist = l.substring(11).trim();
            } else if (lower.startsWith("tag:album=")) {
                t.album = l.substring(10).trim();
            }
        }
        Matcher m = DURATION.matcher(out);
        if (m.find()) {
            t.durationMs = Math.round(Double.parseDouble(m.group(1)) * 1000);
        }
        if (t.durationMs < 1000) {
            throw new IOException("this file has no playable sound");
        }
        return t;
    }

    // ---- Decoding and encoding ----

    /** Starts FFmpeg decoding a file to raw sound (44.1 kHz, stereo, 16-bit) on its output. */
    public Process decode(Path file) throws IOException {
        if (ffmpeg == null) {
            throw new IOException("FFmpeg is not installed");
        }
        return new ProcessBuilder(ffmpeg, "-v", "error", "-nostdin", "-i", file.toAbsolutePath().toString(),
                "-vn", "-f", "s16le", "-ac", String.valueOf(CHANNELS), "-ar", String.valueOf(RATE), "pipe:1")
                .redirectError(ProcessBuilder.Redirect.to(NULL_FILE)).start();
    }

    /** Starts FFmpeg decoding a live DJ stream (MP3, AAC or Ogg written to its input) to raw sound on its output. */
    public Process liveDecoder() throws IOException {
        if (ffmpeg == null) {
            throw new IOException("FFmpeg is not installed");
        }
        return new ProcessBuilder(ffmpeg, "-v", "error", "-fflags", "+nobuffer+discardcorrupt", "-probesize", "65536",
                "-analyzeduration", "1500000", "-i", "pipe:0", "-vn", "-f", "s16le", "-ac", String.valueOf(CHANNELS),
                "-ar", String.valueOf(RATE), "pipe:1")
                .redirectError(ProcessBuilder.Redirect.to(NULL_FILE)).start();
    }

    /** Starts FFmpeg turning raw sound (written to its input) into an MP3 stream (read from its output). */
    public Process encoder(int kbps) throws IOException {
        if (ffmpeg == null) {
            throw new IOException("FFmpeg is not installed");
        }
        return new ProcessBuilder(ffmpeg, "-v", "error", "-f", "s16le", "-ar", String.valueOf(RATE), "-ac",
                String.valueOf(CHANNELS), "-i", "pipe:0", "-c:a", "libmp3lame", "-b:a", kbps + "k", "-write_xing", "0",
                "-flush_packets", "1", "-f", "mp3", "pipe:1")
                .redirectError(ProcessBuilder.Redirect.to(NULL_FILE)).start();
    }

    /** Reads a whole sound file into memory as raw stereo samples. */
    public short[] decodeAll(Path file) throws IOException {
        Process p = decode(file);
        p.getOutputStream().close();
        byte[] raw = readAll(p.getInputStream());
        try {
            p.waitFor(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        short[] out = new short[raw.length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (short) ((raw[2 * i] & 0xff) | (raw[2 * i + 1] << 8));
        }
        return out;
    }

    // ---- The DJ's voice ----

    /** The voice processing radio stations use: clean low end, presence, compression and a limiter. */
    static final String BROADCAST = "highpass=f=85,equalizer=f=220:t=q:w=1:g=1.5,equalizer=f=3200:t=q:w=1.2:g=3,"
            + "acompressor=threshold=0.125:ratio=3:attack=5:release=150:makeup=2,alimiter=limit=0.9";

    /** How one sentence is said: pitch and speed factors and the pause after it. */
    public static final class Prosody {
        final double pitch;
        final double tempo;
        final int pauseMs;

        public Prosody(double pitch, double tempo, int pauseMs) {
            this.pitch = pitch;
            this.tempo = tempo;
            this.pauseMs = pauseMs;
        }
    }

    private volatile String openAiKey = "";
    private volatile String openAiModel = "gpt-4o-mini-tts";
    public static final List<String> OPENAI_VOICES = Collections.unmodifiableList(java.util.Arrays.asList(
            "ash", "coral", "ballad", "verse", "sage", "alloy", "echo", "nova", "onyx", "shimmer"));

    /** Turns on the OpenAI voices (used only when someone is listening). */
    public void setOpenAi(String key, String model) {
        openAiKey = key == null ? "" : key.trim();
        if (model != null && !model.trim().isEmpty()) {
            openAiModel = model.trim();
        }
    }

    public boolean openAiReady() {
        return !openAiKey.isEmpty();
    }

    /** Splits text into sentences; very short bits are joined to the next one. */
    static List<String> sentences(String text) {
        List<String> out = new ArrayList<String>();
        String carry = "";
        for (String part : text.trim().split("(?<=[.!?])\\s+")) {
            String p = (carry.isEmpty() ? "" : carry + " ") + part.trim();
            if (p.length() < 14 && !p.endsWith("!") && !p.endsWith("?")) {
                carry = p;
                continue;
            }
            carry = "";
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        if (!carry.isEmpty()) {
            if (out.isEmpty()) {
                out.add(carry);
            } else {
                out.set(out.size() - 1, out.get(out.size() - 1) + " " + carry);
            }
        }
        return out;
    }

    /**
     * The rise and fall of a DJ: an upbeat start, brighter and quicker exclamations, lifted questions, small
     * changes from sentence to sentence, a settled last line, and pauses that are never quite the same.
     */
    static Prosody prosody(String sentence, int index, int count, java.util.Random r) {
        char end = sentence.isEmpty() ? '.' : sentence.charAt(sentence.length() - 1);
        double pitch;
        double tempo;
        int pause;
        if (end == '!') {
            pitch = 1.035 + r.nextDouble() * 0.015;
            tempo = 1.05 + r.nextDouble() * 0.03;
            pause = 230 + r.nextInt(80);
        } else if (end == '?') {
            pitch = 1.03;
            tempo = 1.0 + r.nextDouble() * 0.02;
            pause = 300 + r.nextInt(80);
        } else {
            double[] steps = {0.985, 1.0, 1.012, 1.025};
            pitch = steps[r.nextInt(steps.length)];
            tempo = 0.99 + r.nextDouble() * 0.05;
            pause = 280 + r.nextInt(150);
        }
        if (index == 0) {
            pitch = Math.max(pitch, 1.02);
            tempo = Math.max(tempo, 1.03);
        }
        if (index == count - 1 && count > 1 && end != '!') {
            pitch = Math.min(pitch, 0.995);
            tempo = Math.min(tempo, 0.99);
        }
        if (index == count - 1) {
            pause = 0;
        }
        return new Prosody(pitch, tempo, pause);
    }

    static String prosodyFilter(Prosody p) {
        return String.format(Locale.ROOT, "aresample=44100,asetrate=%.0f,aresample=44100,atempo=%.4f,%s",
                44100 * p.pitch, p.tempo / p.pitch, BROADCAST);
    }

    /** Decodes a sound file through an FFmpeg filter into raw stereo samples. */
    short[] decodeFiltered(Path file, String filter) throws IOException {
        Process p = new ProcessBuilder(ffmpeg, "-v", "error", "-nostdin", "-i", file.toAbsolutePath().toString(),
                "-af", filter, "-f", "s16le", "-ac", String.valueOf(CHANNELS), "-ar", String.valueOf(RATE), "pipe:1")
                .redirectError(ProcessBuilder.Redirect.to(NULL_FILE)).start();
        p.getOutputStream().close();
        byte[] raw = readAll(p.getInputStream());
        try {
            p.waitFor(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        short[] out = new short[raw.length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (short) ((raw[2 * i] & 0xff) | (raw[2 * i + 1] << 8));
        }
        return out;
    }

    /** Joins sentence recordings with their own pitch, pace and pauses. Null if any is missing. */
    private short[] assemble(List<String> parts, List<Path> wavs, long seed) throws IOException {
        java.util.Random r = new java.util.Random(seed);
        List<short[]> pieces = new ArrayList<short[]>();
        int total = 0;
        for (int i = 0; i < parts.size(); i++) {
            Path w = wavs.get(i);
            if (w == null || !Files.isRegularFile(w) || Files.size(w) < 1000) {
                return null;
            }
            Prosody pr = prosody(parts.get(i), i, parts.size(), r);
            short[] s = decodeFiltered(w, prosodyFilter(pr));
            if (s.length == 0) {
                return null;
            }
            pieces.add(s);
            total += s.length;
            short[] gap = new short[pr.pauseMs * RATE / 1000 * CHANNELS];
            pieces.add(gap);
            total += gap.length;
        }
        short[] out = new short[total];
        int pos = 0;
        for (short[] s : pieces) {
            System.arraycopy(s, 0, out, pos, s.length);
            pos += s.length;
        }
        return out;
    }

    /** Speaks with the free voices only (Piper, Windows, flite). */
    public short[] speak(String text, String voiceName) {
        return speak(text, voiceName, false);
    }

    /**
     * Speaks the text and returns raw stereo samples, or null (see {@link #lastVoiceError()}). voiceName picks a
     * Piper voice, or "openai:NAME" for an OpenAI voice, which is only used when premium is true (someone is
     * listening) - otherwise the first Piper voice is used.
     */
    public short[] speak(String text, String voiceName, boolean premium) {
        if (text == null || text.trim().isEmpty()) {
            lastVoiceError = "nothing to say";
            return null;
        }
        if (ffmpeg == null) {
            lastVoiceError = "FFmpeg is not installed";
            return null;
        }
        lastVoiceError = "";
        StringBuilder why = new StringBuilder();
        List<Path> temp = new ArrayList<Path>();
        try {
            Files.createDirectories(tmpDir);
            String voice = voiceName == null ? "" : voiceName.trim();
            if (voice.toLowerCase(Locale.ROOT).startsWith("openai:")) {
                if (premium && openAiReady()) {
                    short[] pcm = openAiVoice(text, voice.substring(7), temp, why);
                    if (pcm != null) {
                        return pcm;
                    }
                }
                voice = "";
            }
            List<String> parts = sentences(text);
            long seed = text.hashCode() * 31L + System.nanoTime() / 1_000_000_000L;
            String engine = voiceEngine();
            if ("piper".equals(engine)) {
                List<Path> wavs = piperSentences(parts, voice, temp, why);
                short[] pcm = wavs == null ? null : assemble(parts, wavs, seed);
                if (pcm != null && pcm.length >= RATE) {
                    return pcm;
                }
                Path wav = tempFile(temp, ".wav");
                if (piper(text, voice, wav, why)) {
                    return decodeFiltered(wav, BROADCAST);
                }
            }
            if (sapi) {
                Path wav = tempFile(temp, ".wav");
                if (windowsVoice(parts, seed, wav, temp, why)) {
                    return decodeFiltered(wav, BROADCAST);
                }
            }
            if (flite) {
                List<Path> wavs = new ArrayList<Path>();
                for (String part : parts) {
                    Path txt = tempFile(temp, ".txt");
                    Files.write(txt, part.getBytes(StandardCharsets.UTF_8));
                    Path wav = tempFile(temp, ".wav");
                    wavs.add(fliteVoice(txt, wav, why) ? wav : null);
                }
                short[] pcm = assemble(parts, wavs, seed);
                if (pcm != null && pcm.length >= RATE) {
                    return pcm;
                }
            }
            lastVoiceError = why.length() == 0 ? "no voice program is installed" : why.toString();
            System.out.println("[radio] the DJ voice failed: " + lastVoiceError);
            return null;
        } catch (IOException e) {
            lastVoiceError = e.getMessage();
            return null;
        } finally {
            for (Path t : temp) {
                try {
                    Files.deleteIfExists(t);
                } catch (IOException e) {
                    // cleaned up next time
                }
            }
        }
    }

    private Path tempFile(List<Path> temp, String ext) throws IOException {
        Path p = Files.createTempFile(tmpDir, "dj-", ext);
        temp.add(p);
        return p;
    }

    private Path piperModel(String voiceName) {
        Path model = null;
        for (Path p : voices) {
            if (p.getFileName().toString().replaceAll("\\.onnx$", "").equalsIgnoreCase(voiceName == null ? "" : voiceName)) {
                model = p;
            }
        }
        if (model == null) {
            // prefer a "high" quality voice when none is chosen
            for (Path p : voices) {
                if (model == null && p.getFileName().toString().contains("-high")) {
                    model = p;
                }
            }
        }
        if (model == null && !voices.isEmpty()) {
            model = voices.get(0);
        }
        return model;
    }

    /** All sentences in one Piper run (one file each), a little livelier than Piper's defaults. */
    private List<Path> piperSentences(List<String> parts, String voiceName, List<Path> temp, StringBuilder why)
            throws IOException {
        Path model = piperModel(voiceName);
        if (model == null) {
            why.append("Piper: no voice file. ");
            return null;
        }
        List<Path> wavs = new ArrayList<Path>();
        StringBuilder lines = new StringBuilder();
        for (String part : parts) {
            Path w = tempFile(temp, ".wav");
            Files.deleteIfExists(w);
            wavs.add(w);
            lines.append("{\"text\":").append(ProfileApi.quote(part)).append(",\"output_file\":")
                    .append(ProfileApi.quote(w.toAbsolutePath().toString())).append("}\n");
        }
        String[] r = runOut(new File(piper).getAbsoluteFile().getParentFile(), lines.toString(), piper, "--model",
                model.toAbsolutePath().toString(), "--json-input", "--length_scale", "0.93", "--noise_scale", "0.75",
                "--noise_w", "0.9");
        for (Path w : wavs) {
            if (!Files.isRegularFile(w)) {
                why.append("Piper (sentence by sentence) failed (exit ").append(r[0]).append("): ")
                        .append(lastLine(r[1])).append(". ");
                return null;
            }
        }
        return wavs;
    }

    /** An OpenAI voice told to sound like a lively radio DJ. */
    private short[] openAiVoice(String text, String voice, List<Path> temp, StringBuilder why) {
        String v = OPENAI_VOICES.contains(voice.toLowerCase(Locale.ROOT)) ? voice.toLowerCase(Locale.ROOT) : "ash";
        String instructions = "You are a charismatic, upbeat radio DJ live on air. Speak naturally like a real human "
                + "host: warm smile in the voice, lively rhythm, rising and falling pitch, punchy pacing, a little "
                + "excitement on song titles and artist names, short natural pauses between sentences. When the news is "
                + "serious, sound calm and caring instead of excited.";
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(
                    System.getProperty("vstream.openaiSpeechUrl", "https://api.openai.com/v1/audio/speech")).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(10000);
            c.setReadTimeout(60000);
            c.setDoOutput(true);
            c.setRequestProperty("Authorization", "Bearer " + openAiKey);
            c.setRequestProperty("Content-Type", "application/json");
            String body = "{\"model\":" + ProfileApi.quote(openAiModel) + ",\"voice\":" + ProfileApi.quote(v)
                    + ",\"input\":" + ProfileApi.quote(text) + ",\"instructions\":" + ProfileApi.quote(instructions)
                    + ",\"response_format\":\"wav\"}";
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes(StandardCharsets.UTF_8));
            os.close();
            int code = c.getResponseCode();
            byte[] data = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
            c.disconnect();
            if (code != 200 || data.length < 2000) {
                why.append("OpenAI voice failed (HTTP ").append(code).append("). ");
                return null;
            }
            Path wav = tempFile(temp, ".wav");
            Files.write(wav, data);
            short[] pcm = decodeFiltered(wav, BROADCAST);
            return pcm.length >= RATE ? pcm : null;
        } catch (IOException e) {
            why.append("OpenAI voice failed: ").append(e.getMessage()).append(". ");
            return null;
        }
    }

    private boolean piper(String text, String voiceName, Path wav, StringBuilder why) {
        Path model = piperModel(voiceName);
        if (model == null) {
            why.append("Piper: no voice file. ");
            return false;
        }
        String[] r = runOut(new File(piper).getAbsoluteFile().getParentFile(), text.replace('\n', ' ') + "\n",
                piper, "--model", model.toAbsolutePath().toString(), "--output_file", wav.toAbsolutePath().toString(),
                "--length_scale", "0.93", "--noise_scale", "0.75", "--noise_w", "0.9");
        long size = 0;
        try {
            size = Files.size(wav);
        } catch (IOException e) {
            size = 0;
        }
        if ("0".equals(r[0]) && size > 2000) {
            return true;
        }
        why.append("Piper failed (exit ").append(r[0]).append("): ").append(lastLine(r[1])).append(". ");
        return false;
    }

    private static String lastLine(String out) {
        String[] lines = out == null ? new String[0] : out.trim().split("\\r?\\n");
        return lines.length == 0 || lines[lines.length - 1].trim().isEmpty() ? "no message" : lines[lines.length - 1].trim();
    }

    /** The Windows voice, with the same rise and fall written as SSML (pitch, rate and pauses per sentence). */
    private boolean windowsVoice(List<String> parts, long seed, Path wav, List<Path> temp, StringBuilder why)
            throws IOException {
        java.util.Random r = new java.util.Random(seed);
        StringBuilder ssml = new StringBuilder("<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" "
                + "xml:lang=\"en-US\">");
        for (int i = 0; i < parts.size(); i++) {
            Prosody p = prosody(parts.get(i), i, parts.size(), r);
            ssml.append(String.format(Locale.ROOT, "<prosody pitch=\"%+d%%\" rate=\"%+d%%\">",
                    Math.round((p.pitch - 1) * 200), Math.round((p.tempo - 1) * 100) + 5));
            ssml.append(parts.get(i).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"));
            ssml.append("</prosody>");
            if (p.pauseMs > 0) {
                ssml.append("<break time=\"").append(p.pauseMs).append("ms\"/>");
            }
        }
        ssml.append("</speak>");
        Path xml = tempFile(temp, ".xml");
        Files.write(xml, ssml.toString().getBytes(StandardCharsets.UTF_8));
        String script = "Add-Type -AssemblyName System.Speech; "
                + "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; "
                + "$s.SetOutputToWaveFile('" + wav.toAbsolutePath().toString().replace("'", "''") + "'); "
                + "$s.SpeakSsml([System.IO.File]::ReadAllText('" + xml.toAbsolutePath().toString().replace("'", "''") + "')); "
                + "$s.Dispose()";
        String[] r2 = runOut(null, null, "powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-Command", script);
        try {
            if (Files.size(wav) > 2000) {
                return true;
            }
        } catch (IOException e) {
            // fall through
        }
        why.append("Windows voice failed (exit ").append(r2[0]).append("): ").append(lastLine(r2[1])).append(". ");
        return false;
    }

    private boolean fliteVoice(Path txt, Path wav, StringBuilder why) {
        String f = txt.toAbsolutePath().toString().replace("\\", "/").replace(":", "\\:");
        String out = runOk(ffmpeg, "-v", "error", "-y", "-f", "lavfi", "-i", "flite=textfile='" + f + "':voice=slt",
                wav.toAbsolutePath().toString());
        try {
            if (Files.size(wav) > 2000) {
                return true;
            }
        } catch (IOException e) {
            // fall through
        }
        why.append("flite voice failed: ").append(lastLine(out)).append(". ");
        return false;
    }
}
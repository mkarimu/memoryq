package com.vstream.Vidiserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Paid ads (advertiser, headline, text, link, picture, start and end dates) with view and tap counts, plus the
 * settings of the news slider: on or off, how many stories between sliders, whether it carries an ad card, and the
 * Google (AdMob) ad unit used when no paid ad is running. Saved to ads.txt; pictures go in the ads folder.
 */
public final class AdStore {

    public static final String GOOGLE_TEST_UNIT = "ca-app-pub-3940256099942544/6300978111";
    public static final int MAX_IMAGE = 1024 * 1024;

    public static final class Ad {
        public long id;
        public String advertiser = "";
        public String headline = "";
        public String text = "";
        public String link = "";
        public String image = "";
        public long start;
        public long end;
        public boolean active = true;
        public long views;
        public long clicks;
        public long created;

        Ad copy() {
            Ad a = new Ad();
            a.id = id;
            a.advertiser = advertiser;
            a.headline = headline;
            a.text = text;
            a.link = link;
            a.image = image;
            a.start = start;
            a.end = end;
            a.active = active;
            a.views = views;
            a.clicks = clicks;
            a.created = created;
            return a;
        }

        public boolean runningAt(long now) {
            return active && (start == 0 || now >= start) && (end == 0 || now < end);
        }
    }

    public static final class Settings {
        public boolean sliderOn = true;
        public int every = 20;
        public boolean adCardOn = true;
        public boolean googleOn = true;
        public String googleUnit = GOOGLE_TEST_UNIT;
    }

    private final Path file;
    private final Path dir;
    private final Map<Long, Ad> ads = new LinkedHashMap<Long, Ad>();
    private Settings settings = new Settings();
    private long nextId = 1;
    private boolean dirty = false;

    public AdStore(Path file, Path imageDir) {
        this.file = file;
        this.dir = imageDir;
    }

    public synchronized void init() throws IOException {
        ads.clear();
        settings = new Settings();
        nextId = 1;
        Files.createDirectories(dir);
        if (Files.exists(file)) {
            load();
        }
    }

    // ---- Settings ----

    public synchronized Settings settings() {
        Settings s = new Settings();
        s.sliderOn = settings.sliderOn;
        s.every = settings.every;
        s.adCardOn = settings.adCardOn;
        s.googleOn = settings.googleOn;
        s.googleUnit = settings.googleUnit;
        return s;
    }

    public synchronized void setSettings(Settings s) {
        settings = s;
        dirty = true;
    }

    // ---- Ads ----

    public synchronized List<Ad> all() {
        List<Ad> out = new ArrayList<Ad>();
        for (Ad a : ads.values()) {
            out.add(a.copy());
        }
        return out;
    }

    public synchronized Ad get(long id) {
        Ad a = ads.get(id);
        return a == null ? null : a.copy();
    }

    /** Adds an ad; the picture must already be checked (PNG or JPEG, not too big). */
    public synchronized Ad create(String advertiser, String headline, String text, String link, long start, long end,
                                  byte[] image, String ext, long now) throws IOException {
        Ad a = new Ad();
        a.id = nextId++;
        a.advertiser = advertiser;
        a.headline = headline;
        a.text = text;
        a.link = link;
        a.start = start;
        a.end = end;
        a.created = now;
        a.image = "ad-" + a.id + "." + ext;
        Files.write(dir.resolve(a.image), image);
        ads.put(a.id, a);
        dirty = true;
        flushIfDirty();
        return a.copy();
    }

    public synchronized boolean setActive(long id, boolean active) {
        Ad a = ads.get(id);
        if (a == null) {
            return false;
        }
        a.active = active;
        dirty = true;
        return true;
    }

    public synchronized boolean delete(long id) {
        Ad a = ads.remove(id);
        if (a == null) {
            return false;
        }
        try {
            Files.deleteIfExists(dir.resolve(a.image));
        } catch (IOException e) {
            // the picture file is left behind; harmless
        }
        dirty = true;
        return true;
    }

    /** The picture of an ad, or null. */
    public synchronized byte[] image(long id) {
        Ad a = ads.get(id);
        if (a == null || a.image.isEmpty()) {
            return null;
        }
        try {
            return Files.readAllBytes(dir.resolve(a.image));
        } catch (IOException e) {
            return null;
        }
    }

    /** A paid ad that is running now (chosen at random), or null. */
    public synchronized Ad pick(long now, Random random) {
        List<Ad> running = new ArrayList<Ad>();
        for (Ad a : ads.values()) {
            if (a.runningAt(now)) {
                running.add(a);
            }
        }
        return running.isEmpty() ? null : running.get(random.nextInt(running.size())).copy();
    }

    public synchronized void countView(long id) {
        Ad a = ads.get(id);
        if (a != null) {
            a.views++;
            dirty = true;
        }
    }

    public synchronized String countClick(long id) {
        Ad a = ads.get(id);
        if (a == null) {
            return null;
        }
        a.clicks++;
        dirty = true;
        return a.link;
    }

    // ---- Storage ----

    private void load() throws IOException {
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            try {
                if ("S".equals(f[0]) && f.length >= 6) {
                    settings.sliderOn = "1".equals(f[1]);
                    settings.every = Integer.parseInt(f[2]);
                    settings.adCardOn = "1".equals(f[3]);
                    settings.googleOn = "1".equals(f[4]);
                    settings.googleUnit = f[5];
                } else if ("A".equals(f[0]) && f.length >= 13) {
                    Ad a = new Ad();
                    a.id = Long.parseLong(f[1]);
                    a.advertiser = f[2];
                    a.headline = f[3];
                    a.text = f[4];
                    a.link = f[5];
                    a.image = f[6];
                    a.start = Long.parseLong(f[7]);
                    a.end = Long.parseLong(f[8]);
                    a.active = "1".equals(f[9]);
                    a.views = Long.parseLong(f[10]);
                    a.clicks = Long.parseLong(f[11]);
                    a.created = Long.parseLong(f[12]);
                    ads.put(a.id, a);
                    nextId = Math.max(nextId, a.id + 1);
                }
            } catch (RuntimeException e) {
                // skip a damaged line
            }
        }
    }

    public synchronized void flushIfDirty() {
        if (!dirty) {
            return;
        }
        try {
            save();
        } catch (IOException e) {
            System.out.println("[ads] could not save: " + e.getMessage());
        }
    }

    private void save() throws IOException {
        StringBuilder sb = new StringBuilder("# VStream ads and news slider settings\n");
        sb.append("S\t").append(settings.sliderOn ? "1" : "0").append('\t').append(settings.every).append('\t')
                .append(settings.adCardOn ? "1" : "0").append('\t').append(settings.googleOn ? "1" : "0").append('\t')
                .append(settings.googleUnit).append('\n');
        for (Ad a : ads.values()) {
            sb.append("A\t").append(a.id).append('\t').append(a.advertiser).append('\t').append(a.headline).append('\t')
                    .append(a.text).append('\t').append(a.link).append('\t').append(a.image).append('\t').append(a.start)
                    .append('\t').append(a.end).append('\t').append(a.active ? "1" : "0").append('\t').append(a.views)
                    .append('\t').append(a.clicks).append('\t').append(a.created).append("\tend\n");
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, sb.toString().getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        dirty = false;
    }
}
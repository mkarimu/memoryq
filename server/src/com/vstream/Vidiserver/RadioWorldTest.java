package com.vstream.Vidiserver;

import java.nio.file.Files;
import java.nio.file.Path;

/** Memoryq's own radio stations on Global Radio: the place is kept with the station, and the globe follows on air / off air. */
public final class RadioWorldTest {

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
        Path dir = Files.createTempDirectory("memoryq-radioworld");
        long now = 1792700000000L;
        RadioStore store = new RadioStore(dir.resolve("radio.txt"), dir.resolve("music"));
        store.init();
        RadioStore.Station st = store.createStation("Mkarimu Radio", "The voice of the lake zone", "", RadioStore.STATUS_ACTIVE, now);
        RadioStore.Station off = store.createStation("Quiet FM", "", "", RadioStore.STATUS_ACTIVE, now);

        System.out.println("-- The place is kept with the station --");
        checkTrue("no place to start with", !store.station(st.id).hasPlace() && store.station(st.id).worldOn);
        store.setPlace(st.id, true, -2.51643, 32.91751, "Mwanza", "Tanzania", "tz");
        store.setWebsite(st.id, "http://mkarimu.net/");
        boolean refused = false;
        try {
            store.setPlace(st.id, null, 95.0, 10.0, null, null, null);
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        checkTrue("a latitude past 90 is refused", refused);
        store.flushIfDirty();
        RadioStore again = new RadioStore(dir.resolve("radio.txt"), dir.resolve("music"));
        again.init();
        RadioStore.Station r = again.station(st.id);
        checkTrue("kept across a restart (" + r.lat + "," + r.lon + " " + r.city + " " + r.cc + ")", r.lat == -2.5164 && r.lon == 32.9175
                && r.city.equals("Mwanza") && r.cc.equals("TZ") && r.worldOn);
        checkTrue("an older station line (no place) still reads", again.station(off.id) != null && !again.station(off.id).hasPlace());

        System.out.println("-- On the globe --");
        WorldRadio w = new WorldRadio(dir.resolve("world-radio.txt"), null, new SessionStore(), () -> now);
        w.init();
        w.setMemoryqStations(store::stations, "https://memoryq.com");
        RadioStore.Station s = store.station(st.id);
        s.enabled = true;
        store.updateStation(s);
        w.syncMemoryq();
        java.util.List<WorldRadio.Station> found = w.search("mkarimu", "", null, 5);
        checkTrue("on air with a place: on the globe", found.size() == 1 && found.get(0).source.equals("memoryq")
                && found.get(0).url.equals("https://memoryq.com/radio/" + st.id + ".mp3") && found.get(0).slogan.equals("The voice of the lake zone")
                && found.get(0).country.equals("Tanzania") && found.get(0).home.equals("http://mkarimu.net/"));
        checkTrue("searching the slogan finds it", w.search("lake zone", "", null, 5).size() == 1);
        checkTrue("no place: not on the globe", w.search("quiet", "", null, 5).isEmpty());
        store.setPlace(st.id, false, null, null, null, null, null);
        w.syncMemoryq();
        checkTrue("Global Radio switched off for it: taken off", w.search("mkarimu", "", null, 5).isEmpty());
        store.setPlace(st.id, true, null, null, null, null, null);
        s = store.station(st.id);
        s.enabled = false;
        store.updateStation(s);
        w.syncMemoryq();
        checkTrue("off air: not on the globe", w.search("mkarimu", "", null, 5).isEmpty());
        s.enabled = true;
        store.updateStation(s);
        w.syncMemoryq();
        checkTrue("back on air: back on the globe, once", w.search("mkarimu", "", null, 5).size() == 1 && w.count("memoryq") == 1);
        w.flushIfDirty();
        WorldRadio w2 = new WorldRadio(dir.resolve("world-radio.txt"), null, new SessionStore(), () -> now);
        w2.init();
        checkTrue("the slogan is kept", w2.search("lake", "", null, 5).size() == 1);
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void checkTrue(String name, boolean ok) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + name);
        if (!ok) {
            failures++;
        }
    }
}
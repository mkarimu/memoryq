package com.vstream.Vidiserver;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Counts events per key inside a time window. Used to slow down password guessing. */
public final class Limiter {

    private final LongSupplier clock;
    private final Map<String, ArrayDeque<Long>> hits = new HashMap<String, ArrayDeque<Long>>();

    public Limiter() {
        this(System::currentTimeMillis);
    }

    public Limiter(LongSupplier clock) {
        this.clock = clock;
    }

    public synchronized int count(String key, long windowMs) {
        ArrayDeque<Long> d = hits.get(key);
        if (d == null) {
            return 0;
        }
        long cutoff = clock.getAsLong() - windowMs;
        while (!d.isEmpty() && d.peekFirst() < cutoff) {
            d.pollFirst();
        }
        if (d.isEmpty()) {
            hits.remove(key);
            return 0;
        }
        return d.size();
    }

    public synchronized void record(String key) {
        if (hits.size() > 20000) {
            hits.clear();
        }
        ArrayDeque<Long> d = hits.get(key);
        if (d == null) {
            d = new ArrayDeque<Long>();
            hits.put(key, d);
        }
        d.addLast(clock.getAsLong());
    }

    public synchronized void clear(String key) {
        hits.remove(key);
    }
}
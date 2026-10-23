package com.vstream.Vidiserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates fixed preset channels (CHANNEL-001..N) with tokens saved in a file,
 * so the same stream keys keep working after a restart.
 */
public final class StreamKeyStore {

    private StreamKeyStore() {
    }

    /** Returns one stream key per preset channel, in the form CHANNEL-001?token=... */
    public static List<String> provision(LiveChannelRegistry registry, int count, Path file)
            throws IOException {
        List<String> tokens = new ArrayList<String>();
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.matches("[0-9a-f]{48}")) {
                    tokens.add(t);
                }
            }
        }
        boolean changed = false;
        while (tokens.size() < count) {
            tokens.add(LiveChannelRegistry.newToken());
            changed = true;
        }
        if (changed) {
            Files.write(file, tokens.subList(0, count), StandardCharsets.UTF_8);
        }

        List<String> keys = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            String id = registry.registerChannel("preset-" + (i + 1), "");
            LiveChannel channel = registry.getChannel(id);
            channel.setStreamAddress(StreamUrls.hlsPlayback(id));
            channel.setPublishToken(tokens.get(i));
            keys.add(id + "?token=" + tokens.get(i));
        }
        return keys;
    }
}
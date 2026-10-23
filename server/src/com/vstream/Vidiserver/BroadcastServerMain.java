package com.vstream.Vidiserver;

import java.util.List;

public class BroadcastServerMain {

    public static void main(String[] args) throws Exception {
        LiveChannelRegistry registry = new LiveChannelRegistry();
        BroadcastRegistrationServer server = new BroadcastRegistrationServer(registry);
        server.start();
        System.out.println("Press Ctrl+C to stop.");

        String last = "";
        while (true) {
            Thread.sleep(1000);
            StringBuilder sb = new StringBuilder();
            List<LiveChannel> live = registry.getLiveChannels();
            for (LiveChannel c : live) {
                sb.append(c.getChannelId()).append(" (").append(c.getBroadcasterId()).append(") LIVE\n");
            }
            String now = sb.length() == 0 ? "(no live channels)\n" : sb.toString();
            if (!now.equals(last)) {
                System.out.println("--- Live channels ---");
                System.out.print(now);
                last = now;
            }
        }
    }
}
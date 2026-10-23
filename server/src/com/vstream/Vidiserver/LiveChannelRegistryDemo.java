package com.vstream.Vidiserver;

import java.util.List;

/**
 * Stage 1 demo: exercises LiveChannelRegistry without any networking.
 */
public class LiveChannelRegistryDemo {

    public static void main(String[] args) throws InterruptedException {
        LiveChannelRegistry registry = new LiveChannelRegistry();

        String idA = registry.registerChannel("user001", "rtmp://localhost/live/a");
        String idB = registry.registerChannel("user002", "rtmp://localhost/live/b");
        String idC = registry.registerChannel("user003", "rtmp://localhost/live/c");

        System.out.println("Generated: " + idA + ", " + idB + ", " + idC);

        System.out.println();
        System.out.println("Active channels:");
        printLive(registry);

        long before = registry.getChannel(idA).getLastActivity();
        Thread.sleep(10);
        registry.updateActivity(idA);
        long after = registry.getChannel(idA).getLastActivity();
        System.out.println();
        System.out.println("Activity updated for " + idA + ": " + (after > before));

        boolean removed = registry.removeChannel(idB);
        System.out.println("Removed " + idB + ": " + removed);

        System.out.println();
        System.out.println("Active channels after removing " + idB + ":");
        printLive(registry);

        System.out.println();
        System.out.println(idB + " exists: " + registry.channelExists(idB));
        System.out.println(idA + " exists: " + registry.channelExists(idA));
    }

    private static void printLive(LiveChannelRegistry registry) {
        List<LiveChannel> live = registry.getLiveChannels();
        for (LiveChannel channel : live) {
            System.out.println(channel);
        }
    }
}
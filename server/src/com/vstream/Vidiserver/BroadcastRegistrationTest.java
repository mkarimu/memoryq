package com.vstream.Vidiserver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class BroadcastRegistrationTest {

    private static class FakeBroadcaster {
        final Socket socket;
        final BufferedReader in;
        final PrintWriter out;

        FakeBroadcaster(int port) throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(5000);
            in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        }

        String send(String line) throws IOException {
            out.println(line);
            return in.readLine();
        }

        void disconnect() throws IOException {
            socket.close();
        }
    }

    public static void main(String[] args) throws Exception {
        LiveChannelRegistry registry = new LiveChannelRegistry();
        BroadcastRegistrationServer server = new BroadcastRegistrationServer(registry, 0);
        server.start();
        int port = server.getPort();

        System.out.println();
        System.out.println("Broadcaster A connecting as user001...");
        FakeBroadcaster a = new FakeBroadcaster(port);
        String replyA = a.send("BROADCAST user001");
        System.out.println("Broadcaster A received: " + replyA);

        System.out.println();
        System.out.println("Broadcaster B connecting as user002...");
        FakeBroadcaster b = new FakeBroadcaster(port);
        String replyB = b.send("BROADCAST user002");
        System.out.println("Broadcaster B received: " + replyB);

        System.out.println();
        System.out.println("Live channels:");
        printLive(registry);

        long before = registry.getChannel("CHANNEL-001").getLastActivity();
        Thread.sleep(20);
        String pong = a.send("PING");
        long after = registry.getChannel("CHANNEL-001").getLastActivity();
        System.out.println();
        System.out.println("A sent PING, received: " + pong);
        System.out.println("CHANNEL-001 lastActivity updated: " + (after > before));

        System.out.println();
        System.out.println("Disconnecting A...");
        a.disconnect();
        boolean removedA = waitUntilGone(registry, "CHANNEL-001", 3000);
        System.out.println("CHANNEL-001 removed: " + removedA);

        System.out.println();
        System.out.println("Live channels:");
        printLive(registry);

        System.out.println();
        System.out.println("Disconnecting B...");
        b.disconnect();
        boolean removedB = waitUntilGone(registry, "CHANNEL-002", 3000);
        System.out.println("CHANNEL-002 removed: " + removedB);

        System.out.println();
        System.out.println("Live channels:");
        printLive(registry);

        server.stop();
    }

    private static boolean waitUntilGone(LiveChannelRegistry registry, String channelId,
                                         long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!registry.channelExists(channelId)) {
                return true;
            }
            Thread.sleep(20);
        }
        return !registry.channelExists(channelId);
    }

    private static void printLive(LiveChannelRegistry registry) {
        List<LiveChannel> live = registry.getLiveChannels();
        if (live.isEmpty()) {
            System.out.println("(none)");
            return;
        }
        for (LiveChannel channel : live) {
            System.out.println(channel);
        }
    }
}
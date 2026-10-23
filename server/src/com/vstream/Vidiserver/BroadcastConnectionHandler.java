package com.vstream.Vidiserver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

public class BroadcastConnectionHandler implements Runnable {

    private static final int MAX_ID_LENGTH = 64;

    private final Socket socket;
    private final LiveChannelRegistry registry;
    private final int registerTimeoutMs;
    private final int activityTimeoutMs;

    public BroadcastConnectionHandler(Socket socket, LiveChannelRegistry registry,
                                      int registerTimeoutMs, int activityTimeoutMs) {
        this.socket = socket;
        this.registry = registry;
        this.registerTimeoutMs = registerTimeoutMs;
        this.activityTimeoutMs = activityTimeoutMs;
    }

    @Override
    public void run() {
        String channelId = null;
        try {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            socket.setSoTimeout(registerTimeoutMs);
            String line = in.readLine();
            if (line == null) {
                return;
            }

            String broadcasterId = parseBroadcasterId(line);
            if (broadcasterId == null) {
                out.println("ERROR expected: BROADCAST <broadcasterId>");
                System.out.println("[server] Rejected connection from "
                        + socket.getRemoteSocketAddress() + ": bad registration line");
                return;
            }

            String streamAddress = String.valueOf(socket.getRemoteSocketAddress());
            channelId = registry.registerChannel(broadcasterId, streamAddress);
            LiveChannel channel = registry.getChannel(channelId);
            String playbackUrl = StreamUrls.hlsPlayback(channelId);
            channel.setStreamAddress(playbackUrl);

            System.out.println("[server] Broadcaster " + broadcasterId + " registered as "
                    + channelId + " (" + playbackUrl + ")");
            // Reply: OK <channelId> <publishToken>. The token is never logged.
            out.println("OK " + channelId + " " + channel.getPublishToken());

            socket.setSoTimeout(activityTimeoutMs);
            while ((line = in.readLine()) != null) {
                registry.updateActivity(channelId);
                String command = line.trim();
                if (command.equalsIgnoreCase("QUIT")) {
                    break;
                }
                if (command.equalsIgnoreCase("PING")) {
                    out.println("PONG");
                }
            }
        } catch (SocketTimeoutException e) {
            System.out.println("[server] Timeout, no data from "
                    + (channelId != null ? channelId : String.valueOf(socket.getRemoteSocketAddress())));
        } catch (IOException e) {
            System.out.println("[server] Connection error: " + e.getMessage());
        } finally {
            if (channelId != null) {
                System.out.println("[server] Connection closed, removing " + channelId);
                registry.removeChannel(channelId);
                MediaMtxClient.kickPublisherAsync(channelId);
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String parseBroadcasterId(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length != 2 || !parts[0].equalsIgnoreCase("BROADCAST")) {
            return null;
        }
        String id = parts[1];
        if (id.isEmpty() || id.length() > MAX_ID_LENGTH) {
            return null;
        }
        return id;
    }
}
package com.vstream.Vidiserver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Stage 3 test: two simulated broadcasters, a simulated viewer fetching /channels.
 */
public class ChannelListHttpTest {

    private static class FakeBroadcaster {
        final Socket socket;
        final BufferedReader in;
        final PrintWriter out;

        FakeBroadcaster(int port, String broadcasterId) throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(5000);
            in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            out.println("BROADCAST " + broadcasterId);
            System.out.println(broadcasterId + " received: " + in.readLine());
        }

        void disconnect() throws IOException {
            socket.close();
        }
    }

    public static void main(String[] args) throws Exception {
        LiveChannelRegistry registry = new LiveChannelRegistry();
        BroadcastRegistrationServer broadcastServer = new BroadcastRegistrationServer(registry, 0);
        broadcastServer.start();
        ChannelListHttpServer listServer = new ChannelListHttpServer(registry, 0);
        listServer.start();
        int httpPort = listServer.getPort();

        System.out.println();
        System.out.println("Viewer: GET /channels (no broadcasters yet)");
        get(httpPort, "/channels");

        System.out.println();
        FakeBroadcaster a = new FakeBroadcaster(broadcastServer.getPort(), "user001");
        FakeBroadcaster b = new FakeBroadcaster(broadcastServer.getPort(), "user002");

        System.out.println();
        System.out.println("Viewer: GET /channels (A and B live)");
        get(httpPort, "/channels");

        System.out.println();
        System.out.println("Disconnecting A...");
        a.disconnect();
        waitUntilGone(registry, "CHANNEL-001");

        System.out.println();
        System.out.println("Viewer: GET /channels (only B should remain)");
        get(httpPort, "/channels");

        System.out.println();
        System.out.println("Viewer: GET /nothing (expect 404)");
        get(httpPort, "/nothing");

        System.out.println();
        System.out.println("Viewer: POST /channels (expect 405)");
        request(httpPort, "/channels", "POST");

        System.out.println();
        System.out.println("Disconnecting B...");
        b.disconnect();
        waitUntilGone(registry, "CHANNEL-002");

        System.out.println();
        System.out.println("Viewer: GET /channels (expect empty)");
        get(httpPort, "/channels");

        listServer.stop();
        broadcastServer.stop();
    }

    private static void get(int port, String path) throws IOException {
        request(port, path, "GET");
    }

    private static void request(int port, String path, String method) throws IOException {
        URL url = new URL("http://127.0.0.1:" + port + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);
        int status = conn.getResponseCode();
        java.io.InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder body = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            body.append(line);
        }
        reader.close();
        System.out.println("HTTP " + status + " " + body);
    }

    private static void waitUntilGone(LiveChannelRegistry registry, String id)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (registry.channelExists(id) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }
}
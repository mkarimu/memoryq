package com.vstream.Vidiserver;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public class BroadcastRegistrationServer {

    public static final int DEFAULT_PORT = 9100;
    private static final int DEFAULT_REGISTER_TIMEOUT_MS = 10000;
    private static final int DEFAULT_ACTIVITY_TIMEOUT_MS = 30000;

    private final LiveChannelRegistry registry;
    private final int requestedPort;
    private final int registerTimeoutMs;
    private final int activityTimeoutMs;

    private final Set<Socket> openSockets =
            Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());

    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private ExecutorService executor;
    private Thread acceptThread;

    public BroadcastRegistrationServer(LiveChannelRegistry registry) {
        this(registry, DEFAULT_PORT);
    }

    public BroadcastRegistrationServer(LiveChannelRegistry registry, int port) {
        this(registry, port, DEFAULT_REGISTER_TIMEOUT_MS, DEFAULT_ACTIVITY_TIMEOUT_MS);
    }

    public BroadcastRegistrationServer(LiveChannelRegistry registry, int port,
                                       int registerTimeoutMs, int activityTimeoutMs) {
        this.registry = registry;
        this.requestedPort = port;
        this.registerTimeoutMs = registerTimeoutMs;
        this.activityTimeoutMs = activityTimeoutMs;
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        serverSocket = new ServerSocket(requestedPort);
        running = true;

        executor = Executors.newCachedThreadPool(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "broadcast-connection");
                t.setDaemon(true);
                return t;
            }
        });

        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "broadcast-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();

        System.out.println("[server] Broadcast registration listening on port " + getPort());
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket socket = serverSocket.accept();
                openSockets.add(socket);
                final BroadcastConnectionHandler handler = new BroadcastConnectionHandler(
                        socket, registry, registerTimeoutMs, activityTimeoutMs);
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            handler.run();
                        } finally {
                            openSockets.remove(socket);
                        }
                    }
                });
            } catch (IOException e) {
                if (running) {
                    System.out.println("[server] Accept error: " + e.getMessage());
                }
            }
        }
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
        for (Socket s : openSockets) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        executor.shutdown();
        System.out.println("[server] Broadcast registration stopped");
    }

    public int getPort() {
        return serverSocket == null ? -1 : serverSocket.getLocalPort();
    }

    public LiveChannelRegistry getRegistry() {
        return registry;
    }
}
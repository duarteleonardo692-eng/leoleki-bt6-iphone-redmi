package app.leoleki.bt6.redmi;

import java.io.*;
import java.net.*;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Loopback-only SOCKS byte relay. Real code also exercised by the desktop integration tests. */
public final class StreamBridge implements AutoCloseable {
    public interface Link extends AutoCloseable {
        InputStream input() throws IOException;
        OutputStream output() throws IOException;
        void close() throws IOException;
    }
    public interface Factory { Link open() throws IOException; }
    private final Factory factory;
    private final java.util.function.Consumer<String> diagnostics;
    private final Semaphore connecting = new Semaphore(1, true);
    private final Set<Session> sessions = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers = Executors.newFixedThreadPool(64);
    private final Semaphore slots = new Semaphore(32);
    static final int MAX_CHANNELS = 8; // Reserve margin while iOS asynchronously retires old channels.
    private final Semaphore channels = new Semaphore(MAX_CHANNELS, true);
    private final ServerSocket server;
    private volatile boolean closed;
    public final AtomicLong sent = new AtomicLong(), received = new AtomicLong();
    private Thread acceptor;

    public StreamBridge(Factory factory) throws IOException {
        this(factory, message -> {});
    }
    public StreamBridge(Factory factory, java.util.function.Consumer<String> diagnostics) throws IOException {
        this.factory = factory; this.diagnostics = diagnostics;
        server = new ServerSocket(0, 12, InetAddress.getByName("127.0.0.1"));
        acceptor = new Thread(this::accept, "BT6-accept");
        acceptor.setDaemon(true); acceptor.start();
    }
    public int port() { return server.getLocalPort(); }
    private void accept() {
        while (!closed) {
            try {
                Socket client = server.accept();
                if (!slots.tryAcquire()) { diagnostics.accept("Fila do relay cheia"); client.close(); continue; }
                Session session = new Session(client);
                sessions.add(session);
                if (closed) { session.close(); break; }
                try { workers.execute(session::start); }
                catch (RejectedExecutionException e) { session.close(); }
            } catch (IOException e) { if (!closed) close(); break; }
        }
    }
    private final class Session implements AutoCloseable {
        final Socket client;
        volatile Link link;
        volatile boolean ended;
        boolean channelHeld;
        volatile long lastActivity = System.nanoTime();
        Session(Socket client) { this.client = client; }
        void start() {
            try {
                client.setTcpNoDelay(true);
                client.setSoTimeout(30000);
                InputStream originalInput = client.getInputStream();
                int first = originalInput.read();
                if (first < 0) { close(); return; }
                InputStream clientInput = new SequenceInputStream(new ByteArrayInputStream(new byte[]{(byte)first}), originalInput);
                client.setSoTimeout(0);
                long waitStart = System.nanoTime();
                while (!channels.tryAcquire(1, TimeUnit.SECONDS)) {
                    if (closed || ended) throw new IOException("Relay closed while queued");
                    if (System.nanoTime() - waitStart > TimeUnit.SECONDS.toNanos(30)) throw new IOException("Fila Bluetooth expirou");
                    evictIdleChannel();
                }
                synchronized (this) {
                    if (ended) { channels.release(); return; }
                    channelHeld = true;
                }
                // Factory must itself bound/cancel connecting Bluetooth sockets.
                Link opened;
                // Pairing/authentication is shared by the BLE link. Never launch
                // concurrent secure handshakes when the VPN opens several flows.
                connecting.acquire();
                try {
                    if (closed || ended) throw new IOException("Relay closed");
                    opened = factory.open();
                } finally { connecting.release(); }
                synchronized (this) {
                    if (ended) { opened.close(); return; }
                    link = opened;
                }
                workers.execute(() -> pump(linkInput(), clientOutput(), received, "Bluetooth para Android"));
                pump(clientInput, opened.output(), sent, "Android para Bluetooth");
            } catch (Exception e) { diagnostics.accept("Sessao: " + e.getClass().getSimpleName() + " " + e.getMessage()); close(); }
        }
        InputStream linkInput() { try { return link.input(); } catch (IOException e) { close(); return new ByteArrayInputStream(new byte[0]); } }
        OutputStream clientOutput() { try { return client.getOutputStream(); } catch (IOException e) { close(); return new ByteArrayOutputStream(); } }
        void pump(InputStream in, OutputStream out, AtomicLong counter, String direction) {
            byte[] buffer = new byte[16384];
            try {
                int n;
                while (!closed && (n = in.read(buffer)) >= 0) {
                    if (n == 0) continue;
                    out.write(buffer, 0, n);
                    counter.addAndGet(n); lastActivity = System.nanoTime();
                }
            } catch (IOException error) { diagnostics.accept(direction + ": " + error.getClass().getSimpleName() + " " + error.getMessage()); }
            finally { close(); }
        }
        @Override public synchronized void close() {
            if (ended) return;
            ended = true;
            try { client.close(); } catch (IOException ignored) { }
            if (link != null) try { link.close(); } catch (IOException ignored) { }
            sessions.remove(this); slots.release();
            if (channelHeld) { channelHeld = false; channels.release(); }
        }
    }
    private void evictIdleChannel() {
        long cutoff = System.nanoTime() - TimeUnit.SECONDS.toNanos(15);
        Session oldest = null;
        for (Session session : sessions) {
            if (!session.ended && session.link != null && session.lastActivity < cutoff &&
                (oldest == null || session.lastActivity < oldest.lastActivity)) oldest = session;
        }
        if (oldest != null) { diagnostics.accept("Liberando canal ocioso para a fila"); oldest.close(); }
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try { server.close(); } catch (IOException ignored) { }
        for (Session session : sessions) session.close();
        workers.shutdownNow();
    }
}

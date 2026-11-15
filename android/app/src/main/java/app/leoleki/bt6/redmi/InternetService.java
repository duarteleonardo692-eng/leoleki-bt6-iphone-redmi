package app.leoleki.bt6.redmi;

import android.annotation.SuppressLint;
import android.app.*;
import android.bluetooth.*;
import android.content.Intent;
import android.net.VpnService;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.*;
import hev.htproxy.TProxyService;

/** Real TCP/UDP tunnel through encrypted BLE L2CAP. Hardware validation still required. */
public final class InternetService extends VpnService {
    public static final String START = "BT6_INTERNET_START", STOP = "BT6_INTERNET_STOP";
    public static volatile String status = "Internet Bluetooth desligada";
    private final Set<BluetoothSocket> sockets = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
    private volatile StreamBridge bridge;
    private ParcelFileDescriptor tun;
    private volatile boolean stopping;
    private boolean starting;
    private int generation;
    private Thread startup;

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent == null || STOP.equals(intent.getAction())) { status = "Internet Bluetooth desligada"; shutdown(); stopSelf(); return START_NOT_STICKY; }
        if (!START.equals(intent.getAction()) || starting || tun != null) return START_NOT_STICKY;
        final String address = intent.getStringExtra("device");
        final int psm = intent.getIntExtra("psm", 0);
        if (address == null || psm < 1 || psm > 255 || Build.VERSION.SDK_INT < 29) {
            status = "Conecte ao iPhone v0.8 primeiro (Android 10 ou superior)"; stopSelf(); return START_NOT_STICKY;
        }
        stopping = false; starting = true;
        final int attempt = ++generation;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("bt6_internet", "Internet Bluetooth", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 80, new Intent(this, InternetService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, "bt6_internet")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("BT6 Internet Bluetooth")
                .setContentText("Ponte experimental pelo Wi-Fi do iPhone")
                .setOngoing(true).addAction(android.R.drawable.ic_menu_close_clear_cancel,"Desligar",stop).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(808, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(808, notification);
        startup = new Thread(() -> {
            StreamBridge localBridge = null;
            try {
                BluetoothAdapter adapter = getSystemService(BluetoothManager.class).getAdapter();
                BluetoothDevice device = adapter.getRemoteDevice(address);
                localBridge = new StreamBridge(() -> openLink(device, psm));
                synchronized (this) {
                    if (stopping || generation != attempt) { localBridge.close(); return; }
                    bridge = localBridge;
                }
                status = "Verificando canal criptografado e Wi-Fi do iPhone...";
                preflight(localBridge.port());
                synchronized (this) {
                    if (stopping || generation != attempt) return;
                    // Exclude our loopback relay to prevent VPN recursion. Both families
                    // are routed so IPv6 cannot silently escape over mobile data.
                    tun = new Builder().setSession("BT6 via iPhone Wi-Fi").setMtu(1280)
                        .addAddress("10.88.0.2",32).addAddress("fd88::2",128)
                        .addRoute("0.0.0.0",0).addRoute("::",0)
                        .addDnsServer("198.18.0.2").addDisallowedApplication(getPackageName())
                        .setBlocking(true).establish();
                    if (tun == null) throw new IOException("Permissao VPN revogada");
                    File config = new File(getFilesDir(), "internet.yml");
                    String yaml = "tunnel:\n  mtu: 1280\n  ipv4: 10.88.0.2\n  ipv6: 'fd88::2'\n" +
                        "socks5:\n  address: 127.0.0.1\n  port: " + bridge.port() + "\n  udp: tcp\n" +
                        "mapdns:\n  address: 198.18.0.2\n  port: 53\n  network: 100.64.0.0\n  netmask: 255.192.0.0\n  cache-size: 2048\n" +
                        "misc:\n  max-session-count: 10\n  connect-timeout: 20000\n  tcp-read-write-timeout: 120000\n  udp-read-write-timeout: 60000\n  log-level: error\n";
                    try (FileOutputStream out = new FileOutputStream(config)) { out.write(yaml.getBytes(StandardCharsets.UTF_8)); }
                    if (!TProxyService.TProxyStartService(config.getAbsolutePath(), tun.getFd())) throw new IOException("Falha no motor VPN");
                    starting = false;
                    status = "VPN iniciada; valide a navegacao no Chrome. Mantenha o BT6 aberto no iPhone.";
                }
                timers.scheduleWithFixedDelay(() -> {
                    if (!stopping && !TProxyService.TProxyIsRunning()) {
                        status = "Motor VPN parou; reconecte"; shutdown(); stopSelf();
                    }
                }, 3, 3, TimeUnit.SECONDS);
            } catch (Exception | LinkageError e) {
                if (generation != attempt) return;
                status = "Nao foi possivel iniciar: " + e.getClass().getSimpleName() + " " + e.getMessage();
                shutdown(); stopSelf();
            }
        }, "BT6-start-internet");
        startup.start();
        return START_NOT_STICKY;
    }

    @SuppressLint("MissingPermission")
    private StreamBridge.Link openLink(BluetoothDevice device, int psm) throws IOException {
        if (stopping) throw new IOException("Desligado");
        BluetoothSocket socket = device.createL2capChannel(psm);
        sockets.add(socket);
        ScheduledFuture<?> deadline;
        try {
            deadline = timers.schedule(() -> {
                try { socket.close(); } catch (IOException ignored) { }
            }, 20, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            sockets.remove(socket); socket.close(); throw new IOException("Servico encerrado", e);
        }
        try {
            if (stopping) throw new IOException("Desligado");
            socket.connect(); deadline.cancel(false);
            return new StreamBridge.Link() {
                public InputStream input() throws IOException { return socket.getInputStream(); }
                public OutputStream output() throws IOException { return socket.getOutputStream(); }
                public void close() throws IOException { sockets.remove(socket); socket.close(); }
            };
        } catch (IOException e) {
            deadline.cancel(false); sockets.remove(socket); socket.close(); throw e;
        }
    }

    private void preflight(int port) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000); socket.setSoTimeout(23000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            out.write(new byte[]{5,1,0});
            if (in.readUnsignedByte()!=5 || in.readUnsignedByte()!=0) throw new IOException("Negociacao SOCKS falhou");
            byte[] host = "example.com".getBytes(StandardCharsets.US_ASCII);
            out.write(new byte[]{5,1,0,3,(byte)host.length});out.write(host);out.write(new byte[]{0,80});
            byte[] reply = new byte[10]; in.readFully(reply);
            if (reply[0]!=5 || reply[1]!=0) throw new IOException("iPhone sem Wi-Fi ou destino inacessivel");
            out.write("GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            byte[] http = new byte[5]; in.readFully(http);
            if (!"HTTP/".equals(new String(http,StandardCharsets.US_ASCII))) throw new IOException("Teste de internet nao retornou HTTP");
        }
    }
    private synchronized void shutdown() {
        stopping = true; starting = false; generation++;
        for (BluetoothSocket s : sockets) try { s.close(); } catch(IOException ignored) { }
        sockets.clear();
        if (bridge != null) { bridge.close(); bridge = null; }
        try { TProxyService.TProxyStopService(); } catch (LinkageError ignored) { }
        if (tun != null) { try { tun.close(); } catch(IOException ignored) { } tun = null; }
        stopForeground(STOP_FOREGROUND_REMOVE);
    }
    @Override public void onRevoke() { status="VPN revogada"; shutdown(); stopSelf(); }
    @Override public void onDestroy() { shutdown(); timers.shutdownNow(); super.onDestroy(); }
}

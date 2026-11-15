package app.leoleki.bt6.redmi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Arrays;
import android.os.SystemClock;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BT6 v0.6 VPN LAB — diagnostico de rotas e leitura TUN. This service captures ONLY the BT6 app's packets bound
 * for the RFC 2544 benchmarking range 198.18.0.0/15.
 *
 * No packet is forwarded to the iPhone. This is NOT a working Internet VPN.
 * Never route 0.0.0.0/0 or include other installed apps in this prototype.
 */
public final class VpnProbeService extends VpnService {
    public static final String ACTION_START = "app.leoleki.bt6.redmi.action.VPN_PROBE_START";
    public static final String ACTION_STOP = "app.leoleki.bt6.redmi.action.VPN_PROBE_STOP";
    public static final String DESTINATION = "198.18.0.1";
    private static final String CHANNEL_ID = "bt6_lab_capture";
    private static final int NOTIFICATION_ID = 5105;

    public static final AtomicInteger capturedPackets = new AtomicInteger();
    public static final AtomicInteger rawTunReads = new AtomicInteger();
    public static final AtomicInteger emptyTunReads = new AtomicInteger();
    public static final AtomicInteger ipv6Frames = new AtomicInteger();
    public static final AtomicInteger malformedFrames = new AtomicInteger();
    public static volatile boolean captureReaderStarted = false;
    public static volatile String readerState = "Leitor TUN nao iniciado";
    public static final AtomicInteger capturedTcpPackets = new AtomicInteger();
    public static final AtomicInteger capturedUdpPackets = new AtomicInteger();
    public static volatile boolean active = false;
    public static volatile String diagnostic = "VPN de teste nao iniciada";

    private volatile boolean running = false;
    private ParcelFileDescriptor tun;
    private Thread captureThread;
    private static volatile VpnProbeService currentInstance;
    private static volatile ProbeListener probeListener;
    private volatile PendingProbe pendingProbe;
    public static volatile String probeState = "Nenhum UDP LAB capturado";

    public interface ProbeListener {
        void onUdpProbeCaptured();
    }

    public static void setProbeListener(ProbeListener listener) {
        probeListener = listener;
    }

    private static final class PendingProbe {
        final byte[] clientAddress;
        final byte[] remoteAddress;
        final int clientPort;
        final long startedAtMs;

        PendingProbe(byte[] clientAddress, byte[] remoteAddress, int clientPort) {
            this.clientAddress = clientAddress;
            this.remoteAddress = remoteAddress;
            this.clientPort = clientPort;
            this.startedAtMs = SystemClock.elapsedRealtime();
        }
    }

    /** Called only for signed BLE responses from the iPhone v0.6. */
    public static boolean deliverProbeReply(String message) {
        VpnProbeService service = currentInstance;
        if (service == null || !service.running || !message.startsWith("BT6-UDP-RELAY")) {
            return false;
        }
        PendingProbe pending = service.pendingProbe;
        if (pending == null || SystemClock.elapsedRealtime() - pending.startedAtMs > 20000) {
            probeState = "Resposta sem pedido UDP LAB valido";
            return false;
        }
        service.pendingProbe = null;
        byte[] bytes = message.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        new Thread(() -> service.writeUdpProbeReply(pending, bytes), "BT6-LAB-REPLY").start();
        return true;
    }

    private static int u16(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }

    private void examineUdpProbe(byte[] data, int size, int ipHeaderLength) {
        if (size < ipHeaderLength + 8) return;
        if (u16(data, ipHeaderLength + 2) != 5353) return;
        if ((data[16] & 0xff) != 198 || (data[17] & 0xff) != 18 ||
                data[18] != 0 || data[19] != 1) return;
        int udpLength = u16(data, ipHeaderLength + 4);
        if (udpLength < 8 || ipHeaderLength + udpLength > size) return;
        byte[] signature = "BT6-LAB-UDP-TESTE".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int payloadOffset = ipHeaderLength + 8;
        if (udpLength - 8 != signature.length) return;
        for (int i = 0; i < signature.length; i++) {
            if (signature[i] != data[payloadOffset + i]) return;
        }
        if (pendingProbe != null &&
                SystemClock.elapsedRealtime() - pendingProbe.startedAtMs < 15000) return;
        pendingProbe = new PendingProbe(Arrays.copyOfRange(data, 12, 16),
                Arrays.copyOfRange(data, 16, 20), u16(data, ipHeaderLength));
        probeState = "UDP recebido pela VPN; solicitando HTTPS ao iPhone v0.6";
        ProbeListener listener = probeListener;
        if (listener != null) listener.onUdpProbeCaptured();
    }

    private static int checksumIpHeader(byte[] data, int headerLength) {
        int sum = 0;
        for (int i = 0; i < headerLength; i += 2) {
            sum += u16(data, i);
            sum = (sum & 0xffff) + (sum >>> 16);
        }
        return (~sum) & 0xffff;
    }

    private void writeUdpProbeReply(PendingProbe pending, byte[] raw) {
        if (!running || tun == null) return;
        byte[] payload = Arrays.copyOf(raw, Math.min(300, raw.length));
        byte[] data = new byte[20 + 8 + payload.length];
        data[0] = 0x45; // IPv4, IHL 5
        data[2] = (byte)(data.length >> 8);
        data[3] = (byte)data.length;
        data[8] = 64;
        data[9] = 17; // UDP
        System.arraycopy(pending.remoteAddress, 0, data, 12, 4);
        System.arraycopy(pending.clientAddress, 0, data, 16, 4);
        int checksum = checksumIpHeader(data, 20);
        data[10] = (byte)(checksum >> 8);
        data[11] = (byte)checksum;
        data[20] = (byte)(5353 >> 8);
        data[21] = (byte)5353;
        data[22] = (byte)(pending.clientPort >> 8);
        data[23] = (byte)pending.clientPort;
        int udpLength = 8 + payload.length;
        data[24] = (byte)(udpLength >> 8);
        data[25] = (byte)udpLength;
        // UDP checksum 0 is valid only for IPv4 lab packets.
        System.arraycopy(payload, 0, data, 28, payload.length);
        try {
            FileOutputStream output = new FileOutputStream(tun.getFileDescriptor());
            output.write(data);
            probeState = "Resposta UDP LAB injetada na VPN: " + payload.length + " bytes";
        } catch (Exception e) {
            probeState = "Falha ao injetar resposta UDP: " + e.getClass().getSimpleName();
        }
    }

    @Override public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }

    @Override public synchronized int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopCapture();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            return START_NOT_STICKY;
        }
        if (running) {
            diagnostic = "VPN LAB ja esta ativa";
            return START_NOT_STICKY;
        }
        try {
            // A VPN afeta SOMENTE ESTE APLICATIVO e somente a sub-rede
            // de benchmark. Outros apps, inclusive WhatsApp/Chrome,
            // NAO sao redirecionados.
            Builder builder = new Builder()
                .setSession("BT6 LAB v0.6 — somente teste")
                .setMtu(1500)
                .addAddress("10.77.0.2", 32)
                .addRoute("198.18.0.0", 15)
                .addAllowedApplication(getPackageName());
            tun = builder.establish();
            if (tun == null) {
                diagnostic = "Permissao VPN indisponivel ou revogada";
                stopSelf();
                return START_NOT_STICKY;
            }
            ensureChannel();
            Notification notification = createNotification();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            rawTunReads.set(0);
            emptyTunReads.set(0);
            ipv6Frames.set(0);
            malformedFrames.set(0);
            captureReaderStarted = false;
            readerState = "Leitor TUN iniciando";
            capturedPackets.set(0);
            capturedTcpPackets.set(0);
            capturedUdpPackets.set(0);
            running = true;
            active = true;
            currentInstance = this;
            pendingProbe = null;
            probeState = "Aguardando UDP LAB";
            
            diagnostic = "Ativa: captura local apenas para 198.18.0.0/15 do BT6";
            final ParcelFileDescriptor file = tun;
            captureThread = new Thread(() -> captureLoop(file), "BT6-VPN-LAB");
            captureThread.setDaemon(true);
            captureThread.start();
        } catch (Exception exception) {
            diagnostic = "Nao foi possivel abrir VPN LAB: " + exception.getClass().getSimpleName()
                         + " " + exception.getMessage();
            stopCapture();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private Notification createNotification() {
        Intent stop = new Intent(this, VpnProbeService.class).setAction(ACTION_STOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT |
            (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent stopPending = PendingIntent.getService(this, 105, stop, piFlags);
        return new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("BT6 VPN LAB — diagnostico")
            .setContentText("Somente o BT6, rede de testes. Internet geral NAO ativada.")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Desligar teste", stopPending)
            .build();
    }

    private void ensureChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
            "BT6 VPN LAB — diagnostico", NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private void captureLoop(ParcelFileDescriptor file) {
        byte[] buffer = new byte[16384];
        captureReaderStarted = true;
        readerState = "Leitor TUN ativo; aguardando pacotes";
        try (FileInputStream input = new FileInputStream(file.getFileDescriptor())) {
            while (running) {
                int n = input.read(buffer);
                if (n < 0) break;
                if (n == 0) {
                    // A read without bytes is NOT a network packet.
                    // Some TUN implementations can return zero repeatedly;
                    // throttling avoids unbounded CPU use and fake error counts.
                    emptyTunReads.incrementAndGet();
                    readerState = "Leitor TUN sem dados; aguardando";
                    try {
                        Thread.sleep(75);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                rawTunReads.incrementAndGet();
                int version = (buffer[0] >> 4) & 0x0f;
                if (version == 6) {
                    ipv6Frames.incrementAndGet();
                    continue;
                }
                if (version != 4 || n < 20) {
                    malformedFrames.incrementAndGet();
                    continue;
                }
                int ihl = (buffer[0] & 0x0f) * 4;
                if (ihl < 20 || ihl > n) {
                    malformedFrames.incrementAndGet();
                    continue;
                }
                int proto = buffer[9] & 0xff;
                capturedPackets.incrementAndGet();
                readerState = "Pacotes IPv4 validos recebidos";
                if (proto == 6) capturedTcpPackets.incrementAndGet();
                else if (proto == 17) {
                    capturedUdpPackets.incrementAndGet();
                    examineUdpProbe(buffer, n, ihl);
                }
                // Packet data discarded immediately. No PII, URL or payload logs.
            }
        } catch (IOException e) {
            if (running) {
                diagnostic = "Captura terminou: " + e.getClass().getSimpleName() + ": " + e.getMessage();
                readerState = "Erro leitura TUN: " + e.getClass().getSimpleName();
            }
        } finally {
            if (running) {
                running = false;
                active = false;
                diagnostic = "VPN LAB finalizada; reinicie se precisar";
                readerState = "Leitor TUN finalizado";
                stopSelf();
            }
        }
    }

    private synchronized void stopCapture() {
        running = false;
        active = false;
        pendingProbe = null;
        if (currentInstance == this) currentInstance = null;
        probeState = "VPN LAB encerrada";
        captureReaderStarted = false;
        readerState = "Leitor TUN desligado";
        if (tun != null) {
            try { tun.close(); } catch (IOException ignored) {}
            tun = null;
        }
        if (captureThread != null) {
            captureThread.interrupt();
            captureThread = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        diagnostic = "VPN LAB desligada — nenhuma rota de teste ativa";
    }

    @Override public void onRevoke() {
        stopCapture();
        stopSelf();
        super.onRevoke();
    }

    @Override public void onDestroy() {
        stopCapture();
        super.onDestroy();
    }
}

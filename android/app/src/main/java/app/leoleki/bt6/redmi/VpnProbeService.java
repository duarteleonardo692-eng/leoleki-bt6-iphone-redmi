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
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BT6 v0.5.1 VPN LAB — diagnostico de rotas e leitura TUN. This service captures ONLY the BT6 app's packets bound
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
                .setSession("BT6 LAB v0.5 — somente teste")
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
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            rawTunReads.set(0);
            malformedFrames.set(0);
            captureReaderStarted = false;
            readerState = "Leitor TUN iniciando";
            capturedPackets.set(0);
            capturedTcpPackets.set(0);
            capturedUdpPackets.set(0);
            running = true;
            active = true;
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
                rawTunReads.incrementAndGet();
                if (n < 20 || (buffer[0] >> 4 & 0x0f) != 4) {
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
                if (proto == 6) capturedTcpPackets.incrementAndGet();
                else if (proto == 17) capturedUdpPackets.incrementAndGet();
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

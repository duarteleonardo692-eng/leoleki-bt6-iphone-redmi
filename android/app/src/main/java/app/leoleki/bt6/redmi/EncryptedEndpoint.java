package app.leoleki.bt6.redmi;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.content.Context;
import android.os.SystemClock;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.IntConsumer;

/** Owns the encrypted control connection while the VPN outlives the activity. */
@SuppressLint("MissingPermission")
final class EncryptedEndpoint implements AutoCloseable {
    private static final UUID SERVICE = UUID.fromString("AD08C143-7581-4612-9D63-20D9540C4A81");
    private static final UUID ENDPOINT = UUID.fromString("AD08C143-7581-4612-9D63-20D9540C4A44");
    private final Context context;
    private final BluetoothDevice device;
    private final ScheduledExecutorService timers;
    private final IntConsumer changed;
    private BluetoothGatt gatt;
    private ScheduledFuture<?> polling;
    private boolean closed, connected, pending;
    private long pendingSince, lastGood;
    private int psm;

    EncryptedEndpoint(Context context, BluetoothDevice device, int initialPsm,
                      ScheduledExecutorService timers, IntConsumer changed) {
        this.context = context; this.device = device; this.psm = initialPsm;
        this.timers = timers; this.changed = changed;
    }
    synchronized void start() {
        if (!closed && polling == null)
            polling = timers.scheduleWithFixedDelay(this::tick, 0, 3, TimeUnit.SECONDS);
    }
    private synchronized void tick() {
        if (closed) return;
        try {
            long now = SystemClock.elapsedRealtime();
            if (gatt != null && pending && now - pendingSince > 15000) reset(gatt);
            if (gatt == null) {
                pending = true; pendingSince = now;
                gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
                if (gatt == null) pending = false;
            } else if (connected && !pending) readEndpoint();
        } catch (RuntimeException error) {
            android.util.Log.w("BT6Internet", "Controle Bluetooth: " + error.getClass().getSimpleName());
            if (gatt != null) reset(gatt);
        }
    }
    private void readEndpoint() {
        BluetoothGattService service = gatt.getService(SERVICE);
        BluetoothGattCharacteristic ch = service == null ? null : service.getCharacteristic(ENDPOINT);
        pendingSince = SystemClock.elapsedRealtime();
        pending = ch == null ? gatt.discoverServices() : gatt.readCharacteristic(ch);
    }
    private void reset(BluetoothGatt connection) {
        if (connection != gatt) return;
        gatt = null; connected = pending = false; lastGood = 0;
        try { connection.close(); } catch (RuntimeException ignored) { }
        notifyAll();
    }
    synchronized int awaitPsm() throws IOException {
        long deadline = SystemClock.elapsedRealtime() + 20000;
        while (!closed && (lastGood == 0 || SystemClock.elapsedRealtime() - lastGood > 12000)) {
            long left = deadline - SystemClock.elapsedRealtime();
            if (left <= 0) throw new IOException("iPhone nao confirmou o canal atual; mantenha o BT6 aberto");
            try { wait(left); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
        }
        if (closed) throw new IOException("Controle Bluetooth encerrado");
        return psm;
    }
    private synchronized void received(BluetoothGatt connection, BluetoothGattCharacteristic ch, byte[] value, int code) {
        if (closed || connection != gatt || !ENDPOINT.equals(ch.getUuid())) return;
        pending = false;
        if (code != BluetoothGatt.GATT_SUCCESS || value == null || value.length != 3 || value[0] != 10) {
            lastGood = 0;
            android.util.Log.w("BT6Internet", "Canal protegido ainda indisponivel; nova leitura automatica");
            return;
        }
        int next = (value[1] & 255) | ((value[2] & 255) << 8);
        if (next < 1 || next > 255) { lastGood = 0; return; }
        boolean refreshed = lastGood == 0;
        int previous = psm;
        psm = next; lastGood = SystemClock.elapsedRealtime();
        if (previous != next) {
            android.util.Log.i("BT6Internet", "Canal do iPhone atualizado automaticamente: " + previous + " -> " + next);
            changed.accept(next);
        } else if (refreshed) android.util.Log.i("BT6Internet", "Controle criptografado confirmou canal atual=" + next);
        notifyAll();
    }
    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt c, int code, int state) {
            synchronized (EncryptedEndpoint.this) {
                if (closed || c != gatt) return;
                if (code != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) { reset(c); return; }
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    connected = true; pendingSince = SystemClock.elapsedRealtime();
                    pending = c.discoverServices();
                    try {
                        c.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                        c.setPreferredPhy(BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK,
                            BluetoothDevice.PHY_OPTION_NO_PREFERRED);
                    } catch (RuntimeException optional) { /* Data remains encrypted if radio tuning is unavailable. */ }
                }
            }
        }
        @Override public void onServicesDiscovered(BluetoothGatt c, int code) {
            synchronized (EncryptedEndpoint.this) {
                if (closed || c != gatt) return;
                pending = false;
                if (code == BluetoothGatt.GATT_SUCCESS) readEndpoint();
            }
        }
        @Override public void onCharacteristicRead(BluetoothGatt c, BluetoothGattCharacteristic ch, int code) {
            received(c, ch, ch.getValue(), code);
        }
        @Override public void onCharacteristicRead(BluetoothGatt c, BluetoothGattCharacteristic ch, byte[] value, int code) {
            received(c, ch, value, code);
        }
        @Override public void onPhyUpdate(BluetoothGatt c, int tx, int rx, int code) {
            android.util.Log.i("BT6Internet", "Radio do servico PHY status=" + code + " tx=" + tx + " rx=" + rx);
        }
    };
    @Override public synchronized void close() {
        closed = true;
        if (polling != null) polling.cancel(false);
        if (gatt != null) reset(gatt);
        notifyAll();
    }
}

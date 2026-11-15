package app.leoleki.bt6.redmi;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends Activity {
    // UUIDs exclusivos da v0.3: evita acoplamento acidental com o iPhone v0.2.
    private static final UUID SERVICE = UUID.fromString("AD08C143-7581-4612-9D63-20D9540C4A21");
    private static final UUID REQUEST = UUID.fromString("AD08C143-7581-4612-9D63-20D9540C4A22");
    private static final UUID RESPONSE = UUID.fromString("AD08C143-7581-4612-9D63-20D9540C4A23");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");
    private static final int REQ_BT_PERMISSIONS = 901;
    private static final int MAX_BODY_BYTES = 8192;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ByteArrayOutputStream responseBuffer = new ByteArrayOutputStream();
    private final ArrayDeque<byte[]> txQueue = new ArrayDeque<>();
    private TextView statusView, logView, pageView;
    private EditText urlInput;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic requestChar, responseChar;
    private boolean scanning, notificationReady, writeActive;
    private long requestStartMs = 0L;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setPadding(28, 36, 28, 32);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);

        TextView title = new TextView(this);
        title.setText("BT6 — Redmi v0.3");
        title.setTextSize(24f);
        content.addView(title);
        addText(content, "Internet de texto pelo Bluetooth, sem Wi-Fi no Redmi. iPhone precisa do BT6 v0.4 e de internet.");
        statusView = addText(content, "Status: pronto para procurar iPhone v0.4");
        statusView.setTextSize(18f);
        addButton(content, "1. Buscar iPhone BT6 v0.4", this::ensurePermissionsAndScan);
        addButton(content, "2. Testar Bluetooth (PING)", () -> sendCommand((byte)0x50));
        addButton(content, "3. Testar internet (HTTP 200)", () -> sendCommand((byte)0x47));
        addButton(content, "4. Testar transporte TCP real (example.com:80)", () -> sendCommand((byte)0x54));
        addText(content, "Este teste pede que o iPhone abra uma conexao TCP nativa, " +
                "receba os bytes da resposta HTTP e os devolva por BLE com checksum. " +
                "Nao e a internet geral do Android; a porta 80 so e usada com dados publicos de teste.");

        addText(content, "5. Ler conteúdo real via Bluetooth:");
        urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setText("https://example.com/");
        urlInput.setTextSize(14f);
        content.addView(urlInput);
        addButton(content, "Ler endereço HTTPS", () -> sendUrl(urlInput.getText().toString()));
        addButton(content, "Exemplo: Wikipedia Bluetooth", () ->
                sendUrl("https://pt.wikipedia.org/wiki/Bluetooth"));
        addButton(content, "Exemplo: IANA", () ->
                sendUrl("https://www.iana.org/domains/reserved"));
        addText(content, "Nesta etapa, por segurança, apenas example.com, pt.wikipedia.org e www.iana.org. Ainda não fornece internet a Chrome/WhatsApp.");

        pageView = addText(content, "O texto da página aparecerá aqui após a transferência.");
        pageView.setTextSize(16f);
        logView = addText(content, "Registro de testes:\n");
        logView.setTextSize(13f);
        setContentView(scroll);
    }

    private TextView addText(LinearLayout container, String label) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setPadding(0, 10, 0, 10);
        container.addView(view);
        return view;
    }

    private void addButton(LinearLayout container, String label, Runnable action) {
        Button view = new Button(this);
        view.setText(label);
        view.setOnClickListener(v -> action.run());
        container.addView(view);
    }

    private void status(String text) {
        runOnUiThread(() -> {
            statusView.setText("Status: " + text);
            logView.append(text + "\n");
        });
    }

    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void ensurePermissionsAndScan() {
        if (!hasPermissions()) {
            if (Build.VERSION.SDK_INT >= 31) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT_PERMISSIONS);
            } else {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_BT_PERMISSIONS);
            }
            return;
        }
        startScan();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == REQ_BT_PERMISSIONS) {
            if (hasPermissions()) startScan();
            else status("Permissao Bluetooth negada");
        }
    }

    @SuppressLint("MissingPermission")
    private void startScan() {
        BluetoothManager btManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = btManager == null ? null : btManager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            status("Ative o Bluetooth do Redmi primeiro");
            return;
        }
        stopScan();
        if (gatt != null) {
            gatt.close();
            gatt = null;
        }
        notificationReady = false;
        writeActive = false;
        txQueue.clear();
        requestChar = null;
        responseChar = null;
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            status("Scanner BLE indisponivel");
            return;
        }
        ScanFilter filter = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE)).build();
        List<ScanFilter> filters = Arrays.asList(filter);
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanning = true;
        scanner.startScan(filters, settings, callback);
        status("Buscando iPhone BT6 v0.4. Mantenha o app v0.3 aberto no iPhone.");
        handler.postDelayed(() -> {
            if (scanning) {
                stopScan();
                status("Busca expirou. Deixe o BT6 v0.4 visivel no iPhone e tente novamente.");
            }
        }, 25000);
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        if (scanning && scanner != null) scanner.stopScan(callback);
        scanning = false;
    }

    private final ScanCallback callback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            if (!scanning || result.getDevice() == null) return;
            BluetoothDevice device = result.getDevice();
            stopScan();
            status("iPhone BT6 v0.4 encontrado. Conectando...");
            connect(device);
        }

        @Override public void onScanFailed(int errorCode) {
            scanning = false;
            status("Erro BLE ao buscar: " + errorCode);
        }
    };

    @SuppressLint("MissingPermission")
    private void connect(BluetoothDevice device) {
        gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt connection, int code, int newState) {
            if (code != BluetoothGatt.GATT_SUCCESS) {
                notificationReady = false;
                synchronized (txQueue) {
                    txQueue.clear();
                    writeActive = false;
                }
                status("Falha GATT: " + code);
                return;
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                status("Bluetooth conectado. Buscando servicos...");
                discover(connection);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                notificationReady = false;
                status("iPhone desconectado");
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt connection, int code) {
            if (code != BluetoothGatt.GATT_SUCCESS) {
                status("Erro ao ler servicos: " + code);
                return;
            }
            BluetoothGattService service = connection.getService(SERVICE);
            if (service == null) {
                status("Servico BT6 v0.4 nao encontrado");
                return;
            }
            requestChar = service.getCharacteristic(REQUEST);
            responseChar = service.getCharacteristic(RESPONSE);
            if (requestChar == null || responseChar == null) {
                status("Caracteristicas BLE ausentes");
                return;
            }
            enableNotifications(connection);
        }

        @Override public void onDescriptorWrite(BluetoothGatt connection, BluetoothGattDescriptor descriptor, int code) {
            if (CCCD.equals(descriptor.getUuid()) && code == BluetoothGatt.GATT_SUCCESS) {
                notificationReady = true;
                status("Conectado ao iPhone v0.4. Pronto para transferir.");
            } else {
                status("Falha nas notificacoes BLE: " + code);
            }
        }

        @Override public void onCharacteristicWrite(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, int code) {
            synchronized (txQueue) {
                if (!writeActive) return;
                if (code != BluetoothGatt.GATT_SUCCESS) {
                    txQueue.clear();
                    writeActive = false;
                    status("Falha ao transmitir pacote: " + code);
                    return;
                }
                txQueue.pollFirst();
                sendNextFrameLocked();
            }
        }

        @Override public void onCharacteristicChanged(BluetoothGatt connection, BluetoothGattCharacteristic ch) {
            parseNotification(ch.getValue());
        }

        @Override public void onCharacteristicChanged(BluetoothGatt connection, BluetoothGattCharacteristic ch, byte[] value) {
            parseNotification(value);
        }
    };

    @SuppressLint("MissingPermission")
    private void discover(BluetoothGatt connection) {
        connection.discoverServices();
    }

    @SuppressLint("MissingPermission")
    private void enableNotifications(BluetoothGatt connection) {
        if (!connection.setCharacteristicNotification(responseChar, true)) {
            status("Nao foi possivel ouvir respostas BLE");
            return;
        }
        BluetoothGattDescriptor descriptor = responseChar.getDescriptor(CCCD);
        if (descriptor == null) {
            status("Descritor CCCD ausente");
            return;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            int result = connection.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (result != BluetoothGatt.GATT_SUCCESS) status("Erro ao registrar notificacao: " + result);
        } else {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (!connection.writeDescriptor(descriptor)) status("Falha ao habilitar notificacao");
        }
    }

    private void sendCommand(byte command) {
        synchronized (txQueue) {
            if (!isReady() || writeActive) {
                status("Conecte o BT6 v0.4 ou aguarde a transferencia atual");
                return;
            }
            requestStartMs = android.os.SystemClock.elapsedRealtime();
            txQueue.addLast(new byte[]{command});
            writeActive = true;
            sendNextFrameLocked();
        }
    }

    private void sendUrl(String rawUrl) {
        try {
            URI url = new URI(rawUrl.trim());
            String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equalsIgnoreCase(url.getScheme())
                    || url.getUserInfo() != null
                    || (url.getPort() != -1 && url.getPort() != 443)
                    || !(host.equals("example.com") || host.equals("pt.wikipedia.org")
                        || host.equals("www.iana.org"))) {
                status("Site nao permitido. Use HTTPS de example.com, pt.wikipedia.org ou www.iana.org");
                return;
            }
            byte[] bytes = url.toASCIIString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length == 0 || bytes.length > 220) {
                status("Endereco muito longo (limite 220 bytes)");
                return;
            }
            synchronized (txQueue) {
                if (!isReady() || writeActive) {
                    status("Conecte o BT6 v0.4 ou aguarde a transferencia atual");
                    return;
                }
                requestStartMs = android.os.SystemClock.elapsedRealtime();
                txQueue.addLast(new byte[]{0x55}); // U = inicio
                for (int i = 0; i < bytes.length; i += 17) {
                    int end = Math.min(bytes.length, i + 17);
                    byte[] frame = new byte[1 + end - i];
                    frame[0] = 0x44; // D = dados
                    System.arraycopy(bytes, i, frame, 1, end - i);
                    txQueue.addLast(frame);
                }
                txQueue.addLast(new byte[]{0x45}); // E = fim
                writeActive = true;
                sendNextFrameLocked();
            }
            status("Solicitando pagina " + url.getHost() + " via iPhone");
        } catch (Exception e) {
            status("Endereco HTTPS invalido: " + e.getMessage());
        }
    }

    private boolean isReady() {
        return gatt != null && requestChar != null && notificationReady;
    }

    @SuppressLint("MissingPermission")
    private void sendNextFrameLocked() {
        if (txQueue.isEmpty()) {
            writeActive = false;
            return;
        }
        if (!isReady()) {
            txQueue.clear();
            writeActive = false;
            status("Bluetooth desconectado durante envio");
            return;
        }
        byte[] frame = txQueue.peekFirst();
        boolean accepted;
        if (Build.VERSION.SDK_INT >= 33) {
            accepted = gatt.writeCharacteristic(requestChar, frame,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS;
        } else {
            requestChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            requestChar.setValue(frame);
            accepted = gatt.writeCharacteristic(requestChar);
        }
        if (!accepted) {
            txQueue.clear();
            writeActive = false;
            status("Fila GATT ocupada. Tente novamente.");
        }
    }

    private static int fnv1a(byte[] bytes) {
        int hash = 0x811C9DC5;
        for (byte b : bytes) hash = (hash ^ (b & 0xff)) * 0x01000193;
        return hash;
    }

    private static int littleEndianInt(byte[] bytes, int start) {
        return (bytes[start] & 0xff) | ((bytes[start + 1] & 0xff) << 8)
                | ((bytes[start + 2] & 0xff) << 16) | ((bytes[start + 3] & 0xff) << 24);
    }

    private synchronized void parseNotification(byte[] packet) {
        if (packet == null || packet.length == 0) return;
        int frame = packet[0] & 0xFF;
        if (frame == 1) {
            responseBuffer.reset();
            status("Recebendo dados reais via Bluetooth...");
        } else if (frame == 2 && packet.length > 1) {
            if (responseBuffer.size() + packet.length - 1 <= MAX_BODY_BYTES) {
                responseBuffer.write(packet, 1, packet.length - 1);
            } else {
                status("Resposta excedeu limite de 8 KiB");
                responseBuffer.reset();
            }
        } else if (frame == 3) {
            byte[] data = responseBuffer.toByteArray();
            boolean valid = packet.length == 1; // compatibilidade com resposta antiga
            if (packet.length == 9) {
                int expectedHash = littleEndianInt(packet, 1);
                long expectedLength = littleEndianInt(packet, 5) & 0xffffffffL;
                valid = (expectedHash == fnv1a(data)) && (expectedLength == data.length);
            }
            if (!valid) {
                status("Falha de integridade BLE: dados incompletos. Repita o pedido.");
            } else {
                String text = new String(data, StandardCharsets.UTF_8);
                runOnUiThread(() -> pageView.setText(text));
                long elapsed = requestStartMs > 0L
                        ? android.os.SystemClock.elapsedRealtime() - requestStartMs : 0L;
                status("Recebidos " + data.length + " bytes reais; integridade OK"
                        + (elapsed > 0 ? "; " + elapsed + " ms" : ""));
            }
            responseBuffer.reset();
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        stopScan();
        if (gatt != null) {
            gatt.close();
            gatt = null;
        }
    }
}

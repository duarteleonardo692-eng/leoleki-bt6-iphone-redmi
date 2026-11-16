package app.leoleki.bt6.redmi;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.VpnService;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
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
    private static final UUID SERVICE = UUID.fromString("AD08C143-7581-4612-9D63-20D9540C4A81");
    private static final UUID REQUEST = UUID.fromString("AD08C143-7581-4612-9D63-20D9540C4A42");
    private static final UUID RESPONSE = UUID.fromString("AD08C143-7581-4612-9D63-20D9540C4A43");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");
    private static final UUID PSM = UUID.fromString("AD08C143-7581-4612-9D63-20D9540C4A44");
    private static final int REQ_INTERNET = 903;
    private BluetoothGattCharacteristic psmChar;
    private int internetPsm;
    private static final int REQ_BT_PERMISSIONS = 901;
    private static final int REQ_VPN_LAB = 902;
    private static final int MAX_BODY_BYTES = 20000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ByteArrayOutputStream responseBuffer = new ByteArrayOutputStream();
    private final ArrayDeque<byte[]> txQueue = new ArrayDeque<>();
    private TextView statusView, logView, pageView, browserStatus;
    private Button internetButton;
    private android.widget.CheckBox chromeOnly;
    private WebView browserView;
    private EditText browserInput;
    private String browserCurrentURL = "https://example.com/";
    private EditText urlInput;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic requestChar, responseChar;
    private boolean scanning, notificationReady, writeActive;
    private boolean setupActive;
    private long requestStartMs = 0L;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setPadding(28, 36, 28, 32);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);

        TextView title = new TextView(this);
        title.setText("BT6 v0.8.9 — Internet do iPhone");
        title.setTextSize(24f);
        content.addView(title);
        addText(content, "Conecte o iPhone ao Wi-Fi e mantenha o BT6 v0.8 aberto nele. Depois busque o iPhone e ative a internet. Primeira validacao nos aparelhos ainda pendente.");
        statusView = addText(content, "Status: pronto para procurar iPhone v0.8");
        statusView.setTextSize(18f);
        addButton(content, "1. Buscar iPhone BT6 v0.8", this::ensurePermissionsAndScan);
        addText(content, "INTERNET PARA OUTROS APPS — experimental: use iPhone v0.8 no Wi-Fi, com o BT6 aberto. A VPN substitui outra VPN em uso. Velocidade e estabilidade ainda dependem do teste fisico.");
        chromeOnly = new android.widget.CheckBox(this);
        chromeOnly.setText("Diagnostico: encaminhar somente o Chrome");
        chromeOnly.setChecked(true);
        content.addView(chromeOnly);
        internetButton = addButton(content, "ATIVAR INTERNET DO IPHONE", this::prepareInternet);
        internetButton.setEnabled(false);
        addButton(content, "VER ESTADO DA INTERNET", () -> status(InternetService.snapshot()));
        addButton(content, "DESLIGAR INTERNET", () -> startService(new Intent(this, InternetService.class).setAction(InternetService.STOP)));
        LinearLayout diagnostics = new LinearLayout(this);
        diagnostics.setOrientation(LinearLayout.VERTICAL);
        diagnostics.setVisibility(android.view.View.GONE);
        addButton(content, "Mostrar / ocultar testes avancados", () -> diagnostics.setVisibility(
            diagnostics.getVisibility() == android.view.View.VISIBLE ? android.view.View.GONE : android.view.View.VISIBLE));
        content.addView(diagnostics);
        addButton(diagnostics, "2. Testar Bluetooth (PING)", () -> sendCommand((byte)0x50));

        addText(diagnostics, "NAVEGADOR BT6 — sites HTTPS leves:");
        browserInput = new EditText(this);
        browserInput.setSingleLine(true);
        browserInput.setText(browserCurrentURL);
        browserInput.setTextSize(15f);
        diagnostics.addView(browserInput);
        addButton(diagnostics, "ABRIR SITE PELO BLUETOOTH", () -> requestBrowserPage(browserInput.getText().toString()));
        browserStatus = addText(diagnostics, "Navegador: conecte o iPhone v0.8 e abra um site HTTPS.");
        browserView = new WebView(this);
        browserView.getSettings().setJavaScriptEnabled(false);
        browserView.getSettings().setDomStorageEnabled(false);
        browserView.getSettings().setAllowFileAccess(false);
        browserView.getSettings().setAllowContentAccess(false);
        browserView.getSettings().setBlockNetworkLoads(true);
        browserView.getSettings().setLoadsImagesAutomatically(false);
        browserView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return true;
                requestBrowserPage(request.getUrl().toString());
                return true;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                requestBrowserPage(url);
                return true;
            }
        });
        int browserHeight = Math.round(360 * getResources().getDisplayMetrics().density);
        diagnostics.addView(browserView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, browserHeight));
        browserView.loadDataWithBaseURL("https://example.com/",
                "<h3>BT6 v0.8</h3><p>Os links dos sites serao abertos pelo iPhone.</p>",
                "text/html", "UTF-8", null);
        addText(diagnostics, "Este navegador leve e limitado a texto e links. Nao digite senhas ou dados pessoais; e um navegador experimental.");

        addButton(diagnostics, "3. Testar internet (HTTP 200)", () -> sendCommand((byte)0x47));
        addButton(diagnostics, "4. Testar transporte TCP real (example.com:80)", () -> sendCommand((byte)0x54));
        addText(diagnostics, "Este teste pede que o iPhone abra uma conexao TCP nativa, " +
                "receba os bytes da resposta HTTP e os devolva por BLE com checksum. " +
                "Nao e a internet geral do Android; a porta 80 so e usada com dados publicos de teste.");

        addText(diagnostics, "5. Ler conteúdo real via Bluetooth:");
        urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setText("https://example.com/");
        urlInput.setTextSize(14f);
        diagnostics.addView(urlInput);
        addButton(diagnostics, "Ler endereço HTTPS", () -> sendUrl(urlInput.getText().toString()));
        addButton(diagnostics, "Exemplo: Wikipedia Bluetooth", () ->
                sendUrl("https://pt.wikipedia.org/wiki/Bluetooth"));
        addButton(diagnostics, "Exemplo: IANA", () ->
                sendUrl("https://www.iana.org/domains/reserved"));
        addText(diagnostics, "Nesta etapa, por segurança, apenas example.com, pt.wikipedia.org e www.iana.org. Essa restricao e apenas do leitor de diagnostico.");

        addText(diagnostics, "6. VPN LAB (opcional e isolada): apenas este aplicativo e somente IPs 198.18.0.0/15. Nao encaminha pacotes para o iPhone.");
        addText(diagnostics, "ATENCAO: ativar uma VPN de teste pode interromper outra VPN existente no aparelho. Ela nao libera internet ao Chrome ou WhatsApp.");
        addButton(diagnostics, "Iniciar VPN LAB (somente teste)", this::confirmAndPrepareVpnLab);
        addButton(diagnostics, "Testar TCP + UDP e verificar captura", this::generateLabPacket);
        addButton(diagnostics, "TESTAR UDP DE IDA E VOLTA VIA IPHONE", this::testUdpRoundTrip);
        addText(diagnostics, "Novo teste: envia UDP pelo TUN Android, usa o iPhone para confirmar HTTPS e devolve uma resposta UDP ao proprio BT6. Apenas diagnostico.");
        addButton(diagnostics, "Diagnosticar rede Android", this::diagnoseAndroidNetwork);
        addButton(diagnostics, "Verificar pacotes VPN LAB", this::showVpnLabStatus);
        addButton(diagnostics, "DESLIGAR VPN LAB", this::stopVpnLab);
        pageView = addText(diagnostics, "O texto da página aparecerá aqui após a transferência.");
        pageView.setTextSize(16f);
        logView = addText(content, "Registro de testes:\n");
        logView.setTextSize(13f);
        setContentView(scroll);
        VpnProbeService.setProbeListener(() -> handler.post(() -> {
            status("VPN capturou pacote UDP real. Solicitando HTTPS do iPhone v0.8 pelo BLE...");
            sendCommand((byte)0x56);
        }));
    }

    private TextView addText(LinearLayout container, String label) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setPadding(0, 10, 0, 10);
        container.addView(view);
        return view;
    }

    private Button addButton(LinearLayout container, String label, Runnable action) {
        Button view = new Button(this);
        view.setText(label);
        view.setOnClickListener(v -> action.run());
        container.addView(view);
        return view;
    }

    private void status(String text) {
        runOnUiThread(() -> {
            statusView.setText("Status: " + text);
            if (logView != null) {
                if (logView.length() > 12000) logView.setText("Registro recente:\n");
                logView.append(text + "\n");
            }
        });
    }

    private void confirmAndPrepareVpnLab() {
        new AlertDialog.Builder(this)
            .setTitle("VPN LAB: autorizacao opcional")
            .setMessage("SOMENTE o BT6 e os IPs de teste 198.18.0.0/15 serao capturados. " +
                "Outros aplicativos nao serao redirecionados. Essa VPN de laboratorio nao " +
                "transmite internet; ela pode substituir outra VPN em uso. Deseja continuar?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Autorizar teste", (dialog, which) -> {
                try {
                    Intent permission = VpnService.prepare(this);
                    if (permission != null) {
                        startActivityForResult(permission, REQ_VPN_LAB);
                    } else {
                        startVpnLab();
                    }
                } catch (Exception e) {
                    status("Falha ao solicitar VPN: " + e.getMessage());
                }
            }).show();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_INTERNET && resultCode == RESULT_OK) startInternet();
        if (requestCode == REQ_VPN_LAB) {
            if (resultCode == RESULT_OK) startVpnLab();
            else status("VPN LAB cancelada. Nada foi ativado.");
        }
    }

    private void startVpnLab() {
        Intent intent = new Intent(this, VpnProbeService.class)
                .setAction(VpnProbeService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
            status("Solicitado inicio de VPN LAB. Toque em Verificar pacotes para ver o estado.");
        } catch (Exception e) {
            status("Nao foi possivel iniciar VPN LAB: " + e.getMessage());
        }
    }

    private void showVpnLabStatus() {
        status(VpnProbeService.diagnostic + " | VPN ativa: " + VpnProbeService.active
                + " | Leitor: " + VpnProbeService.readerState
                + " | Leituras TUN com dados: " + VpnProbeService.rawTunReads.get()
                + " | Leituras vazias: " + VpnProbeService.emptyTunReads.get()
                + " | IPv6: " + VpnProbeService.ipv6Frames.get()
                + " | IPv4: " + VpnProbeService.capturedPackets.get()
                + " | TCP: " + VpnProbeService.capturedTcpPackets.get()
                + " | UDP: " + VpnProbeService.capturedUdpPackets.get()
                + " | Quadros invalidos: " + VpnProbeService.malformedFrames.get()
                + " | Resposta LAB: " + VpnProbeService.probeState);
    }

    private void diagnoseAndroidNetwork() {
        try {
            ConnectivityManager connectivity =
                    (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (connectivity == null) {
                status("Diagnostico: ConnectivityManager indisponivel");
                return;
            }
            Network active = connectivity.getActiveNetwork();
            if (active == null) {
                status("Diagnostico: sem rede padrao ativa no Android. " +
                        "Essa pode ser a razao dos sockets nao enviarem pacotes mesmo com VPN visivel.");
                return;
            }
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(active);
            if (caps == null) {
                status("Diagnostico: rede padrao sem capacidades conhecidas");
                return;
            }
            status("Rede Android: VPN=" +
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) +
                    ", dados moveis=" +
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) +
                    ", Wi-Fi=" +
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) +
                    ", INTERNET=" +
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) +
                    ", VALIDADA=" +
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) +
                    ". Sem nomes, IPs pessoais ou senhas.");
        } catch (Exception e) {
            status("Diagnostico de rede falhou: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void checkInternetSocketPermission() {
        boolean allowed = checkSelfPermission(Manifest.permission.INTERNET)
                == PackageManager.PERMISSION_GRANTED;
        status("Permissao INTERNET (abrir sockets): " + (allowed ? "OK" : "AUSENTE") +
                ". Isso nao significa que o Android tenha acesso a rede externa.");
    }

    private void testUdpRoundTrip() {
        if (!VpnProbeService.active) {
            status("Primeiro autorize e inicie a VPN LAB desta versao.");
            return;
        }
        if (!isReady()) {
            status("Primeiro conecte o iPhone BT6 v0.8 pelo Bluetooth.");
            return;
        }
        status("Iniciando teste UDP da VPN Android até o iPhone e de volta...");
        new Thread(() -> {
            try (java.net.DatagramSocket socket = new java.net.DatagramSocket()) {
                socket.setSoTimeout(17000);
                java.net.InetAddress host = java.net.InetAddress.getByName(VpnProbeService.DESTINATION);
                socket.connect(host, 5353);
                byte[] request = "BT6-LAB-UDP-TESTE".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                java.net.DatagramPacket outbound =
                        new java.net.DatagramPacket(request, request.length, host, 5353);
                long started = android.os.SystemClock.elapsedRealtime();
                socket.send(outbound);
                byte[] answer = new byte[512];
                java.net.DatagramPacket incoming =
                        new java.net.DatagramPacket(answer, answer.length);
                socket.receive(incoming);
                long elapsed = android.os.SystemClock.elapsedRealtime() - started;
                String reply = new String(incoming.getData(), incoming.getOffset(),
                        incoming.getLength(), java.nio.charset.StandardCharsets.UTF_8);
                status("UDP LAB ida e volta CONFIRMADA em " + elapsed + " ms | " + reply);
            } catch (Exception exception) {
                status("Teste UDP LAB nao concluiu: " +
                        exception.getClass().getSimpleName() + ": " + exception.getMessage()
                        + " | " + VpnProbeService.probeState);
            }
            showVpnLabStatus();
        }, "BT6-UDP-ROUNDTRIP").start();
    }

    private void generateLabPacket() {
        if (!VpnProbeService.active) {
            status("Ative a VPN LAB explicitamente antes deste teste.");
            return;
        }
        diagnoseAndroidNetwork();
        checkInternetSocketPermission();
        status("Teste TCP e UDP para IP reservado 198.18.0.1. " +
                "Sem servidor de destino: nao espere uma resposta de internet.");
        new Thread(() -> {
            String tcpResult;
            String udpResult;
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(VpnProbeService.DESTINATION, 80), 1500);
                tcpResult = "conectou (inesperado no endereco de laboratorio)";
            } catch (Exception e) {
                tcpResult = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            try (java.net.DatagramSocket socket = new java.net.DatagramSocket()) {
                byte[] payload = "BT6-LAB-UDP-BASICO".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                java.net.DatagramPacket packet = new java.net.DatagramPacket(payload, payload.length,
                        java.net.InetAddress.getByName(VpnProbeService.DESTINATION), 5353);
                socket.send(packet);
                udpResult = "send() executado (nao confirma roteamento)";
            } catch (Exception e) {
                udpResult = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            try { Thread.sleep(350); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            final String tcp = tcpResult, udp = udpResult;
            runOnUiThread(() -> {
                status("Resultado SOCKET TCP: " + tcp + " | UDP: " + udp);
                showVpnLabStatus();
            });
        }, "BT6-LAB-TESTE-DUPLO").start();
    }

    private void stopVpnLab() {
        Intent stop = new Intent(this, VpnProbeService.class)
                .setAction(VpnProbeService.ACTION_STOP);
        try {
            startService(stop);
            status("Comando DESLIGAR VPN LAB enviado.");
        } catch (Exception e) {
            status("Nao foi possivel parar VPN LAB: " + e.getMessage());
        }
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
        if (setupActive) { status("Aguarde a busca/conexao atual terminar."); return; }
        BluetoothManager btManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = btManager == null ? null : btManager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            status("Ative o Bluetooth do Redmi primeiro");
            return;
        }
        stopScan();
        stopService(new Intent(this, InternetService.class));
        if (gatt != null) {
            try { gatt.close(); } catch (SecurityException ignored) { }
            gatt = null;
        }
        notificationReady = false;
        internetPsm = 0;
        internetButton.setEnabled(false);
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
        setupActive = true;
        scanning = true;
        scanner.startScan(filters, settings, callback);
        status("Buscando iPhone BT6 v0.8. Mantenha o app v0.8 aberto no iPhone.");
        handler.postDelayed(() -> {
            if (scanning) {
                stopScan();
                setupActive = false;
                status("Busca expirou. Deixe o BT6 v0.8 visivel no iPhone e tente novamente.");
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
            status("iPhone BT6 v0.8 encontrado. Conectando...");
            connect(device);
        }

        @Override public void onScanFailed(int errorCode) {
            scanning = false;
            setupActive = false;
            status("Erro BLE ao buscar: " + errorCode);
        }
    };

    @SuppressLint("MissingPermission")
    private void connect(BluetoothDevice device) {
        gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt connection, int code, int newState) {
            if (connection != gatt) return;
            if (code != BluetoothGatt.GATT_SUCCESS) {
                setupActive = false;
                internetPsm = 0;
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
                setupActive = false;
                internetPsm = 0;
                notificationReady = false;
                status("iPhone desconectado");
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt connection, int code) {
            if (connection != gatt) return;
            if (code != BluetoothGatt.GATT_SUCCESS) {
                setupActive = false;
                status("Erro ao ler servicos: " + code);
                return;
            }
            BluetoothGattService service = connection.getService(SERVICE);
            if (service == null) {
                setupActive = false;
                status("Servico BT6 v0.4 nao encontrado");
                return;
            }
            psmChar = service.getCharacteristic(PSM);
            requestChar = service.getCharacteristic(REQUEST);
            responseChar = service.getCharacteristic(RESPONSE);
            if (requestChar == null || responseChar == null) {
                setupActive = false;
                status("Caracteristicas BLE ausentes");
                return;
            }
            enableNotifications(connection);
        }

        @Override public void onDescriptorWrite(BluetoothGatt connection, BluetoothGattDescriptor descriptor, int code) {
            if (connection != gatt) return;
            if (CCCD.equals(descriptor.getUuid()) && code == BluetoothGatt.GATT_SUCCESS) {
                setupActive = true;
                notificationReady = true;
                status("Bluetooth conectado. Aguarde a autorizacao do pareamento nos dois aparelhos.");
                readInternetEndpoint(connection);
            } else {
                setupActive = false;
                status("Falha nas notificacoes BLE: " + code);
            }
        }

        @Override public void onCharacteristicRead(BluetoothGatt c, BluetoothGattCharacteristic ch, int result) {
            if (c == gatt) readPsm(ch, ch.getValue(), result);
        }
        @Override public void onCharacteristicRead(BluetoothGatt c, BluetoothGattCharacteristic ch, byte[] value, int result) {
            if (c == gatt) readPsm(ch, value, result);
        }
        @Override public void onCharacteristicWrite(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, int code) {
            if (connection != gatt) return;
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
            if (connection != gatt) return;
            parseNotification(ch.getValue());
        }

        @Override public void onCharacteristicChanged(BluetoothGatt connection, BluetoothGattCharacteristic ch, byte[] value) {
            if (connection != gatt) return;
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

    private void readInternetEndpoint(BluetoothGatt connection) {
        if (psmChar == null || !hasPermissions()) return;
        try {
            if (!connection.readCharacteristic(psmChar)) status("Falha ao ler canal de internet. Busque novamente.");
        } catch (SecurityException e) { status("Autorize o Bluetooth novamente."); }
    }
    private void readPsm(BluetoothGattCharacteristic ch, byte[] value, int result) {
        if (!PSM.equals(ch.getUuid())) return;
        setupActive = false;
        android.util.Log.i("BT6Internet", "Endpoint GATT lido; status=" + result + " bytes=" + (value == null ? 0 : value.length));
        if (result == BluetoothGatt.GATT_SUCCESS && value != null && value.length == 3 && value[0] == 10) {
            internetPsm = (value[1] & 255) | ((value[2] & 255) << 8);
            runOnUiThread(() -> internetButton.setEnabled(true));
            status("iPhone pronto; vinculo criptografado confirmado. Toque em ATIVAR INTERNET.");
        } else {
            internetPsm = 0;
            runOnUiThread(() -> internetButton.setEnabled(false));
            status("Nao foi possivel autenticar o canal. Confirme o pareamento nos dois aparelhos e busque novamente.");
        }
    }
    private void prepareInternet() {
        if (!isReady() || internetPsm == 0 || Build.VERSION.SDK_INT < 29) {
            status("Conecte ao BT6 iPhone v0.8 primeiro (Android 10 ou superior)."); return;
        }
        new AlertDialog.Builder(this).setTitle("Internet pelo iPhone")
            .setMessage((chromeOnly.isChecked() ? "Neste teste, somente o Chrome usara a ponte. " : "A VPN vai enviar o trafego dos outros apps pela ponte. ") + "Mantenha o BT6 aberto no iPhone no Wi-Fi. Isso substitui outra VPN ativa. Iniciar teste?")
            .setNegativeButton("Cancelar", null).setPositiveButton("Iniciar", (d,w) -> {
                Intent permission = VpnService.prepare(this);
                if (permission == null) startInternet(); else startActivityForResult(permission, REQ_INTERNET);
            }).show();
    }
    private void startInternet() {
        if (gatt == null || internetPsm == 0) { status("Conecte novamente ao iPhone"); return; }
        Intent intent = new Intent(this, InternetService.class).setAction(InternetService.START)
            .putExtra("device", gatt.getDevice().getAddress()).putExtra("psm", internetPsm).putExtra("chromeOnly", chromeOnly.isChecked());
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
        status("Verificando internet do iPhone. Use VER ESTADO DA INTERNET para acompanhar.");
    }

    private boolean permittedBrowserHost(String raw) {
        try {
            URI url = new URI(raw.trim());
            String host = url.getHost();
            if (host == null || !url.getScheme().equalsIgnoreCase("https") ||
                    url.getUserInfo() != null ||
                    (url.getPort() != -1 && url.getPort() != 443)) return false;
            host = host.toLowerCase(Locale.ROOT);
            if (!host.contains(".") || host.equals("localhost")
                    || host.endsWith(".local") || host.endsWith(".localhost")
                    || host.endsWith(".internal") || host.endsWith(".onion")
                    || host.endsWith(".test") || host.endsWith(".invalid")
                    || host.contains(":") || host.matches("[0-9.]+")) return false;
            return raw.trim().getBytes(StandardCharsets.UTF_8).length <= 220;
        } catch (Exception error) {
            return false;
        }
    }

    private void requestBrowserPage(String rawUrl) {
        String value = rawUrl == null ? "" : rawUrl.trim();
        if (!value.contains("://")) value = "https://" + value;
        if (!permittedBrowserHost(value)) {
            browserStatus.setText("Use um endereco HTTPS publico com menos de 220 bytes. Nao sao aceitos IPs locais.");
            return;
        }
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        synchronized (txQueue) {
            if (!isReady() || writeActive) {
                browserStatus.setText("Conecte ao iPhone v0.8 ou aguarde o pedido anterior.");
                return;
            }
            txQueue.addLast(new byte[]{(byte) 0x5B});
            for (int i = 0; i < data.length; i += 17) {
                int n = Math.min(17, data.length - i);
                byte[] packet = new byte[n + 1];
                packet[0] = (byte) 0x5C;
                System.arraycopy(data, i, packet, 1, n);
                txQueue.addLast(packet);
            }
            txQueue.addLast(new byte[]{(byte) 0x5D});
            writeActive = true;
            sendNextFrameLocked();
        }
        browserStatus.setText("Lendo " + value + " por Bluetooth...");
        requestStartMs = android.os.SystemClock.elapsedRealtime();
    }

    private void showBrowserResponse(String text) {
        int first = text.indexOf('\n');
        int second = first < 0 ? -1 : text.indexOf('\n', first + 1);
        if (second < 0) {
            browserStatus.setText("Resposta de navegador incompleta.");
            return;
        }
        String url = text.substring(first + 1, second);
        String html = text.substring(second + 1);
        if (!permittedBrowserHost(url)) {
            browserStatus.setText("Site de retorno bloqueado.");
            return;
        }
        browserCurrentURL = url;
        browserInput.setText(url);
        browserView.loadDataWithBaseURL(url, html, "text/html", "UTF-8", null);
        browserStatus.setText("Pagina recebida pelo Bluetooth: " +
                html.getBytes(StandardCharsets.UTF_8).length + " bytes (HTML simplificado).");
        status("NAVEGADOR BLE: HTML recebido e exibido. Clique nos links para navegar.");
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
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == android.bluetooth.BluetoothStatusCodes.SUCCESS;
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
                if (text.startsWith("BT6-BROWSER-OK\n")) {
                    runOnUiThread(() -> showBrowserResponse(text));
                } else if (text.startsWith("BT6-BROWSER-ERROR\n")) {
                    runOnUiThread(() -> browserStatus.setText(text.substring("BT6-BROWSER-ERROR\n".length())));
                }
                if (text.startsWith("BT6-UDP-RELAY")) {
                    boolean accepted = VpnProbeService.deliverProbeReply(text);
                    status(accepted
                            ? "Resposta confirmada no iPhone; injetando UDP de volta no socket Android..."
                            : "Resposta BLE recebida sem pedido UDP LAB pendente");
                }
                long elapsed = requestStartMs > 0L
                        ? android.os.SystemClock.elapsedRealtime() - requestStartMs : 0L;
                status("Recebidos " + data.length + " bytes reais; integridade OK"
                        + (elapsed > 0 ? "; " + elapsed + " ms" : ""));
            }
            responseBuffer.reset();
        }
    }

    @Override protected void onDestroy() {
        if (browserView != null) browserView.destroy();
        VpnProbeService.setProbeListener(null);
        super.onDestroy();
        stopScan();
        if (gatt != null) {
            try { gatt.close(); } catch (SecurityException ignored) { }
            gatt = null;
        }
    }
}

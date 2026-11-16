package app.leoleki.bt6.redmi;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class StreamBridgeTest {
    static StreamBridge.Link link(Socket socket) {
        return new StreamBridge.Link() {
            public InputStream input() throws IOException { return socket.getInputStream(); }
            public OutputStream output() throws IOException { return socket.getOutputStream(); }
            public void close() throws IOException { socket.close(); }
        };
    }
    static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); }
    public static void main(String[] args) throws Exception {
        ExecutorService peers = Executors.newCachedThreadPool();
        try (ServerSocket echo = new ServerSocket(0,50,InetAddress.getByName("127.0.0.1"))) {
            peers.submit(() -> {
                while (!echo.isClosed()) try {
                    Socket socket = echo.accept();
                    peers.submit(() -> { try (socket) {
                        byte[] buf = new byte[937]; int n;
                        while ((n=socket.getInputStream().read(buf))!=-1) socket.getOutputStream().write(buf,0,n);
                    } catch(IOException ignored) {} });
                } catch(IOException e) { break; }
            });
            AtomicInteger opening = new AtomicInteger(), peak = new AtomicInteger();
            StreamBridge relay = new StreamBridge(() -> {
                int n = opening.incrementAndGet(); peak.accumulateAndGet(n, Math::max);
                try {
                    try { Thread.sleep(15); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                    return link(new Socket("127.0.0.1",echo.getLocalPort()));
                } finally { opening.decrementAndGet(); }
            });
            try (relay) {
            List<Future<?>> jobs = new ArrayList<>();
            for (int k=0;k<8;k++) {
                final int seed=k;
                jobs.add(peers.submit(() -> {
                    try (Socket client=new Socket("127.0.0.1",relay.port())) {
                        client.setSoTimeout(10000);
                        byte[] expected=new byte[1_048_576];new Random(seed).nextBytes(expected);
                        Future<?> writer=peers.submit(() -> { try { client.getOutputStream().write(expected); } catch(IOException e) { throw new RuntimeException(e); } });
                        byte[] actual=client.getInputStream().readNBytes(expected.length);
                        writer.get(10,TimeUnit.SECONDS);
                        check(Arrays.equals(expected,actual),"1MiB integrity concurrent "+seed);
                    } catch(Exception e) { throw new RuntimeException(e); }
                }));
            }
            for (Future<?> job:jobs) job.get(20,TimeUnit.SECONDS);
            check(peak.get()==1,"secure handshakes must be serialized");
            check(relay.sent.get()==8L*1048576,"upload count");
            check(relay.received.get()==8L*1048576,"download count");
            List<Socket> held=new ArrayList<>();
            try {
                for (int i=0;i<StreamBridge.MAX_CHANNELS;i++) {
                    Socket active=new Socket("127.0.0.1",relay.port());held.add(active);active.setSoTimeout(3000);
                    active.getOutputStream().write(41);check(active.getInputStream().read()==41,"occupy channel");
                }
                Socket queued=new Socket("127.0.0.1",relay.port());held.add(queued);queued.setSoTimeout(250);
                queued.getOutputStream().write(42);
                try { queued.getInputStream().read();throw new AssertionError("extra channel must queue"); }
                catch(SocketTimeoutException expected) { }
                held.get(0).close();queued.setSoTimeout(4000);
                check(queued.getInputStream().read()==42,"queued channel resumes when capacity released");
            } finally { for(Socket socket:held) socket.close(); }
            Socket blocked=new Socket("127.0.0.1",relay.port());blocked.setSoTimeout(3000);
            Thread.sleep(100);relay.close();relay.close();
            check(blocked.getInputStream().read()==-1,"stop unblocks client");blocked.close();
            try {new Socket("127.0.0.1",relay.port());throw new AssertionError("closed listener");}catch(IOException expected){}
            }
        } finally { peers.shutdownNow(); }
        try (StreamBridge broken=new StreamBridge(() -> {throw new IOException("offline");}); Socket c=new Socket("127.0.0.1",broken.port())) {
            c.setSoTimeout(3000);c.getOutputStream().write(1);check(c.getInputStream().read()==-1,"failed link releases client");
        }
        System.out.println("PASS: bounded eight-channel admission and queued resume; serialized secure channel opens; eight concurrent 1MiB random full duplex round trips, byte counters, close, double close, failed link");
    }
}

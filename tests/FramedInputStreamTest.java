package app.leoleki.bt6.redmi;
import java.io.*;
import java.util.*;

public class FramedInputStreamTest {
    static void check(boolean b) { if (!b) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        byte[] payload=new byte[1048576];new Random(17).nextBytes(payload);
        ByteArrayOutputStream wire=new ByteArrayOutputStream();
        DataOutputStream writer=new DataOutputStream(wire);
        for (int pos=0;pos<payload.length;pos+=16384) {
            int n=Math.min(16384,payload.length-pos);
            writer.writeByte(0xD1);writer.writeShort(n);writer.write(payload,pos,n);
        }
        writer.write(new byte[]{(byte)0xD2,0,0});writer.write(99);
        InputStream fragmented=new ByteArrayInputStream(wire.toByteArray()) {
            @Override public synchronized int read(byte[] b,int o,int n) {return super.read(b,o,Math.min(n,7));}
        };
        FramedInputStream input=new FramedInputStream(fragmented);
        check(Arrays.equals(payload,input.readAllBytes()));check(input.read()==-1);
        check(fragmented.read()==99); // Logical EOF did not consume/close the physical stream.
        for (byte[] bad:new byte[][]{{1,0,1,0},{(byte)0xD1,0,0},{(byte)0xD1,0x40,1},{(byte)0xD1,0,2,1},{(byte)0xD2,0,1,0}}) {
            try {new FramedInputStream(new ByteArrayInputStream(bad)).read();throw new AssertionError("bad frame accepted");}
            catch(IOException expected) { }
        }
        System.out.println("PASS: framed 1MiB integrity with fragmented reads, ordered EOF preserving physical stream, invalid/truncated/oversized frames");
    }
}

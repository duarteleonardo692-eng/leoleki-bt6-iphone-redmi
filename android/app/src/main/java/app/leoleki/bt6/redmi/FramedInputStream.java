package app.leoleki.bt6.redmi;

import java.io.*;

/** Ordered response chunks and EOF, carried inside the already encrypted BLE stream. */
public final class FramedInputStream extends InputStream {
    private final DataInputStream source;
    private byte[] frame = new byte[0];
    private int position;
    private boolean ended;
    public FramedInputStream(InputStream source) { this.source = new DataInputStream(source); }
    @Override public int read() throws IOException {
        byte[] one = new byte[1];
        return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
    }
    @Override public int read(byte[] out, int offset, int count) throws IOException {
        if (offset < 0 || count < 0 || offset > out.length - count) throw new IndexOutOfBoundsException();
        if (count == 0) return 0;
        if (ended) return -1;
        if (position == frame.length) {
            int type = source.readUnsignedByte();
            int length = source.readUnsignedShort();
            if (type == 0xD2 && length == 0) { ended = true; return -1; }
            if (type != 0xD1 || length == 0 || length > 16384) throw new IOException("Quadro Bluetooth invalido");
            frame = new byte[length]; position = 0;
            source.readFully(frame);
        }
        int n = Math.min(count, frame.length - position);
        System.arraycopy(frame, position, out, offset, n); position += n;
        return n;
    }
    @Override public int available() { return frame.length - position; }
    @Override public void close() throws IOException { ended = true; source.close(); }
}

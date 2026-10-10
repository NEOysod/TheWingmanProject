package wingman.net;

import java.io.IOException;

/**
 * Packs values into bytes. Hand-written because Starsector's mod sandbox refuses to load
 * java.io.DataOutputStream ("File access and reflection are not allowed to scripts").
 */
public final class Out {
    private byte[] buf = new byte[256];
    private int n;

    private void need(int more) {
        if (n + more <= buf.length) return;
        byte[] b = new byte[Math.max(buf.length * 2, n + more)];
        System.arraycopy(buf, 0, b, 0, n);
        buf = b;
    }

    public void writeByte(int v) throws IOException { need(1); buf[n++] = (byte) v; }
    public void writeBoolean(boolean v) throws IOException { writeByte(v ? 1 : 0); }
    public void writeShort(int v) throws IOException { need(2); buf[n++] = (byte) (v >>> 8); buf[n++] = (byte) v; }
    public void writeInt(int v) throws IOException {
        need(4);
        buf[n++] = (byte) (v >>> 24); buf[n++] = (byte) (v >>> 16); buf[n++] = (byte) (v >>> 8); buf[n++] = (byte) v;
    }
    public void writeLong(long v) throws IOException { writeInt((int) (v >>> 32)); writeInt((int) v); }
    /** A whole number in as few bytes as it needs: 1 byte below 128, 2 below 16384, and so on. */
    public void writeVarLong(long v) throws IOException {
        while ((v & ~0x7FL) != 0) { writeByte((int) ((v & 0x7F) | 0x80)); v >>>= 7; }
        writeByte((int) v);
    }
    public void writeVarInt(int v) throws IOException { writeVarLong(v & 0xFFFFFFFFL); }
    public void writeFloat(float v) throws IOException { writeInt(Float.floatToIntBits(v)); }
    public void write(byte[] b) throws IOException { need(b.length); System.arraycopy(b, 0, buf, n, b.length); n += b.length; }
    public void write(byte[] b, int off, int len) throws IOException { need(len); System.arraycopy(b, off, buf, n, len); n += len; }

    /** Text as a length followed by two bytes per character. */
    public void writeUTF(String s) throws IOException {
        if (s == null) s = "";
        if (s.length() > 65535) s = s.substring(0, 65535);
        writeShort(s.length());
        need(s.length() * 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            buf[n++] = (byte) (c >>> 8);
            buf[n++] = (byte) c;
        }
    }

    public void flush() {}

    public byte[] toByteArray() {
        byte[] b = new byte[n];
        System.arraycopy(buf, 0, b, 0, n);
        return b;
    }
}

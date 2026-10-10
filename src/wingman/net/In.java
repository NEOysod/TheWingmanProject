package wingman.net;

import java.io.IOException;

/** Unpacks values written by Out. A message that ends early throws instead of reading garbage. */
public final class In {
    private final byte[] buf;
    private int p;

    public In(byte[] data) { this.buf = data; }

    private void need(int more) throws IOException {
        if (p + more > buf.length) throw new IOException("message cut short");
    }

    public byte readByte() throws IOException { need(1); return buf[p++]; }
    public boolean readBoolean() throws IOException { return readByte() != 0; }
    public short readShort() throws IOException { need(2); return (short) (((buf[p++] & 0xFF) << 8) | (buf[p++] & 0xFF)); }
    public int readInt() throws IOException {
        need(4);
        return ((buf[p++] & 0xFF) << 24) | ((buf[p++] & 0xFF) << 16) | ((buf[p++] & 0xFF) << 8) | (buf[p++] & 0xFF);
    }
    public long readLong() throws IOException { long hi = readInt(); return (hi << 32) | (readInt() & 0xFFFFFFFFL); }
    public long readVarLong() throws IOException {
        long v = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            byte b = readByte();
            v |= (long) (b & 0x7F) << shift;
            if (b >= 0) return v;
        }
        throw new IOException("number too long");
    }
    public int readVarInt() throws IOException { return (int) readVarLong(); }
    public float readFloat() throws IOException { return Float.intBitsToFloat(readInt()); }
    public void readFully(byte[] b) throws IOException { need(b.length); System.arraycopy(buf, p, b, 0, b.length); p += b.length; }

    public String readUTF() throws IOException {
        int len = readShort() & 0xFFFF;
        need(len * 2);
        char[] c = new char[len];
        for (int i = 0; i < len; i++) c[i] = (char) (((buf[p++] & 0xFF) << 8) | (buf[p++] & 0xFF));
        return new String(c);
    }
}

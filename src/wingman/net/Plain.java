package wingman.net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plain data on the wire: yes/no, numbers, text, and lists, sets and maps of those (map keys are text). Nothing
 * else is ever written or read, so a value a mod keeps that is an object of its own is left out, and what comes
 * back is always the JDK's own types: there is no way for a message to make the reader create a class it names.
 *
 * Lists and maps come back as ArrayList / HashMap (and LinkedHash* when the original kept its order), sets as
 * HashSet / LinkedHashSet, numbers as the boxed type they were sent as.
 */
public final class Plain {
    private Plain() {}

    public static final int MAX_DEPTH = 6, MAX_ITEMS = 4000, MAX_TEXT = 4000;
    private static final byte NULL = 0, TRUE = 1, FALSE = 2, INT = 3, LONG = 4, FLOAT = 5, DOUBLE = 6, TEXT = 7,
            LIST = 8, SET = 9, MAP = 10, ORDERED_SET = 11, ORDERED_MAP = 12, SHORT = 13, BYTE = 14;

    /** Whether a value is plain data all the way down (within the limits), so that write() takes it. */
    public static boolean isPlain(Object v) { return isPlain(v, 0, new int[] { MAX_NODES }); }

    /** Most values looked at for one value, all levels together (a huge or tangled structure is left out). */
    public static final int MAX_NODES = 20000;

    private static boolean isPlain(Object v, int depth, int[] left) {
        if (--left[0] < 0) return false;
        if (v == null || v instanceof Boolean || v instanceof Integer || v instanceof Long || v instanceof Float
                || v instanceof Double || v instanceof Short || v instanceof Byte) return true;
        if (v instanceof String) return ((String) v).length() <= MAX_TEXT;
        if (depth >= MAX_DEPTH) return false;
        if (v instanceof List || v instanceof Set) {
            Collection<?> c = (Collection<?>) v;
            if (c.size() > MAX_ITEMS) return false;
            for (Object o : c) if (!isPlain(o, depth + 1, left)) return false;
            return true;
        }
        if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            if (m.size() > MAX_ITEMS) return false;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!(e.getKey() instanceof String) || ((String) e.getKey()).length() > MAX_TEXT) return false;
                if (!isPlain(e.getValue(), depth + 1, left)) return false;
            }
            return true;
        }
        return false;
    }

    /** Write a value that isPlain() accepted. */
    public static void write(Out out, Object v) throws IOException { write(out, v, 0); }

    private static void write(Out out, Object v, int depth) throws IOException {
        if (v == null) { out.writeByte(NULL); return; }
        if (v instanceof Boolean) { out.writeByte(((Boolean) v).booleanValue() ? TRUE : FALSE); return; }
        if (v instanceof Integer) { out.writeByte(INT); out.writeInt(((Integer) v).intValue()); return; }
        if (v instanceof Long) { out.writeByte(LONG); out.writeLong(((Long) v).longValue()); return; }
        if (v instanceof Float) { out.writeByte(FLOAT); out.writeFloat(((Float) v).floatValue()); return; }
        if (v instanceof Double) { out.writeByte(DOUBLE); out.writeDouble(((Double) v).doubleValue()); return; }
        if (v instanceof Short) { out.writeByte(SHORT); out.writeShort(((Short) v).shortValue()); return; }
        if (v instanceof Byte) { out.writeByte(BYTE); out.writeByte(((Byte) v).byteValue()); return; }
        if (v instanceof String) { out.writeByte(TEXT); writeLongText(out, (String) v); return; }
        if (depth >= MAX_DEPTH) throw new IOException("too deep");
        if (v instanceof List) {
            List<?> l = (List<?>) v;
            out.writeByte(LIST);
            out.writeVarInt(l.size());
            for (Object o : l) write(out, o, depth + 1);
            return;
        }
        if (v instanceof Set) {
            Set<?> s = (Set<?>) v;
            out.writeByte(v instanceof LinkedHashSet ? ORDERED_SET : SET);
            out.writeVarInt(s.size());
            for (Object o : s) write(out, o, depth + 1);
            return;
        }
        if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            out.writeByte(v instanceof LinkedHashMap ? ORDERED_MAP : MAP);
            out.writeVarInt(m.size());
            for (Map.Entry<?, ?> e : m.entrySet()) {
                writeLongText(out, (String) e.getKey());
                write(out, e.getValue(), depth + 1);
            }
            return;
        }
        throw new IOException("not plain data: " + v.getClass().getName());
    }

    /** Text of any length up to MAX_TEXT (writeUTF stops at 64 KB of bytes; this has no such edge). */
    private static void writeLongText(Out out, String s) throws IOException {
        out.writeVarInt(s.length());
        for (int i = 0; i < s.length(); i++) out.writeVarInt(s.charAt(i));
    }

    private static String readLongText(In in) throws IOException {
        int n = in.readVarInt();
        if (n < 0 || n > MAX_TEXT) throw new IOException("text too long: " + n);
        char[] c = new char[n];
        for (int i = 0; i < n; i++) {
            int ch = in.readVarInt();
            if (ch < 0 || ch > 0xFFFF) throw new IOException("bad character");
            c[i] = (char) ch;
        }
        return new String(c);
    }

    public static Object read(In in) throws IOException { return read(in, 0); }

    private static Object read(In in, int depth) throws IOException {
        byte kind = in.readByte();
        switch (kind) {
            case NULL: return null;
            case TRUE: return Boolean.TRUE;
            case FALSE: return Boolean.FALSE;
            case INT: return Integer.valueOf(in.readInt());
            case LONG: return Long.valueOf(in.readLong());
            case FLOAT: return Float.valueOf(in.readFloat());
            case DOUBLE: return Double.valueOf(in.readDouble());
            case SHORT: return Short.valueOf(in.readShort());
            case BYTE: return Byte.valueOf(in.readByte());
            case TEXT: return readLongText(in);
            default: break;
        }
        if (depth >= MAX_DEPTH) throw new IOException("too deep");
        int n = in.readVarInt();
        if (n < 0 || n > MAX_ITEMS || n > in.remaining()) throw new IOException("too many items: " + n);
        switch (kind) {
            case LIST: {
                List<Object> l = new ArrayList<Object>(n);
                for (int i = 0; i < n; i++) l.add(read(in, depth + 1));
                return l;
            }
            case SET: case ORDERED_SET: {
                Set<Object> s = kind == SET ? new HashSet<Object>() : new LinkedHashSet<Object>();
                for (int i = 0; i < n; i++) s.add(read(in, depth + 1));
                return s;
            }
            case MAP: case ORDERED_MAP: {
                Map<String, Object> m = kind == MAP ? new HashMap<String, Object>() : new LinkedHashMap<String, Object>();
                for (int i = 0; i < n; i++) {
                    String k = readLongText(in);
                    m.put(k, read(in, depth + 1));
                }
                return m;
            }
            default: throw new IOException("unknown value kind " + kind);
        }
    }

    /** A map of plain values: written as a count and key/value pairs. Values that aren't plain are left out. */
    public static int writeMap(Out out, Map<String, Object> m) throws IOException {
        out.writeVarInt(m.size());
        for (Map.Entry<String, Object> e : m.entrySet()) {
            writeLongText(out, e.getKey());
            write(out, e.getValue());
        }
        return m.size();
    }

    public static Map<String, Object> readMap(In in) throws IOException {
        int n = in.readVarInt();
        if (n < 0 || n > MAX_ITEMS * 4 || n > in.remaining()) throw new IOException("too many entries: " + n);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i < n; i++) {
            String k = readLongText(in);
            m.put(k, read(in));
        }
        return m;
    }
}

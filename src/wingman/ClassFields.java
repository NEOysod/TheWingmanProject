package wingman;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds a field of a game class by its type, reading the class file itself (the game's loader hands it out as a
 * resource). For private fields whose scrambled name may differ between copies of the game (a localised or
 * patched game jar): java.lang.reflect, which could list them, is refused to mods. Returns null if the class file
 * can't be read, or if not exactly one field has that type. Public for wingman.patch, which asks it which fields of
 * an object could hold a ship.
 */
public final class ClassFields {
    private ClassFields() {}

    private static final int STATIC = 0x0008;

    static String nameOfFieldWithType(Class<?> c, String descriptor) {
        List<String[]> all = fields(c);
        if (all == null) return null;
        String found = null;
        for (String[] f : all) {
            if (!descriptor.equals(f[1])) continue;
            if (found != null) return null; // two of that type: can't tell which
            found = f[0];
        }
        return found;
    }

    /**
     * The instance fields a class declares itself (not its superclasses') whose type is a class or interface:
     * {name, binary name of the type}. Null if the class file can't be read (a loose script's class has none).
     */
    public static List<String[]> objectFields(Class<?> c) {
        List<String[]> all = fields(c);
        if (all == null) return null;
        List<String[]> out = new ArrayList<String[]>();
        for (String[] f : all) {
            String d = f[1];
            if ((Integer.parseInt(f[2]) & STATIC) != 0 || !d.startsWith("L") || !d.endsWith(";")) continue;
            out.add(new String[] { f[0], d.substring(1, d.length() - 1).replace('/', '.') });
        }
        return out;
    }

    /** Every field the class file declares: {name, descriptor, access flags}; null if it can't be read. */
    private static List<String[]> fields(Class<?> c) {
        try {
            byte[] b = read(c);
            if (b == null) return null;
            int n = u2(b, 8), at = 10;
            String[] utf = new String[n];
            for (int k = 1; k < n; k++) {
                int tag = b[at] & 0xFF;
                switch (tag) {
                    case 1: { int len = u2(b, at + 1); utf[k] = mutf8(b, at + 3, len); at += 3 + len; break; }
                    case 3: case 4: at += 5; break;
                    case 5: case 6: at += 9; k++; break;
                    case 7: case 8: case 16: case 19: case 20: at += 3; break;
                    case 9: case 10: case 11: case 12: case 17: case 18: at += 5; break;
                    case 15: at += 4; break;
                    default: return null;
                }
            }
            at += 6; // access, this, super
            at += 2 + 2 * u2(b, at); // interfaces
            int fields = u2(b, at);
            at += 2;
            List<String[]> out = new ArrayList<String[]>(fields);
            for (int f = 0; f < fields; f++) {
                int access = u2(b, at);
                String name = utf[u2(b, at + 2)], desc = utf[u2(b, at + 4)];
                int attrs = u2(b, at + 6);
                at += 8;
                for (int a = 0; a < attrs; a++) at += 6 + u4(b, at + 2);
                out.add(new String[] { name, desc, String.valueOf(access) });
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] read(Class<?> c) throws java.io.IOException {
        String path = c.getName().replace('.', '/') + ".class";
        ClassLoader l = c.getClassLoader();
        InputStream in = l == null ? null : l.getResourceAsStream(path);
        if (in == null) return null;
        try {
            byte[] buf = new byte[8192];
            byte[] out = new byte[0];
            for (int r; (r = in.read(buf)) > 0;) {
                byte[] grown = new byte[out.length + r];
                System.arraycopy(out, 0, grown, 0, out.length);
                System.arraycopy(buf, 0, grown, out.length, r);
                out = grown;
            }
            return out;
        } finally {
            in.close();
        }
    }

    private static int u2(byte[] b, int at) { return ((b[at] & 0xFF) << 8) | (b[at + 1] & 0xFF); }
    private static int u4(byte[] b, int at) { return (u2(b, at) << 16) | u2(b, at + 2); }

    /** Java's "modified UTF-8", as class files store names. */
    private static String mutf8(byte[] b, int at, int len) {
        StringBuilder sb = new StringBuilder(len);
        int end = at + len;
        while (at < end) {
            int x = b[at++] & 0xFF;
            if (x < 0x80) sb.append((char) x);
            else if ((x & 0xE0) == 0xC0) sb.append((char) (((x & 0x1F) << 6) | (b[at++] & 0x3F)));
            else { int y = b[at++] & 0x3F, z = b[at++] & 0x3F; sb.append((char) (((x & 0x0F) << 12) | (y << 6) | z)); }
        }
        return sb.toString();
    }
}

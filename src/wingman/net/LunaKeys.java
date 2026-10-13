package wingman.net;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** A joiner's own keys for mods' LunaLib settings (Msg.LUNA_KEYS, joiner -> host): "mod/field" -> key code. */
public final class LunaKeys {
    private LunaKeys() {}

    public static final int MAX_KEYS = 400;

    public static byte[] encode(Map<String, Integer> keys) {
        Msg.W w = new Msg.W();
        try {
            int n = Math.min(keys.size(), MAX_KEYS);
            w.out.writeVarInt(n);
            int i = 0;
            for (Map.Entry<String, Integer> e : keys.entrySet()) {
                if (i++ >= n) break;
                String f = e.getKey();
                w.out.writeUTF(f.length() > 200 ? f.substring(0, 200) : f);
                w.out.writeByte(e.getValue());
            }
        } catch (IOException e) { throw new IllegalStateException(e); }
        return w.done();
    }

    public static Map<String, Integer> decode(byte[] data) throws IOException {
        In in = Msg.reader(data);
        int n = in.readVarInt();
        if (n < 0 || n > MAX_KEYS || n > in.remaining()) throw new IOException("too many keys: " + n);
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < n; i++) {
            String f = in.readUTF();
            int k = in.readByte() & 0xFF;
            if (f.length() <= 200 && k > 0) out.put(f, k);
        }
        return out;
    }
}

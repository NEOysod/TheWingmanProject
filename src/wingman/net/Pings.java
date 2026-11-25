package wingman.net;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everyone's round trip to the host, as the host last told it (Msg.PINGS, every 2 s), by P number. On the host the
 * same figures come straight from each connection (Conn.rttMs); the host itself is 0.
 */
public final class Pings {
    private Pings() {}

    private static volatile Map<Integer, Integer> bySlot = new LinkedHashMap<Integer, Integer>();
    private static volatile long heardAt;

    public static byte[] encode(Map<Integer, Integer> slots) {
        Msg.W w = new Msg.W();
        try {
            int n = Math.min(255, slots.size());
            w.out.writeByte(n);
            int i = 0;
            for (Map.Entry<Integer, Integer> e : slots.entrySet()) {
                if (i++ >= n) break;
                w.out.writeByte(e.getKey());
                w.out.writeShort(Math.max(-1, Math.min(Short.MAX_VALUE, e.getValue())));
            }
        } catch (IOException e) { throw new IllegalStateException(e); }
        return w.done();
    }

    public static Map<Integer, Integer> decode(byte[] data) throws IOException {
        In in = Msg.reader(data);
        int n = (in.readByte() & 0xFF);
        Map<Integer, Integer> m = new LinkedHashMap<Integer, Integer>();
        for (int i = 0; i < n; i++) {
            int slot = (in.readByte() & 0xFF);
            m.put(slot, (int) in.readShort());
        }
        return m;
    }

    /** A PINGS message from the host (joiner side). */
    public static void onMessage(byte[] data) {
        try { bySlot = decode(data); heardAt = System.currentTimeMillis(); } catch (IOException e) { /* the next one replaces it */ }
    }

    /** A player's round trip to the host (ms), as last told; -1 unknown (or the word is more than 10 s old). */
    public static int of(int slot) {
        if (slot == 1) return 0;
        if (System.currentTimeMillis() - heardAt > 10000) return -1;
        Integer v = bySlot.get(slot);
        return v == null ? -1 : v;
    }

    public static void forget() { bySlot = new LinkedHashMap<Integer, Integer>(); heardAt = 0; }

    /** How a round trip reads: "54 ms", "?" unknown. */
    public static String text(int ms) { return ms < 0 ? "?" : ms + " ms"; }

    /** 0 good (under 120 ms), 1 fair (under 220), 2 poor; -1 unknown. */
    public static int grade(int ms) { return ms < 0 ? -1 : ms < 120 ? 0 : ms < 220 ? 1 : 2; }
}

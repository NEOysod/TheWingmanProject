package wingman.net;

/**
 * Counts what this copy of the game sends and receives, by kind of message, so the log can say how much
 * bandwidth the mod really uses. Reset each time a report is taken.
 */
public final class NetStats {
    private NetStats() {}

    private static final long[] outBytes = new long[256], inBytes = new long[256];
    private static final int[] outCount = new int[256];
    private static int skipped;
    private static long wireOut, wireIn;
    /** Bytes that actually went onto or came off the network, after compression. */
    static synchronized void wireOut(int bytes) { wireOut += bytes; }
    static synchronized void wireIn(int bytes) { wireIn += bytes; }
    private static long since = System.currentTimeMillis();

    static synchronized void sent(byte type, int bytes) { outBytes[type & 0xFF] += bytes; outCount[type & 0xFF]++; }
    static synchronized void received(byte type, int bytes) { inBytes[type & 0xFF] += bytes; }
    /** An update that was not sent because earlier ones were still waiting to go out. */
    static synchronized void skipped() { skipped++; }

    private static String name(int type) {
        switch (type) {
            case Msg.HELLO: return "hello";
            case Msg.INPUT: return "controls";
            case Msg.SYNC_REQ: return "resync request";
            case Msg.CLAIM: return "ship claim";
            case Msg.READY: return "ready check";
            case Msg.WELCOME: return "welcome";
            case Msg.REJECT: return "refusals";
            case Msg.RESET: return "resets";
            case Msg.NOTICE: return "notices";
            case Msg.ALERT: return "pop-ups";
            case Msg.BYE: return "goodbye";
            case Msg.SPAWN: return "ship descriptions";
            case Msg.DESPAWN: return "ship removals";
            case Msg.SNAPSHOT: return "position updates (old format)";
            case Msg.SNAPSHOT_D: return "position updates";
            case Msg.NEED_KEY: return "requests for a full update";
            case Msg.ASSIGN: return "ship assignment";
            case Msg.MISSILE_SPAWN: return "missile launches";
            case Msg.MISSILE_DESPAWN: return "missile removals";
            case Msg.LOBBY: return "lobby";
            case Msg.OBJECTIVES: return "objectives";
            case Msg.ORDERS: return "fleet orders";
            case Msg.BATTLE_INFO: return "battlefield";
            case Msg.ASTEROIDS: return "asteroids";
            case Msg.NEBULA: return "nebula";
            case Msg.DAMAGE: return "armor/ammo/EMP";
            case Msg.MISSILE_TARGET: return "missile targets";
            case Msg.PING: return "keep-alive";
            case Msg.WRECK_SPLIT: return "wreck break-ups";
            case Msg.HURTS: return "damage reports";
            case Msg.STATS: return "ship stats";
            case Msg.NOTES: return "script values";
            case Msg.SHOT_HIT: return "scripted shot hits";
            case Msg.MISSILE_HIT: return "missile hits";
            case Msg.BATTLE_END: return "battle end";
            case Msg.CHECK: return "sync checks";
            default: return "type " + type;
        }
    }

    private static String rate(long bytes, float seconds) {
        float kb = bytes / 1024f / seconds;
        return kb >= 10f ? Math.round(kb) + " KB/s" : (Math.round(kb * 10f) / 10f) + " KB/s";
    }

    /**
     * One line describing traffic since the last report, then starts counting afresh. Returns null if nothing
     * moved, or if less than minSeconds have passed.
     */
    public static synchronized String report(float minSeconds) {
        long now = System.currentTimeMillis();
        float seconds = (now - since) / 1000f;
        if (seconds < minSeconds) return null;
        long out = 0, in = 0;
        for (int i = 0; i < 256; i++) { out += outBytes[i]; in += inBytes[i]; }
        String line = null;
        if (out + in > 0) {
            StringBuilder sb = new StringBuilder();
            // "On the wire" is what the connection really carried; the other figure is the messages before compression.
            sb.append("on the wire: sent ").append(rate(wireOut, seconds)).append(", received ").append(rate(wireIn, seconds))
              .append(" over ").append(Math.round(seconds)).append("s (before compression: sent ").append(rate(out, seconds))
              .append(", received ").append(rate(in, seconds)).append(")");
            if (skipped > 0) sb.append(", ").append(skipped).append(" updates skipped (link backed up)");
            appendTop(sb, "sent", outBytes, out, seconds, true);
            appendTop(sb, "received", inBytes, in, seconds, false);
            line = sb.toString();
        }
        for (int i = 0; i < 256; i++) { outBytes[i] = 0; inBytes[i] = 0; outCount[i] = 0; }
        skipped = 0;
        wireOut = 0;
        wireIn = 0;
        since = now;
        return line;
    }

    /** The biggest few kinds of message, with their share and (for sent ones) their average size. */
    private static void appendTop(StringBuilder sb, String label, long[] bytes, long total, float seconds, boolean sizes) {
        if (total <= 0) return;
        sb.append(". Biggest ").append(label).append(": ");
        boolean[] used = new boolean[256];
        for (int n = 0; n < 4; n++) {
            int best = -1;
            for (int i = 0; i < 256; i++) if (!used[i] && bytes[i] > 0 && (best < 0 || bytes[i] > bytes[best])) best = i;
            if (best < 0) break;
            used[best] = true;
            if (n > 0) sb.append(", ");
            sb.append(name(best)).append(' ').append(rate(bytes[best], seconds)).append(" (").append(Math.round(bytes[best] * 100f / total)).append("%");
            if (sizes && outCount[best] > 0) sb.append(", avg ").append(bytes[best] / outCount[best]).append(" bytes");
            sb.append(')');
        }
    }
}

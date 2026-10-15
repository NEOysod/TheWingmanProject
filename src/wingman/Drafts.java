package wingman;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;

import wingman.net.RefitRequest;

/**
 * Friend refit, joiner side: the plan for each ship, kept until the host accepts it, so a refit planned away from
 * a market, or cut short by a battle, a restart or another pick, is there again next time. One line per ship in a
 * small file of the game's own common folder: the member id, then the request as hex. At most MAX ships; the
 * oldest goes first.
 */
final class Drafts {
    private Drafts() {}

    static final String FILE = "wingman_refit_plans";
    static final int MAX = 24;
    private static Map<String, byte[]> plans;

    private static Map<String, byte[]> plans() {
        if (plans != null) return plans;
        plans = new LinkedHashMap<String, byte[]>();
        try {
            if (Global.getSettings().fileExistsInCommon(FILE)) {
                String text = Global.getSettings().readTextFileFromCommon(FILE);
                for (String line : text == null ? new String[0] : text.split("\n")) {
                    int eq = line.indexOf('=');
                    if (eq <= 0 || eq > 200) continue;
                    byte[] b = unhex(line.substring(eq + 1).trim());
                    if (b != null && b.length <= 64 * 1024) plans.put(line.substring(0, eq).trim(), b);
                }
            }
        } catch (Throwable t) {
            Global.getLogger(Drafts.class).warn("Wingman: saved refit plans could not be read; starting without them", t);
        }
        return plans;
    }

    /** The saved plan for that ship, or null. */
    static RefitRequest get(String memberId) {
        byte[] b = plans().get(memberId);
        if (b == null) return null;
        try {
            RefitRequest r = RefitRequest.decode(b);
            return memberId.equals(r.memberId) ? r : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Keep this plan (or, when it changes nothing, forget any kept for its ship). Written only when it changed. */
    static void put(RefitRequest r, boolean empty) {
        if (r == null || r.memberId.isEmpty()) return;
        if (empty) { forget(r.memberId); return; }
        byte[] b;
        try { b = r.encode(); } catch (Throwable t) { return; }
        byte[] had = plans().get(r.memberId);
        if (had != null && Arrays.equals(had, b)) return;
        plans().remove(r.memberId); // to the end: the newest
        plans().put(r.memberId, b);
        while (plans().size() > MAX) plans().remove(plans().keySet().iterator().next());
        write();
    }

    static void forget(String memberId) {
        if (plans().remove(memberId) != null) write();
    }

    private static void write() {
        try {
            StringBuilder sb = new StringBuilder();
            List<Map.Entry<String, byte[]>> all = new ArrayList<Map.Entry<String, byte[]>>(plans().entrySet());
            for (Map.Entry<String, byte[]> e : all) sb.append(e.getKey().replace('=', '_').replace('\n', '_')).append('=').append(hex(e.getValue())).append('\n');
            Global.getSettings().writeTextFileToCommon(FILE, sb.toString());
        } catch (Throwable t) {
            Global.getLogger(Drafts.class).warn("Wingman: refit plans could not be saved; they last until the game is closed", t);
        }
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    static String hex(byte[] b) {
        char[] c = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) { c[i * 2] = HEX[(b[i] >> 4) & 15]; c[i * 2 + 1] = HEX[b[i] & 15]; }
        return new String(c);
    }

    static byte[] unhex(String s) {
        if (s.length() % 2 != 0) return null;
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16), lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return null;
            b[i] = (byte) ((hi << 4) | lo);
        }
        return b;
    }
}

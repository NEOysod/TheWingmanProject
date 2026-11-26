package wingman;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.json.JSONObject;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipSystemSpecAPI;

/**
 * Which ship systems move their ship sharply, vanilla or any mod's: by <b>jumping</b> (a teleporter: Phase Skimmer,
 * Phase Teleporter, a mod's blink), by a <b>burst</b> of speed (burn drive, plasma jets, a mod's dash), or not at
 * all (shields, dampers, weapon boosts). Told first from the system's own description, the same way for every mod:
 * its type (DISPLACER or TELEPORTER), the game's own teleporter class, its "movement" tag, and the hints it gives
 * the game's AI about speed. Then learned by watching (see wingman.net.Motion): a system whose ship is seen to jump,
 * or to burst, soon after it starts, on most of the times it is used (see saw), is taken for one that does, and
 * that is kept on this PC for next time (saves/common/wingman_movers.txt). What it is used for:
 * <ul>
 * <li>a teleporter is never run on a copy of someone else's ship (the copy can't know where the real one goes and
 *     would jump somewhere else first); the copy appears where the host's does, with the system's look drawn;</li>
 * <li>while a moving system starts or ends, copies are carried on in a straight line from the host's latest figures
 *     and held close, rather than along a curve that a burst bends at once (see WingmanClientPlugin.apply).</li>
 * </ul>
 */
final class Movers {
    private Movers() {}

    static final int STILL = 0, BURST = 1, JUMP = 2;
    private static final String FILE = "wingman_movers.txt";
    /**
     * Marks a file written since jumps were only read from real updates (Motion.guessSlack). Test builds before
     * that misread updates the bandwidth limit had left out as jumps, and learned Burn Drive, Maneuvering Jets and
     * Plasma Burn as teleports: their copies then glowed with the teleport shimmer and snapped about. A jump in an
     * older file is read as a burst (held close, its system still run), and learned again if it really is one.
     */
    private static final String VERSION = "(v3)";
    /**
     * A system is learned as a mover once its ship was seen to burst on at least BURST_USES of the uses watched,
     * and on two thirds of them; to jump on at least JUMP_USES, and on half. One burst or jump per use counts.
     * (Test builds before (v3) learned from any two sightings, even within one use: a ship knocked by a blast
     * while its Ammo Feeder or Targeting Feed ran taught that those move it, and its copies were then held close
     * while they ran, which only makes them shake. A file from before (v3) is forgotten and learned again.)
     */
    static final int BURST_USES = 3, JUMP_USES = 2;

    /** By system id: what its description says. */
    private static final Map<String, Integer> described = new HashMap<String, Integer>();
    /**
     * Systems that only change how the engines work (type ENGINE_MOD: burn drive, maneuvering jets, plasma jets,
     * a mod's afterburner). However fast, the ship flies there, so such a system is never taken for a jump: a
     * jump seen while one runs is the figures' fault (an update the bandwidth limit left out), not the system's.
     */
    private static final java.util.Set<String> engineOnly = new java.util.HashSet<String>();
    /** By system id: what was learned by watching (kept on this PC). */
    private static Map<String, Integer> learned;
    /** By system id: { uses watched, uses with a burst, uses with a jump }, not yet enough to learn from. */
    private static final Map<String, int[]> seen = new HashMap<String, int[]>();
    private static int learnedNow;
    private static String teleporterClass;

    /** How this system moves its ship: STILL, BURST or JUMP. */
    static int kind(ShipSystemAPI sys) {
        if (sys == null) return STILL;
        String id;
        try { id = sys.getId(); } catch (Throwable t) { return JUMP; } // can't tell: the kind that must not be pushed
        Integer l = id == null ? null : learned().get(id);
        int d = describe(sys, id);
        if (l != null && l == JUMP && engineOnly.contains(id)) l = BURST; // (an older build could learn that wrongly)
        return l == null ? d : Math.max(d, l);
    }

    /** Does it teleport the ship? */
    static boolean jumps(ShipSystemAPI sys) { return kind(sys) == JUMP; }

    /** Does it move the ship sharply at all (a burst or a jump)? */
    static boolean moves(ShipSystemAPI sys) { return kind(sys) != STILL; }

    /** This system was just switched on (on the host) on a ship being watched: one more use to judge it by. */
    static void used(ShipSystemAPI sys) {
        try {
            String id = sys == null ? null : sys.getId();
            if (id == null) return;
            int[] n = seen.get(id);
            if (n == null) seen.put(id, n = new int[3]);
            n[0]++;
        } catch (Throwable t) { /* not counted */ }
    }

    /** The ship was seen to jump soon after this system started (once per use, see WingmanClientPlugin.noteMotion). */
    static void sawJump(ShipSystemAPI sys) { saw(sys, JUMP); }

    /** The ship was seen in a burst soon after this system started (once per use). */
    static void sawBurst(ShipSystemAPI sys) { saw(sys, BURST); }

    /** Systems learned this session (for the log). */
    static int learnedThisSession() { return learnedNow; }

    private static void saw(ShipSystemAPI sys, int what) {
        try {
            if (sys == null || kind(sys) >= what) return;
            String id = sys.getId();
            if (id == null) return;
            if (what == JUMP && engineOnly.contains(id)) return; // its ship only flies, however fast
            int[] n = seen.get(id);
            if (n == null) seen.put(id, n = new int[3]);
            n[what]++;
            int uses = Math.max(n[0], n[what]);
            if (what == BURST ? n[what] < BURST_USES || n[what] * 3 < uses * 2 : n[what] < JUMP_USES || n[what] * 2 < uses) return;
            learned().put(id, what);
            learnedNow++;
            save();
            String name = sys.getSpecAPI() == null ? id : sys.getSpecAPI().getName() + " (" + id + ")";
            Global.getLogger(Movers.class).info("Wingman: the ship system " + name + " moves its ship "
                    + (what == JUMP ? "by jumping: from now on it isn't run on copies of others' ships, which appear where the host's does"
                            : "in bursts: copies are held close while it starts and ends")
                    + " (seen on " + n[what] + " of " + Math.max(n[0], n[what]) + " uses; remembered on this PC in saves/common/" + FILE + ")");
        } catch (Throwable t) { /* not learned: copies are handled as before */ }
    }

    /** From the system's description alone. */
    private static int describe(ShipSystemAPI sys, String id) {
        Integer d = id == null ? null : described.get(id);
        if (d != null) return d;
        int kind = STILL;
        try {
            if (sys.getClass().getName().equals(teleporterClass())) kind = JUMP;
            ShipSystemSpecAPI spec = sys.getSpecAPI();
            if (spec != null) {
                JSONObject json = null;
                try { json = spec.getSpecJson(); } catch (Throwable t) { /* the rest decides */ }
                String type = json == null ? "" : json.optString("type", "").toUpperCase(java.util.Locale.ROOT);
                if (type.contains("DISPLACER") || type.contains("TELEPORTER")) kind = JUMP;
                if (type.equals("ENGINE_MOD") && id != null) engineOnly.add(id);
                if (kind == STILL) {
                    boolean movement = false;
                    try { movement = spec.hasTag("movement"); } catch (Throwable t) { /* hints decide */ }
                    JSONObject hints = json == null ? null : json.optJSONObject("aiHints");
                    if (hints != null && (hints.optDouble("activeSpeedIncrease", 0) > 0 || hints.optDouble("averageSpeedIncrease", 0) > 0
                            || hints.optDouble("burstMovementPotential", 0) > 0)) movement = true;
                    if (type.contains("BURN") || type.contains("JETS")) movement = true;
                    if (movement) kind = BURST;
                }
            }
        } catch (Throwable t) {
            kind = JUMP; // can't tell: the kind that must not be pushed
        }
        if (id != null) described.put(id, kind);
        return kind;
    }

    /** The game's teleporter system class in this build (the name differs build by build). */
    static String teleporterClass() {
        if (teleporterClass != null) return teleporterClass;
        String v = Mods.gameVersion();
        String c;
        if (v.contains("RC9")) c = "com.fs.starfarer.combat.systems.J";
        else if (v.contains("RC8")) { StringBuilder n = new StringBuilder("com.fs.starfarer.combat.systems.O0o"); for (int i = 0; i < 253; i++) n.append('O'); c = n.toString(); }
        else if (v.contains("RC7")) c = "com.fs.starfarer.combat.systems.I";
        else if (v.contains("RC6")) c = "com.fs.starfarer.combat.systems.int";
        else c = "com.fs.starfarer.combat.systems.null"; // RC5
        return teleporterClass = c;
    }

    private static Map<String, Integer> learned() {
        if (learned != null) return learned;
        learned = new LinkedHashMap<String, Integer>();
        try {
            if (Global.getSettings().fileExistsInCommon(FILE)) {
                String text = Global.getSettings().readTextFileFromCommon(FILE);
                // Written by an older build (no VERSION mark): learned too eagerly, so forgotten (learned again).
                boolean trusted = text != null && text.contains(VERSION);
                if (text != null && trusted) for (String line : text.split("\n")) {
                    int eq = line.indexOf('=');
                    if (eq <= 0 || eq > 200) continue;
                    String v = line.substring(eq + 1).trim();
                    int k = "jump".equals(v) ? JUMP : "burst".equals(v) ? BURST : -1;
                    if (k > 0) learned.put(line.substring(0, eq).trim(), k);
                }
            }
        } catch (Throwable t) { /* nothing learned yet, as far as this session knows */ }
        return learned;
    }

    private static void save() {
        try {
            StringBuilder sb = new StringBuilder("# Ship systems Wingman has seen move their ship sharply (jump or burst). Delete this file to forget them. " + VERSION + "\n");
            for (Map.Entry<String, Integer> e : learned().entrySet()) {
                if (e.getValue() == JUMP && engineOnly.contains(e.getKey())) e.setValue(BURST); // put right
                sb.append(e.getKey()).append('=').append(e.getValue() == JUMP ? "jump" : "burst").append('\n');
            }
            Global.getSettings().writeTextFileToCommon(FILE, sb.toString());
        } catch (Throwable t) { /* kept until the game closes */ }
    }
}

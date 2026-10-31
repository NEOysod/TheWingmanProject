package wingman.patch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.fs.starfarer.api.combat.ShipAPI;

/**
 * What Wingman's PatchLib hooks (see {@link Patches}) share, and Wingman's way in.
 *
 * The idea: in a co-op battle, a ship another person flies is, on this PC, an ordinary AI ship (on the host: a
 * joiner's ship; on a joiner: the host's ship and the other joiners'). So scripts that do something for "the
 * player's ship" never do it for that ship here, and every PC disagrees about who the player is. While the
 * game runs a frame of a ship a person on another PC flies (its hullmods, ship system, weapons' scripts,
 * MagicLib subsystems), that ship is the <i>acting</i> ship, and in mods' code running for it:
 * <ul>
 * <li>{@code engine.getPlayerShip()} answers the acting ship, so "if this is the player's ship" is true for
 * it, as it is on its pilot's own screen;</li>
 * <li>{@code engine.setPlayerShipExternal(s)} goes to the bridge: on the host the joiner is moved into s
 * (Wingman claims it for them); on a joiner nothing happens here (the host decides);</li>
 * <li>the mouse is that person's cursor (where they point, on this screen), and no key is ever this PC's;</li>
 * <li>things only meant for one player's own screen and game speed do nothing here: the status line in the
 * corner, the camera, interface sounds, and the battle's time multiplier (a script slowing time for "the
 * player" would slow the battle for everyone).</li>
 * </ul>
 * Everything else, the game's own code, and everything outside an acting ship's frame (screen-wide plugins,
 * this PC's own player's ship) gets the real answer. With no bridge set (not in a co-op battle), nothing is
 * changed.
 *
 * Names no PatchLib class: Wingman calls in here whether or not the hooks were installed.
 * All of this is on the game thread; the one static field is fine.
 */
public final class Hooks {
    private Hooks() {}

    /** Set by Wingman; null when not in a co-op battle. */
    static volatile Bridge bridge;
    /** The ship whose frame is running, if a person on another PC flies it (a ShipAPI). Game thread only. */
    static Object acting;
    /** Plugins a script added for a joiner's ship (plugin -> ship). Weak keys. */
    static final Map<Object, Object> owners = Collections.synchronizedMap(new WeakHashMap<Object, Object>());
    /** Where a script last handed the host's player another ship itself (not for a joiner): for the host's log. */
    static volatile Throwable lastSet;
    /** Diagnostics: how often each hook changed an answer. */
    static volatile long playerShipAnswers, moves, mouseAnswers, keyAnswers, statusDropped, timeDropped, screenDropped, contexts, frames,
            pluginsOwned;
    /**
     * Keys that mods' scripts have asked about on this PC outside any other player's ship (on a joiner: its own
     * ship's scripts, and its screen). Bit k of word k/64 is key code k. Wingman sends those of them held down
     * with the joiner's controls, so the same scripts running on the host for the joiner's ship hear them.
     */
    static final long[] polledKeys = new long[4];

    /**
     * Campaign lookups that found nothing (a joiner's battle, while Wingman has recording on): what mods' scripts
     * asked the campaign's memory and saved data for that this PC's stand-in campaign couldn't answer, and how
     * often. Key -> count. At most MAX_MISSES keys.
     */
    static volatile boolean recording;
    static final Map<String, int[]> misses = Collections.synchronizedMap(new LinkedHashMap<String, int[]>());
    static final int MAX_MISSES = 400;

    /** The mark Wingman's host writes on a ship a joiner flies (see wingman.PilotMarks). */
    static final String REMOTE = "wingman_remote";

    static void miss(String key) {
        if (key == null) return;
        synchronized (misses) {
            int[] n = misses.get(key);
            if (n == null) {
                if (misses.size() >= MAX_MISSES) return;
                misses.put(key, n = new int[1]);
            }
            n[0]++;
        }
    }

    /** Whose frame this is: the ship itself if a joiner flies it; for a module or a fighter, the ship it belongs to. */
    static ShipAPI actingFor(ShipAPI s) {
        Bridge b = bridge;
        if (s == null || b == null) return null;
        try {
            if (!b.live()) return null;
            if (remote(s)) return s;
            ShipAPI parent = s.getParentStation();
            if (parent != null && remote(parent)) return parent;
            if (s.isFighter() && s.getWing() != null) {
                ShipAPI src = s.getWing().getSourceShip();
                if (src != null && remote(src)) return src;
            }
        } catch (Throwable t) { /* never break the game's frame */ }
        return null;
    }

    private static boolean remote(ShipAPI s) {
        Map<String, Object> d = s.getCustomData();
        return d != null && d.containsKey(REMOTE);
    }

    // ---- Wingman's side ----

    public static void setBridge(Bridge b) { bridge = b; acting = null; owners.clear(); }
    public static Object acting() { return acting; }

    /** Run something with this ship acting (Wingman uses it when it fires a joiner's ability itself). */
    public static void runAs(ShipAPI ship, Runnable r) {
        Object prev = acting;
        acting = actingFor(ship);
        try { r.run(); } finally { acting = prev; }
    }

    /**
     * Run something for a ship Wingman knows a joiner flies (it fires their ability itself): that ship acts, even
     * if its mark isn't written yet (they got it this frame).
     */
    public static void runAsPilot(Object ship, Runnable r) {
        Bridge b = bridge;
        Object prev = acting;
        boolean live = false;
        try { live = b != null && ship != null && b.live(); } catch (Throwable t) { /* as not live */ }
        acting = live ? ship : null;
        try { r.run(); } finally { acting = prev; }
    }

    /** The keys asked about so far (see polledKeys), as a copy. */
    public static long[] polledKeys() { return polledKeys.clone(); }

    /** Wingman turns recording on for a joiner's battle and off when it ends. */
    public static void setRecording(boolean on) { recording = on; }

    /** The lookups recorded since the last call, most asked first; clears them. Key -> times asked. */
    public static Map<String, Integer> takeMisses() {
        List<Map.Entry<String, int[]>> all;
        synchronized (misses) {
            all = new ArrayList<Map.Entry<String, int[]>>(misses.entrySet());
            misses.clear();
        }
        Collections.sort(all, new Comparator<Map.Entry<String, int[]>>() {
            public int compare(Map.Entry<String, int[]> a, Map.Entry<String, int[]> b) { return b.getValue()[0] - a.getValue()[0]; }
        });
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        for (Map.Entry<String, int[]> e : all) out.put(e.getKey(), e.getValue()[0]);
        return out;
    }

    /** Where a script last handed this PC's player another ship (not for a joiner), or null; forgotten once asked. */
    public static Throwable takeLastSet() {
        Throwable t = lastSet;
        lastSet = null;
        return t;
    }

    /** One line for the log. */
    public static String report() {
        return "Wingman hooks (PatchLib); changed answers so far: player ship " + playerShipAnswers + ", ship moves " + moves + ", mouse " + mouseAnswers
                + ", keys " + keyAnswers + ", status lines dropped " + statusDropped + ", time changes dropped " + timeDropped
                + ", camera/UI sounds kept off this screen " + screenDropped + ", joiner ship frames " + contexts + " (and from mods' methods " + frames + ")"
                + ", plugins added for joiners " + pluginsOwned;
    }
}

package wingman.agent;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The hooks' state, in one place whichever copy of {@link Hooks} is running. Where a launcher's class loader
 * loads the game's classes itself (Fast Rendering), each loader whose classes are changed gets its own copy of
 * Hooks, so that it sees the game's classes as they do (see Agent.injectInto); they all keep their state here.
 * Nothing here names a game class, so this class can stay in the agent's own loader and be shared.
 */
public final class Shared {
    private Shared() {}

    /** Set by Wingman; null when not in a co-op battle. */
    public static volatile Bridge bridge;
    /** The ship whose frame is running, if a person on another PC flies it (a ShipAPI). Game thread only. */
    public static Object acting;
    /** Plugins a script added for a joiner's ship (plugin -> ship). Weak keys. */
    public static final Map<Object, Object> owners = Collections.synchronizedMap(new WeakHashMap<Object, Object>());
    /** Diagnostics: how often each hook changed an answer. */
    public static volatile long playerShipAnswers, moves, mouseAnswers, keyAnswers, statusDropped, timeDropped, contexts, pluginsOwned,
            screenDropped, frames;
    /**
     * Campaign lookups that found nothing (a joiner's battle, while Wingman has recording on): what mods' scripts
     * asked the campaign's memory and saved data for that this PC's stand-in campaign couldn't answer, and how
     * often. Key -> count. At most MAX_MISSES keys.
     */
    /**
     * Keys that mods' scripts have asked about on this PC outside any other player's ship (on a joiner: its own
     * ship's scripts, and its screen). Bit k of word k/64 is key code k. Wingman sends those of them held down
     * with the joiner's controls, so the same scripts running on the host for the joiner's ship hear them.
     */
    public static final long[] polledKeys = new long[4];

    public static volatile boolean recording;
    public static final Map<String, int[]> misses = Collections.synchronizedMap(new java.util.LinkedHashMap<String, int[]>());
    public static final int MAX_MISSES = 400;

    public static void miss(String key) {
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

    /** The misses recorded since the last call, most asked first; clears them. */
    public static Map<String, Integer> takeMisses() {
        java.util.List<Map.Entry<String, int[]>> all;
        synchronized (misses) {
            all = new java.util.ArrayList<Map.Entry<String, int[]>>(misses.entrySet());
            misses.clear();
        }
        Collections.sort(all, new java.util.Comparator<Map.Entry<String, int[]>>() {
            public int compare(Map.Entry<String, int[]> a, Map.Entry<String, int[]> b) { return b.getValue()[0] - a.getValue()[0]; }
        });
        Map<String, Integer> out = new java.util.LinkedHashMap<String, Integer>();
        for (Map.Entry<String, int[]> e : all) out.put(e.getKey(), e.getValue()[0]);
        return out;
    }

    /** The agent's own counts (classes changed, calls swapped). */
    public static volatile java.util.concurrent.Callable<?> reportSource;
}

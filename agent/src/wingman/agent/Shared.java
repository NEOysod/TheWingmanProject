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
    /** The agent's own counts (classes changed, calls swapped). */
    public static volatile java.util.concurrent.Callable<?> reportSource;
}

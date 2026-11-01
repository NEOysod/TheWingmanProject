package wingman;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAPI;
import wingman.client.JoinerAgentLink;
import wingman.client.WingmanClientPlugin;

/**
 * The only class of Wingman's that may be loaded without PatchLib (the mod Wingman's hooks are written for, see
 * wingman.patch.Patches). It asks once whether PatchLib is there; only then is {@link AgentLink}, which uses the
 * hooks' classes, ever touched. Without it every call here does nothing, or just runs what it is given.
 */
public final class AgentGate {
    private AgentGate() {}

    private static int state; // 0: not asked yet, 1: PatchLib is there, 2: it isn't
    private static boolean failed;

    static boolean present() {
        if (state == 0) {
            try {
                Class.forName("patchlib.api.PatchLib");
                state = 1;
            } catch (Throwable t) {
                state = 2;
            }
        }
        return state == 1 && !failed;
    }

    /** What the log says about the hooks when they aren't in use. */
    static String absence() {
        if (state == 1 && failed) return "Wingman hooks: PatchLib is there, but they could not be used; mods' player-ship scripts see only this PC's own player."
                + " Please report this with your mod list";
        return "Wingman hooks: PatchLib isn't enabled, so mods' player-ship scripts see only this PC's own player";
    }

    /** One line for the log when the game starts. */
    static String startupLine() {
        if (present()) return "Wingman hooks: on (PatchLib)";
        return absence();
    }

    /** At the start of a battle this PC hosts: the hooks answer for joiners' ships from now on. */
    static void start(WingmanHostPlugin host) {
        if (!present()) {
            Global.getLogger(AgentGate.class).info(absence());
            return;
        }
        try {
            AgentLink.install(host);
            Global.getLogger(AgentGate.class).info("Wingman hooks: on. " + AgentLink.report());
        } catch (Throwable t) {
            failed = true;
            Global.getLogger(AgentGate.class).warn("Wingman hooks: could not be used (a different PatchLib version?); co-op goes on without them", t);
        }
    }

    /** At the start of a battle this PC joins: the hooks answer for the ships other people fly. */
    public static void startJoiner(WingmanClientPlugin joiner) {
        if (!present()) {
            Global.getLogger(AgentGate.class).info(absence());
            return;
        }
        try {
            JoinerAgentLink.install(joiner);
            Global.getLogger(AgentGate.class).info("Wingman hooks: on (joiner). " + AgentLink.report());
        } catch (Throwable t) {
            failed = true;
            Global.getLogger(AgentGate.class).warn("Wingman hooks: could not be used (a different PatchLib version?); co-op goes on without them", t);
        }
    }

    /** Co-op stopped in this battle: the hooks give the real answers again. */
    public static void stop() {
        if (!present()) return;
        try { AgentLink.uninstall(); } catch (Throwable t) { failed = true; }
    }

    /** Run something as part of this ship's frame (a joiner's ability fired by Wingman itself). */
    public static void runAs(ShipAPI ship, Runnable r) {
        if (present()) {
            final boolean[] started = new boolean[1];
            final Runnable inner = r;
            try {
                AgentLink.runAs(ship, new Runnable() {
                    public void run() { started[0] = true; inner.run(); }
                });
                return;
            } catch (LinkageError e) {
                if (started[0]) throw e; // from what was run, not from the hooks: not ours to swallow
                failed = true; // the hooks' classes don't match: from now on, without it
            }
        }
        r.run();
    }

    /**
     * List campaign lookups that find nothing (a joiner's battle, from the moment its stand-in campaign is set up
     * until it is left; see MissedLookups). Without the hooks there is nothing to list.
     */
    /** Whether lookups were listed in this battle (the log says "not recorded" otherwise). */
    public static volatile boolean recordedThisBattle;

    public static void recordLookups(boolean on) {
        if (on) recordedThisBattle = true;
        if (!present()) return;
        try { AgentLink.recordLookups(on); } catch (Throwable t) { /* not available: no list */ }
    }

    /** The lookups listed since the last call (key -> times asked), or null without the hooks. */
    public static java.util.Map<String, Integer> takeMisses() {
        if (!present()) return null;
        try { return AgentLink.takeMisses(); } catch (Throwable t) { return null; }
    }

    /** The keys mods' scripts here have asked about (see Hooks.isKeyDown), or null without the hooks. */
    public static long[] polledKeys() {
        if (!present() || failed) return null;
        try { return AgentLink.polledKeys(); } catch (Throwable t) { return null; } // (not available: none)
    }

    /** Where a mod's script last handed this PC's player another ship, or null (none, or no hooks). */
    static Throwable takeLastSet() {
        if (!present() || failed) return null;
        try { return AgentLink.takeLastSet(); } catch (Throwable t) { return null; } // (not available: unknown)
    }

    /** One line for the log, or null without the hooks. */
    public static String report() {
        if (!present()) return null;
        try { return AgentLink.report(); } catch (Throwable t) { return null; }
    }
}

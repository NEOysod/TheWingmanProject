package wingman;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAPI;

/**
 * The only class of Wingman's that may be loaded without Wingman's agent (jars/WingmanAgent.jar, started by a line
 * in the game's launch settings, see docs/USER_GUIDE.md). It asks once whether the agent is there; only then is
 * {@link AgentLink}, which uses the agent's classes, ever touched. Without the agent every call here does
 * nothing, or just runs what it is given.
 */
final class AgentGate {
    private AgentGate() {}

    private static int state; // 0: not asked yet, 1: the agent is there, 2: it isn't
    private static boolean failed;

    static boolean present() {
        if (state == 0) {
            try {
                Class.forName("wingman.agent.Hooks");
                // The classes alone can be there without the agent running (WingmanAgent.jar on the class path for
                // wingman.agent.Launch, which then failed): only an agent that started sets this.
                String prop = null;
                try { prop = System.getProperty("wingman.agent"); } catch (Throwable t) { /* as unset */ }
                state = prop == null ? 2 : 1;
            } catch (Throwable t) {
                state = 2;
            }
        }
        return state == 1 && !failed;
    }

    /** What the log says about the agent when it isn't in use. */
    static String absence() {
        String prop = null;
        try { prop = System.getProperty("wingman.agent"); } catch (Throwable t) { /* not allowed: as if unset */ }
        if ("off".equals(prop)) return "Wingman agent: started with the 'off' option, so it changes nothing";
        if (prop != null) return "Wingman agent: started, but Wingman's code can't reach it (a mod that loads mods' jars its own way?); "
                + "mods' player-ship scripts see only this PC's own player. Please report this with your mod list";
        return "Wingman agent: not started (optional; the -javaagent setting, or wingman.agent.Launch, isn't in the game's launch settings), "
                + "mods' player-ship scripts see only this PC's own player";
    }

    /** One line for the log when the game starts. */
    static String startupLine() {
        String prop = null;
        try { prop = System.getProperty("wingman.agent"); } catch (Throwable t) { /* as if unset */ }
        if (present() && !"off".equals(prop)) return "Wingman agent: running";
        return absence();
    }

    /** At the start of a battle this PC hosts: the agent's hooks answer for joiners' ships from now on. */
    static void start(WingmanHostPlugin host) {
        if (!present()) {
            Global.getLogger(AgentGate.class).info(absence());
            return;
        }
        try {
            AgentLink.install(host);
            Global.getLogger(AgentGate.class).info("Wingman agent: on. " + AgentLink.report());
        } catch (Throwable t) {
            failed = true;
            Global.getLogger(AgentGate.class).warn("Wingman agent: could not be used (a different version of it?); co-op goes on without it", t);
        }
    }

    /** At the start of a battle this PC joins: the agent's hooks answer for the ships other people fly. */
    static void startJoiner(WingmanClientPlugin joiner) {
        if (!present()) {
            Global.getLogger(AgentGate.class).info(absence());
            return;
        }
        try {
            JoinerAgentLink.install(joiner);
            Global.getLogger(AgentGate.class).info("Wingman agent: on (joiner). " + AgentLink.report());
        } catch (Throwable t) {
            failed = true;
            Global.getLogger(AgentGate.class).warn("Wingman agent: could not be used (a different version of it?); co-op goes on without it", t);
        }
    }

    /** Co-op stopped in this battle: the hooks give the real answers again. */
    static void stop() {
        if (!present()) return;
        try { AgentLink.uninstall(); } catch (Throwable t) { failed = true; }
    }

    /** Run something as part of this ship's frame (a joiner's ability fired by Wingman itself). */
    static void runAs(ShipAPI ship, Runnable r) {
        if (present()) {
            final boolean[] started = new boolean[1];
            final Runnable inner = r;
            try {
                AgentLink.runAs(ship, new Runnable() {
                    public void run() { started[0] = true; inner.run(); }
                });
                return;
            } catch (LinkageError e) {
                if (started[0]) throw e; // from what was run, not from the agent: not ours to swallow
                failed = true; // the agent's classes don't match: from now on, without it
            }
        }
        r.run();
    }

    /** One line for the log, or null without the agent. */
    static String report() {
        if (!present()) return null;
        try { return AgentLink.report(); } catch (Throwable t) { return null; }
    }
}

package wingman;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAPI;

import wingman.agent.Bridge;
import wingman.agent.Hooks;

/**
 * Wingman's agent on a joiner's PC (see AgentLink for the host's side). Here the ships other people fly (the
 * host's and the other joiners', marked {@code wingman_remote}) are the acting ships: a mod's script running
 * for one of them sees it as "the player's ship", as it is on its pilot's screen, and aims where that pilot
 * points. It can't move anyone: who flies what is the host's to decide. Only ever loaded through AgentGate.
 */
final class JoinerAgentLink implements Bridge {
    private final WingmanClientPlugin joiner;
    private int movesIgnored;

    private JoinerAgentLink(WingmanClientPlugin joiner) { this.joiner = joiner; }

    static void install(WingmanClientPlugin joiner) {
        Hooks.setBridge(new JoinerAgentLink(joiner));
        logged.clear();
        try {
            Hooks.guardScripts(true);
        } catch (LinkageError e) { // an older agent than this Wingman: everything else still works
            Global.getLogger(JoinerAgentLink.class).info("Wingman agent: too old to catch mods' scripts failing here; restart the game after updating");
        }
    }

    private static final java.util.Set<String> logged = new java.util.HashSet<String>();
    private static long seen;

    /**
     * Mods' scripts the agent caught failing on this PC's copy of the battle (see Hooks.frameFailed): each is put
     * in the log once, with its error, the first time. Cheap when there is nothing new; called every frame.
     */
    static void reportCaught() {
        long n = wingman.agent.Shared.failedCount;
        if (n == seen) return;
        seen = n;
        java.util.List<Object[]> news = new java.util.ArrayList<Object[]>();
        synchronized (wingman.agent.Shared.failed) {
            for (java.util.Map.Entry<String, Object[]> e : wingman.agent.Shared.failed.entrySet()) {
                if (logged.add(e.getKey())) news.add(new Object[] { e.getKey(), e.getValue()[1] });
            }
        }
        for (Object[] f : news) {
            Global.getLogger(JoinerAgentLink.class).warn("Wingman guard: a mod's script (" + f[0] + ") failed on this PC's copy of the battle; "
                    + "caught, so the game goes on (the host's game, where it runs for real, decides what it does). Noted once", (Throwable) f[1]);
        }
    }

    /** For the end-of-battle line: how often each caught script failed. */
    static String caughtSummary() {
        StringBuilder sb = new StringBuilder();
        synchronized (wingman.agent.Shared.failed) {
            for (java.util.Map.Entry<String, Object[]> e : wingman.agent.Shared.failed.entrySet()) {
                sb.append(sb.length() == 0 ? "" : ", ").append(e.getKey()).append(" ").append(((int[]) e.getValue()[0])[0]).append("x");
            }
        }
        return sb.toString();
    }

    public boolean live() { return joiner.agentLive(); }

    public boolean moveRemote(Object from, Object to) {
        if (movesIgnored++ < 5) {
            Global.getLogger(JoinerAgentLink.class).info("Wingman agent: a script here tried to hand " + nameOf(from)
                    + "'s ship over to another; not done here (the host's game does it for them)");
        }
        return false;
    }

    public float[] cursor(Object ship) { return ship instanceof ShipAPI ? joiner.aimOf((ShipAPI) ship) : null; }

    public boolean keyDown(Object ship, int key) { return false; }

    /** Others' autopilot isn't known here; their abilities wait for the host's word anyway (holdOthers). */
    public boolean manual(Object ship) { return true; }

    /** The pilot's name from the ship's mark, for the log. */
    private static String nameOf(Object s) {
        String n = s instanceof ShipAPI ? wingman.api.WingmanAPI.pilotOf((ShipAPI) s) : null;
        return n == null ? "another player" : n;
    }
}

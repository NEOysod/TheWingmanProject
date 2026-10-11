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

    static void install(WingmanClientPlugin joiner) { Hooks.setBridge(new JoinerAgentLink(joiner)); }

    public boolean live() { return joiner.agentLive(); }

    public boolean moveRemote(ShipAPI from, ShipAPI to) {
        if (movesIgnored++ < 5) {
            Global.getLogger(JoinerAgentLink.class).info("Wingman agent: a script here tried to hand " + nameOf(from)
                    + "'s ship over to another; not done here (the host's game does it for them)");
        }
        return false;
    }

    public float[] cursor(ShipAPI ship) { return joiner.aimOf(ship); }

    public boolean keyDown(ShipAPI ship, int key) { return false; }

    /** Others' autopilot isn't known here; their abilities wait for the host's word anyway (holdOthers). */
    public boolean manual(ShipAPI ship) { return true; }

    /** The pilot's name from the ship's mark, for the log. */
    private static String nameOf(ShipAPI s) {
        String n = wingman.api.WingmanAPI.pilotOf(s);
        return n == null ? "another player" : n;
    }
}

package wingman;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAPI;

import wingman.agent.Bridge;
import wingman.agent.Hooks;
import wingman.net.HostSession;

/**
 * Wingman's side of its agent (see wingman.agent.Hooks in agent/src): what the hooks ask about joiners, answered
 * from the host's session. Only ever loaded through {@link AgentGate}, after it has seen that the agent is there.
 *
 * While a joiner's ship's frame runs on the host, a mod's script there sees that ship as "the player's ship".
 * When such a script hands "the player" another ship (UAF's Isenoria passing control to one of its fighters),
 * the joiner is moved into it, with the same checks as when they ask for a ship themselves, and their game
 * is told to follow.
 */
final class AgentLink implements Bridge {
    private final WingmanHostPlugin host;
    private int movesRefused, movesLogged;

    private AgentLink(WingmanHostPlugin host) { this.host = host; }

    static void install(WingmanHostPlugin host) { Hooks.setBridge(new AgentLink(host)); }
    static void uninstall() { Hooks.setBridge(null); }
    static void runAs(ShipAPI ship, Runnable r) { Hooks.runAsPilot(ship, r); }

    private static ShipAPI ship(Object o) { return o instanceof ShipAPI ? (ShipAPI) o : null; }
    static String report() { return Hooks.report(); }
    static Throwable takeLastSet() { return Hooks.takeLastSet(); }
    static void recordLookups(boolean on) { Hooks.setRecording(on); }
    static java.util.Map<String, Integer> takeMisses() { return Hooks.takeMisses(); }
    static long[] polledKeys() { return Hooks.polledKeys(); }

    public boolean live() { return host.agentLive(); }

    private HostSession.Player playerIn(ShipAPI s) {
        HostSession session = host.session();
        if (session == null || s == null) return null;
        for (HostSession.Player p : session.getPlayers()) if (p.ship == s) return p;
        return null;
    }

    public boolean moveRemote(Object fromObj, Object toObj) {
        ShipAPI from = ship(fromObj), to = ship(toObj);
        if (from == null || to == null) return false;
        HostSession.Player p = playerIn(from);
        Integer id = host.idOf(to);
        if (p == null || id == null) return false;
        String why = host.refusal(p, to, host.hostShip());
        if (why != null) {
            if (movesRefused++ < 5) Global.getLogger(AgentLink.class).info("Wingman agent: a script tried to move " + p.name + " into another ship; not done: " + why);
            return false;
        }
        p.claim = id.intValue();
        p.hasClaim = true;
        p.claimByScript = true;
        if (movesLogged++ < 20) Global.getLogger(AgentLink.class).info("Wingman agent: a script on the host moves " + p.name + " from "
                + from.getHullSpec().getHullId() + " into " + to.getHullSpec().getHullId());
        return true;
    }

    public float[] cursor(Object s) {
        HostSession.Player p = playerIn(ship(s));
        return p == null ? null : new float[] { p.input.mouseX, p.input.mouseY };
    }

    public boolean manual(Object s) {
        HostSession.Player p = playerIn(ship(s));
        return p == null || !host.onAutopilot(p.id);
    }

    /**
     * A key a script asks about while it runs for a joiner's ship (a hullmod's ability on a hotkey, say): held if
     * that joiner's game says it is (see InputState.keys; their own copy of the script asked about it there).
     * Never the host's keys. A module or fighter answers for the player of the ship it belongs to.
     */
    public boolean keyDown(Object s, int key) {
        ShipAPI ship = ship(s);
        HostSession.Player p = playerIn(ship);
        if (p == null && ship != null && ship.getParentStation() != null) p = playerIn(ship.getParentStation());
        if (p == null || System.currentTimeMillis() - p.input.receivedAt > 1000) return false;
        // Their key for the same setting, if they have bound it to another (LunaLib keys, see LunaCopy).
        if (!p.input.keyHeld(key) && !LunaCopy.heldByBinding(key, p.lunaKeys, p.input)) return false;
        if (key != lastKeyHeard && keysHeard++ < 10) Global.getLogger(AgentLink.class).info("Wingman agent: " + p.name + " holds "
                + org.lwjgl.input.Keyboard.getKeyName(key) + " for a script on their ship");
        lastKeyHeard = key;
        return true;
    }
    private int keysHeard, lastKeyHeard;
}

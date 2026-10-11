package wingman.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAPI;

import wingman.Hosting;
import wingman.WingmanConfig;
import wingman.net.ClientLink;
import wingman.net.HostSession;
import wingman.net.ModChannel;
import wingman.net.Msg;

/**
 * What other mods may use of Wingman. Everything here is static, safe to call when nobody is connected, and
 * meant for the game thread.
 *
 * <p>A mod that wants to work with or without Wingman lists it in its mod_info.json "dependencies" as optional
 * (or not at all) and calls this class only after checking
 * {@code Global.getSettings().getModManager().isModEnabled("wingman_project")}: the class is then never loaded when
 * Wingman is absent.
 *
 * <p><b>Who flies a ship.</b> In a co-op battle, a ship a joiner flies is an ordinary AI ship on the host and
 * on the other joiners' PCs, and is the player's ship only on that joiner's own PC. Scripts that do something
 * special for {@code engine.getPlayerShip()} (a player-only status display, a hotkey) can use
 * {@link #isFlownRemotely(ShipAPI)} to skip or include such ships. The same is written on the ship itself, so a
 * mod need not call this class at all: custom data {@link #PILOT_KEY} holds the name of the person flying it (on
 * every PC, the host's own ship included), and {@link #REMOTE_KEY} is present when that person is on another PC.
 *
 * <p><b>Messages.</b> Mods can talk to their own copies on the other PCs: the host to every joiner (or one), a
 * joiner to the host. A message is a channel name (use your mod id as a prefix: "mymod:loot") and up to 60 KB.
 * Messages arrive in order, are never lost while the link holds, and are handed to listeners on the game thread
 * by {@link #deliver()}. Wingman calls deliver() every frame in battles and on the title screen; a campaign
 * script calls {@link #pump()} (which delivers too). On the host, Wingman posts {@code "wingman:joined"} and
 * {@code "wingman:left"} when a joiner comes or goes.
 *
 * <p>Joiners can only reach the host; the host can pass things on. A joiner may send at most 100 messages a
 * second; more are dropped on the host.
 */
public final class WingmanAPI {
    private WingmanAPI() {}

    /** Bumped when something here changes in a way a caller might need to know about. */
    public static final int API_VERSION = 1;
    /** Ship custom data: the name of the person flying the ship (String). */
    public static final String PILOT_KEY = "wingman_pilot";
    /** Ship custom data: present (Boolean.TRUE) when the person flying the ship is on another PC. */
    public static final String REMOTE_KEY = "wingman_remote";
    /** Channels Wingman posts on (host only): a joiner came in, or left. The message is empty. */
    public static final String JOINED = ModChannel.JOINED, LEFT = ModChannel.LEFT;
    /** The id the host has in {@link Listener#onMessage}: joiners hear everything from the host. */
    public static final int HOST = ModChannel.HOST_ID;
    /** Largest message, in bytes. */
    public static final int MAX_BYTES = ModChannel.MAX_BYTES;

    public interface Listener {
        /** @param from the sender's player name; @param playerId their id (HOST for the host), for sendTo. */
        void onMessage(String from, int playerId, byte[] data);
    }

    // ---- who is who ----

    /** Wingman's build, e.g. "0.6.5-beta 2026-10-11T10:00Z". */
    public static String version() { return Hosting.buildId(); }

    /** This PC has a host session open (players may connect). */
    public static boolean isHosting() { return HostSession.active() != null; }

    /** This PC is connected to a host as a joiner. */
    public static boolean isJoined() { return ClientLink.get().getState() == ClientLink.State.CONNECTED; }

    /** The name this player goes by (from Wingman's settings). */
    public static String localName() { return config().playerName; }

    /** On a joiner: the host's name. On the host: this player's own name. */
    public static String hostName() { return isJoined() ? ClientLink.get().hostName : localName(); }

    /** On the host: the ids of the joiners connected now (see {@link #playerName(int)}). Empty elsewhere. */
    public static List<Integer> players() {
        HostSession s = HostSession.active();
        if (s == null) return Collections.emptyList();
        List<Integer> out = new ArrayList<Integer>();
        for (HostSession.Player p : s.getPlayers()) out.add(p.id);
        return out;
    }

    /** On the host: a connected joiner's name, or null. A joiner who reconnects keeps the name, not the id. */
    public static String playerName(int playerId) {
        HostSession s = HostSession.active();
        if (s == null) return null;
        for (HostSession.Player p : s.getPlayers()) if (p.id == playerId) return p.name;
        return null;
    }

    // ---- ships ----

    /** The name of the person flying this ship, or null if the AI flies it (or the battle isn't co-op). */
    public static String pilotOf(ShipAPI ship) {
        if (ship == null) return null;
        Object o = ship.getCustomData().get(PILOT_KEY);
        return o instanceof String ? (String) o : null;
    }

    /** A person flies this ship, here or on another PC. */
    public static boolean isFlownByPlayer(ShipAPI ship) { return pilotOf(ship) != null; }

    /** A person on another PC flies this ship (on the host: a joiner's ship; on a joiner: the host's or another joiner's). */
    public static boolean isFlownRemotely(ShipAPI ship) {
        return ship != null && ship.getCustomData().containsKey(REMOTE_KEY);
    }

    // ---- ships kept for players ----

    private static final Map<String, String> reserved = new ConcurrentHashMap<String, String>();

    /**
     * On the host: the ship (by fleet member id) this player is to be seated in when they come into a battle and
     * have no ship yet. Once per battle: if they change ship, they keep their choice. The usual rules apply (not
     * the host's ship, not one someone else flies, the host's size limit). Matched by name, ignoring case; null
     * memberId clears it. Kept until the game is closed; a campaign mod sets it again on load.
     */
    public static void reserveShip(String playerName, String fleetMemberId) {
        if (playerName == null) return;
        String key = playerName.trim().toLowerCase();
        if (fleetMemberId == null) reserved.remove(key); else reserved.put(key, fleetMemberId);
    }

    /** The fleet member id kept for this player, or null. */
    public static String reservedShip(String playerName) {
        return playerName == null ? null : reserved.get(playerName.trim().toLowerCase());
    }

    // ---- hosting outside a battle ----

    /**
     * Open the host's session now, with the settings in Wingman's panel (port, password, mod check), so friends
     * can connect before a battle. Battles then use the same session, as usual. Returns false if hosting is
     * switched off in the settings or the port could not be opened (the reason is logged).
     */
    public static boolean openHost() {
        if (isHosting()) return true;
        WingmanConfig cfg = config();
        if (!cfg.hostEnabled) return false;
        try {
            Hosting.open(cfg);
            return true;
        } catch (Throwable t) {
            Global.getLogger(WingmanAPI.class).warn("Wingman API: could not open the host session on port " + cfg.port, t);
            return false;
        }
    }

    /**
     * On the host, outside a battle (from a campaign script, every frame or so): let players connect and leave,
     * and hand waiting messages to listeners. Returns lines worth showing the host ("Ann joined (2/4)."). In a
     * battle Wingman does this itself and this only delivers messages.
     */
    public static List<String> pump() {
        List<String> lines = Collections.emptyList();
        HostSession s = HostSession.active();
        if (s != null && Global.getCurrentState() != GameState.COMBAT) lines = s.pump();
        deliver();
        return lines;
    }

    // ---- messages ----

    private static final Map<String, List<Listener>> listeners = new ConcurrentHashMap<String, List<Listener>>();
    private static long joinerWindow;
    private static int joinerSent;
    private static boolean listenerWarned;

    public static void listen(String channel, Listener l) {
        if (!ModChannel.validChannel(channel) || l == null) throw new IllegalArgumentException("bad channel or listener");
        List<Listener> list = listeners.get(channel);
        if (list == null) { list = new CopyOnWriteArrayList<Listener>(); listeners.put(channel, list); }
        if (!list.contains(l)) list.add(l);
    }

    public static void unlisten(String channel, Listener l) {
        List<Listener> list = listeners.get(channel);
        if (list != null) list.remove(l);
    }

    /**
     * On the host: to every connected joiner. On a joiner: to the host. Returns false if there is nobody to send
     * to, or (on a joiner) more than 100 messages went this second. Throws IllegalArgumentException for a
     * channel over 64 characters or a message over 60 KB.
     */
    public static boolean send(String channel, byte[] data) {
        byte[] m = ModChannel.encode(channel, data);
        HostSession s = HostSession.active();
        if (s != null) {
            if (s.getPlayers().isEmpty()) return false;
            for (HostSession.Player p : s.getPlayers()) p.conn.send(Msg.MOD, m);
            return true;
        }
        if (!isJoined()) return false;
        long now = System.currentTimeMillis();
        if (now - joinerWindow > 1000) { joinerWindow = now; joinerSent = 0; }
        if (++joinerSent > ModChannel.PER_PLAYER_PER_SECOND) return false;
        wingman.net.Conn c = ClientLink.get().getConn();
        if (c == null) return false;
        c.send(Msg.MOD, m);
        return true;
    }

    /** On the host: to one joiner. Returns false if that player isn't connected (or this PC isn't hosting). */
    public static boolean sendTo(int playerId, String channel, byte[] data) {
        byte[] m = ModChannel.encode(channel, data);
        HostSession s = HostSession.active();
        return s != null && s.sendTo(playerId, Msg.MOD, m);
    }

    /** Hand messages that have arrived to their listeners. Wingman calls this every frame; calling it more is harmless. */
    public static void deliver() {
        ModChannel.Message m;
        int n = 0;
        while (n++ < 500 && (m = ModChannel.poll()) != null) {
            List<Listener> list = listeners.get(m.channel);
            if (list == null) continue;
            for (Listener l : list) {
                try {
                    l.onMessage(m.from, m.playerId, m.data);
                } catch (Throwable t) { // one mod's mistake must not stop the others, or co-op
                    if (!listenerWarned) {
                        listenerWarned = true;
                        Global.getLogger(WingmanAPI.class).warn("Wingman API: a listener on \"" + m.channel + "\" failed (further failures not logged)", t);
                    }
                }
            }
        }
    }

    private static WingmanConfig cfg;
    private static long cfgAt;

    /** Wingman's settings, read again at most every few seconds. */
    private static WingmanConfig config() {
        long now = System.currentTimeMillis();
        if (cfg == null || now - cfgAt > 5000) { cfg = WingmanConfig.load(); cfgAt = now; }
        return cfg;
    }
}

package wingman;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;

import wingman.api.WingmanAPI;
import wingman.compat.Exotica;
import wingman.net.HostSession;
import wingman.host.CampaignLobby;
import wingman.host.FriendRefit;

/**
 * Exotica Technologies' menu in the friend refit: the messages between host and joiner (see wingman.compat.Exotica).
 *
 * Joiner: EXOTICA asks the host for the ship; its answer opens Exotica's menu on a mirror; closing the menu sends what was
 * bought. Host: answers an ask with the ship, and plays a joiner's purchases back through Exotica's own methods when the
 * host is at a market with no core screen open. The joiner only says what it wants: the host checks and charges.
 */
public final class ExoticaRefit {
    private ExoticaRefit() {}

    private static boolean registered;
    private static WingmanConfig lastCfg;

    // ---- host ----

    private static final int MAX_QUEUE = 8;

    private static final class Entry {
        final int playerId;
        final String playerName, memberId;
        final List<Exotica.Step> steps;
        Entry(int playerId, String playerName, String memberId, List<Exotica.Step> steps) {
            this.playerId = playerId; this.playerName = playerName; this.memberId = memberId; this.steps = steps;
        }
    }

    private static final List<Entry> queue = new ArrayList<Entry>();

    static void register() {
        if (registered) return;
        registered = true;
        try {
            WingmanAPI.listen(Exotica.CH_ASK, new WingmanAPI.Listener() {
                public void onMessage(String from, int playerId, byte[] data) { guard(1, from, playerId, data); }
            });
            WingmanAPI.listen(Exotica.CH_SET, new WingmanAPI.Listener() {
                public void onMessage(String from, int playerId, byte[] data) { guard(2, from, playerId, data); }
            });
            WingmanAPI.listen(Exotica.CH_SHIP, new WingmanAPI.Listener() {
                public void onMessage(String from, int playerId, byte[] data) { guard(3, from, playerId, data); }
            });
            WingmanAPI.listen(Exotica.CH_RESULT, new WingmanAPI.Listener() {
                public void onMessage(String from, int playerId, byte[] data) { guard(4, from, playerId, data); }
            });
        } catch (Throwable t) {
            Global.getLogger(ExoticaRefit.class).warn("Wingman: Exotica messages could not be listened for", t);
        }
    }

    private static void guard(int which, String from, int playerId, byte[] data) {
        try {
            if (which == 1 && WingmanAPI.isHosting()) onAsk(playerId, data);
            else if (which == 2 && WingmanAPI.isHosting()) onSet(from, playerId, data);
            else if (which == 3 && !WingmanAPI.isHosting()) onShip(data);
            else if (which == 4 && !WingmanAPI.isHosting()) onResult(data);
        } catch (IOException e) {
            Global.getLogger(ExoticaRefit.class).info("Wingman: a bad Exotica message from " + from + " was dropped: " + e.getMessage());
        } catch (Throwable t) {
            Global.getLogger(ExoticaRefit.class).warn("Wingman: Exotica message failed", t);
        }
    }

    private static void reply(int playerId, String memberId, boolean ok, String text) {
        try {
            WingmanAPI.sendTo(playerId, Exotica.CH_RESULT, Exotica.encodeResult(new Exotica.ResultMsg(memberId, ok, text)));
        } catch (Throwable t) { /* the player is gone */ }
    }

    private static void onAsk(int playerId, byte[] data) throws IOException {
        String id = Exotica.decodeAsk(data);
        HostSession s = HostSession.active();
        HostSession.Player p = s == null ? null : FriendRefit.playerFor(s, playerId);
        if (p == null) return;
        WingmanConfig cfg = lastCfg;
        if (cfg == null || !cfg.friendRefit) { reply(playerId, id, false, "The host has friend refit turned off."); return; }
        if (!Exotica.available()) { reply(playerId, id, false, "Exotica Technologies isn't usable on the host."); return; }
        MarketAPI market = FriendRefit.dockedMarket();
        if (market == null) { reply(playerId, id, false, "The host isn't docked at a market."); return; }
        String why = FriendRefit.refusalFor(id, cfg);
        if (why != null) { reply(playerId, id, false, why); return; }
        FleetMemberAPI m = FriendRefit.memberFor(id);
        if (m == null) { reply(playerId, id, false, "That ship isn't in the host's fleet."); return; }
        try {
            WingmanAPI.sendTo(playerId, Exotica.CH_SHIP, wingman.compat.ModCompat.exoticaShip(m, market));
        } catch (IOException e) {
            reply(playerId, id, false, "That ship has too much on it for Exotica's menu here.");
        } catch (Throwable t) {
            Global.getLogger(ExoticaRefit.class).warn("Wingman: Exotica ship could not be built", t);
            reply(playerId, id, false, "The host couldn't read that ship (see its log).");
        }
    }

    private static void onSet(String from, int playerId, byte[] data) throws IOException {
        Exotica.SetMsg m = Exotica.decodeSet(data);
        if (m.steps.isEmpty()) return;
        for (int i = queue.size() - 1; i >= 0; i--) {
            Entry e = queue.get(i);
            if (e.playerId == playerId && e.memberId.equals(m.memberId)) queue.remove(i); // the newer one replaces it
        }
        if (queue.size() >= MAX_QUEUE) { reply(playerId, m.memberId, false, "The host has too many Exotica changes waiting."); return; }
        queue.add(new Entry(playerId, from, m.memberId, m.steps));
    }

    public static void clear() { queue.clear(); }

    /** Host, every frame: plays back waiting purchases when no core screen is open. */
    public static void advance(HostSession s, WingmanConfig cfg) {
        lastCfg = cfg;
        if (queue.isEmpty()) return;
        try {
            if (Global.getSector().getCampaignUI().getCurrentCoreTab() != null) return; // the screen holds its own copy of the ship: wait
            List<Entry> now = new ArrayList<Entry>(queue);
            queue.clear();
            for (Entry e : now) play(s, cfg, e);
        } catch (Throwable t) {
            Global.getLogger(ExoticaRefit.class).warn("Wingman: Exotica changes could not be played back", t);
            queue.clear();
        }
    }

    private static void play(HostSession s, WingmanConfig cfg, Entry e) {
        HostSession.Player p = FriendRefit.playerFor(s, e.playerId);
        if (p == null) return;
        MarketAPI market = FriendRefit.dockedMarket();
        if (market == null || !cfg.friendRefit) { reply(e.playerId, e.memberId, false, "The host isn't docked at a market."); return; }
        String why = FriendRefit.refusalFor(e.memberId, cfg);
        if (why != null) { reply(e.playerId, e.memberId, false, why); return; }
        FleetMemberAPI m = FriendRefit.memberFor(e.memberId);
        if (m == null) { reply(e.playerId, e.memberId, false, "That ship isn't in the host's fleet."); return; }
        try {
            Exotica.Outcome o = wingman.compat.ModCompat.exoticaReplay(m, market, e.steps);
            CampaignLobby.forgetLoadout(e.memberId);
            String ship = m.getShipName();
            if (o.applied > 0) CampaignLobby.say(e.playerName + " Exotica: " + o.applied + " change(s) on " + ship);
            String text = o.applied + " change(s) done." + (o.failure == null ? "" : " Stopped at " + o.failure + ".");
            reply(e.playerId, e.memberId, o.failure == null, text);
        } catch (Throwable t) {
            Global.getLogger(ExoticaRefit.class).warn("Wingman: Exotica replay failed", t);
            reply(e.playerId, e.memberId, false, "host error");
        }
    }

    // ---- joiner ----

    private static String askedMember = "";
    private static long askedAt;
    private static String note = "";
    private static boolean noteOk;

    /** A line for the joiner's screen, once; null if none. */
    static String takeNote() {
        if (note.isEmpty()) return null;
        String n = note;
        note = "";
        return n;
    }

    static boolean noteIsGood() { return noteOk; }

    private static void setNote(String text, boolean ok) { note = text; noteOk = ok; }

    static boolean isOpen() { return wingman.compat.ModCompat.exoticaActive(); }

    /** The EXOTICA button: ask the host for the ship. One ask at a time. */
    static void ask(String memberId) {
        if (isOpen()) return;
        if (!askedMember.isEmpty() && System.currentTimeMillis() - askedAt < 15000L) return;
        try {
            byte[] b = Exotica.encodeAsk(memberId);
            if (!WingmanAPI.send(Exotica.CH_ASK, b)) { setNote("Exotica: not connected.", false); return; }
            askedMember = memberId;
            askedAt = System.currentTimeMillis();
            setNote("Exotica: asking host...", true);
        } catch (Throwable t) {
            setNote("Exotica: couldn't ask the host.", false);
        }
    }

    private static void onShip(byte[] data) throws IOException {
        Exotica.ShipMsg m = Exotica.decodeShip(data);
        if (askedMember.isEmpty() || !askedMember.equals(m.memberId)) return;
        askedMember = "";
        String err = wingman.compat.ModCompat.exoticaOpen(m);
        if (err != null) setNote("Exotica: " + err, false);
        else setNote("", true);
    }

    private static void onResult(byte[] data) throws IOException {
        Exotica.ResultMsg r = Exotica.decodeResult(data);
        if (r.memberId.equals(askedMember)) askedMember = "";
        setNote("Exotica: " + r.text, r.ok);
    }

    /** Every frame on the joiner: a menu that failed is gone; an ask that was never answered is given up. */
    static boolean frame(float dt) {
        if (!askedMember.isEmpty() && System.currentTimeMillis() - askedAt >= 15000L) {
            askedMember = "";
            setNote("Exotica: the host didn't answer.", false);
        }
        if (!isOpen()) return false;
        if (!wingman.compat.ModCompat.exoticaFrame(dt)) {
            setNote("Exotica menu failed (see log)", false);
            return false;
        }
        return true;
    }

    static void input(List<com.fs.starfarer.api.input.InputEventAPI> events) {
        if (isOpen()) wingman.compat.ModCompat.exoticaInput(events);
    }

    static void render() {
        if (isOpen()) wingman.compat.ModCompat.exoticaRender();
    }

    /** Closes the menu; sends what was bought when asked to and still connected. Always takes the mirror down. */
    static void close(boolean send) {
        askedMember = "";
        if (!isOpen()) return;
        Exotica.Closed c = null;
        try {
            c = wingman.compat.ModCompat.exoticaClose();
        } catch (Throwable t) {
            Global.getLogger(ExoticaRefit.class).warn("Wingman: Exotica menu close failed", t);
        }
        if (c == null) return;
        if (!send || c.steps.isEmpty()) return;
        try {
            Exotica.SetMsg m = new Exotica.SetMsg();
            m.memberId = c.memberId;
            m.steps.addAll(c.steps);
            if (WingmanAPI.isJoined() && WingmanAPI.send(Exotica.CH_SET, Exotica.encodeSet(m)))
                setNote("Exotica: sent " + c.steps.size() + " change(s)" + (c.full ? " (too many changes, close and reopen)" : ""), true);
            else setNote("Exotica: couldn't send the changes.", false);
        } catch (Throwable t) {
            setNote("Exotica: couldn't send the changes.", false);
        }
    }
}

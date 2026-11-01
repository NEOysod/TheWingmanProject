package wingman;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.OfficerDataAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;

import wingman.api.WingmanAPI;
import wingman.compat.Bionics;
import wingman.host.CampaignLobby;
import wingman.host.FriendRefit;
import wingman.net.HostSession;

/**
 * Bionic Alteration's workshop in the co-op lobby: the messages between host and joiner (see wingman.compat.Bionics).
 *
 * Joiner: BIONICS asks the host for their linked captain; its answer opens the mod's own workshop on a mirror;
 * closing the workshop sends what was changed. Host: answers an ask with the captain, and plays a joiner's changes
 * back through the mod's own install/remove methods when no battle and no core screen is open. The joiner only says
 * what it wants: the host checks the limits and charges the items.
 */
public final class BionicsRefit {
    private BionicsRefit() {}

    private static boolean registered;

    // ---- host ----

    private static final int MAX_QUEUE = 8;

    private static final class Entry {
        final int playerId;
        final String playerName, memberId, officerId;
        final long revision;
        final List<Bionics.Step> steps;
        Entry(int playerId, String playerName, String memberId, String officerId, long revision, List<Bionics.Step> steps) {
            this.playerId = playerId; this.playerName = playerName; this.memberId = memberId;
            this.officerId = officerId; this.revision = revision; this.steps = steps;
        }
    }

    private static final List<Entry> queue = new ArrayList<Entry>();

    static void register() {
        if (registered) return;
        registered = true;
        try {
            WingmanAPI.listen(Bionics.CH_ASK, new WingmanAPI.Listener() {
                public void onMessage(String from, int playerId, byte[] data) { guard(1, from, playerId, data); }
            });
            WingmanAPI.listen(Bionics.CH_SET, new WingmanAPI.Listener() {
                public void onMessage(String from, int playerId, byte[] data) { guard(2, from, playerId, data); }
            });
            WingmanAPI.listen(Bionics.CH_SHIP, new WingmanAPI.Listener() {
                public void onMessage(String from, int playerId, byte[] data) { guard(3, from, playerId, data); }
            });
            WingmanAPI.listen(Bionics.CH_RESULT, new WingmanAPI.Listener() {
                public void onMessage(String from, int playerId, byte[] data) { guard(4, from, playerId, data); }
            });
        } catch (Throwable t) {
            Global.getLogger(BionicsRefit.class).warn("Wingman: bionic messages could not be listened for", t);
        }
    }

    private static void guard(int which, String from, int playerId, byte[] data) {
        try {
            if (which == 1 && WingmanAPI.isHosting()) onAsk(playerId, data);
            else if (which == 2 && WingmanAPI.isHosting()) onSet(from, playerId, data);
            else if (which == 3 && !WingmanAPI.isHosting()) onShip(data);
            else if (which == 4 && !WingmanAPI.isHosting()) onResult(data);
        } catch (IOException e) {
            Global.getLogger(BionicsRefit.class).info("Wingman: a bad bionic message from " + from + " was dropped: " + e.getMessage());
        } catch (Throwable t) {
            Global.getLogger(BionicsRefit.class).warn("Wingman: bionic message failed", t);
        }
    }

    private static void reply(int playerId, String officerId, boolean ok, String text) {
        try {
            WingmanAPI.sendTo(playerId, Bionics.CH_RESULT, Bionics.encodeResult(new Bionics.ResultMsg(officerId, ok, text)));
        } catch (Throwable t) { /* the player is gone */ }
    }

    /** The captain this player may change: their own linked officer, rooted at the member they picked it on. */
    private static PersonAPI officer(HostSession.Player p, String memberId, String officerId) {
        if (p == null || memberId == null || officerId == null) return null;
        FleetMemberAPI member = FriendRefit.memberFor(memberId);
        if (member == null) return null;
        PersonAPI person = member.getCaptain();
        if (person == null || person.isDefault() || person.isPlayer() || person.isAICore()) return null;
        if (!officerId.equals(person.getId())) return null;
        if (com.fs.starfarer.api.util.Misc.isUnremovable(person)) return null;
        String linked = OfficerPlayerBinding.boundPlayer(person);
        if (linked == null || linked.isEmpty() || !linked.equals(OfficerPlayerBinding.identity(p.name))) return null;
        int bound = 0, same = 0;
        try {
            for (OfficerDataAPI officer : Global.getSector().getPlayerFleet().getFleetData().getOfficersCopy()) {
                PersonAPI q = officer.getPerson();
                if (q == person) same++;
                if (q != null && linked.equals(OfficerPlayerBinding.boundPlayer(q))) bound++;
            }
        } catch (Throwable t) {
            return null;
        }
        if (bound != 1 || same != 1) return null; // never steal, never guess
        return person;
    }

    private static void onAsk(int playerId, byte[] data) throws IOException {
        String[] ask = Bionics.decodeAsk(data);
        HostSession s = HostSession.active();
        HostSession.Player p = s == null ? null : FriendRefit.playerFor(s, playerId);
        if (p == null) return;
        String officerId = ask[1];
        if (!Bionics.available()) { reply(playerId, officerId, false, "Bionic Alteration isn't usable on the host."); return; }
        PersonAPI person = officer(p, ask[0], officerId);
        if (person == null) { reply(playerId, officerId, false, "That captain isn't yours to change."); return; }
        try {
            WingmanAPI.sendTo(playerId, Bionics.CH_SHIP, wingman.compat.ModCompat.bionicsShip(person));
        } catch (IOException e) {
            reply(playerId, officerId, false, "That captain has too much on them for this menu.");
        } catch (Throwable t) {
            Global.getLogger(BionicsRefit.class).warn("Wingman: a captain's bionics could not be read", t);
            reply(playerId, officerId, false, "The host couldn't read that captain (see its log).");
        }
    }

    private static void onSet(String from, int playerId, byte[] data) throws IOException {
        Bionics.SetMsg m = Bionics.decodeSet(data);
        if (m.steps.isEmpty()) return;
        String memberId = memberFor(playerId, m.officerId);
        for (int i = queue.size() - 1; i >= 0; i--) {
            Entry e = queue.get(i);
            if (e.playerId == playerId && e.officerId.equals(m.officerId)) queue.remove(i); // the newer one replaces it
        }
        if (queue.size() >= MAX_QUEUE) { reply(playerId, m.officerId, false, "The host has too many bionic changes waiting."); return; }
        queue.add(new Entry(playerId, from, memberId, m.officerId, m.revision, m.steps));
    }

    /** The host's member whose captain has that id, for the later checks (empty when the fleet changed). */
    private static String memberFor(int playerId, String officerId) {
        HostSession s = HostSession.active();
        HostSession.Player p = s == null ? null : FriendRefit.playerFor(s, playerId);
        if (p == null) return "";
        try {
            for (FleetMemberAPI m : Global.getSector().getPlayerFleet().getFleetData().getMembersListCopy()) {
                if (m.getCaptain() != null && officerId.equals(m.getCaptain().getId())) return m.getId();
            }
        } catch (Throwable t) { /* the fleet is gone */ }
        return "";
    }

    /** The host went into a battle: waiting changes are dropped, and their players told. */
    public static void clear() {
        for (Entry e : queue)
            reply(e.playerId, e.officerId, false, "The host went into a battle before your bionic changes were made. Nothing changed; make them again after the battle.");
        queue.clear();
    }

    /** Host, every frame: plays back waiting changes when no battle, no core screen and no dialog is open. */
    public static void advance(HostSession s) {
        if (queue.isEmpty()) return;
        try {
            if (s.isBattleOpen()) return; // a captain still fighting: wait
            if (Global.getSector().getCampaignUI().getCurrentCoreTab() != null) return; // the core screen is the host's: wait
            if (Global.getSector().getCampaignUI().isShowingDialog()) return; // the mod's own workshop is a dialog: never change a captain under it
            List<Entry> now = new ArrayList<Entry>(queue);
            queue.clear();
            for (Entry e : now) play(s, e);
        } catch (Throwable t) {
            Global.getLogger(BionicsRefit.class).warn("Wingman: bionic changes could not be played back", t);
            queue.clear();
        }
    }

    private static void play(HostSession s, Entry e) {
        HostSession.Player p = FriendRefit.playerFor(s, e.playerId);
        if (p == null) return;
        PersonAPI person = officer(p, e.memberId, e.officerId);
        if (person == null) { reply(e.playerId, e.officerId, false, "That captain isn't yours to change any more."); return; }
        try {
            Bionics.Outcome o = wingman.compat.ModCompat.bionicsReplay(person, e.revision, e.steps);
            CampaignLobby.forgetLoadout(e.memberId);
            String name = person.getName() == null ? "your captain" : person.getName().getFullName();
            if (o.applied > 0) CampaignLobby.say(e.playerName + " bionics: " + o.applied + " change(s) on " + name);
            String text = o.applied + " change(s) on " + name + "." + (o.failure == null ? "" : " Stopped at " + o.failure + ".");
            reply(e.playerId, e.officerId, o.failure == null, text);
        } catch (Throwable t) {
            Global.getLogger(BionicsRefit.class).warn("Wingman: bionic replay failed", t);
            reply(e.playerId, e.officerId, false, "host error");
        }
    }

    // ---- joiner ----

    private static String askedOfficer = "";
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

    static boolean isOpen() { return wingman.compat.ModCompat.bionicsActive(); }

    /** The BIONICS button: ask the host for the linked captain. One ask at a time. */
    static void ask(String memberId, String officerId) {
        if (isOpen() || officerId.isEmpty()) return;
        if (!askedOfficer.isEmpty() && System.currentTimeMillis() - askedAt < 15000L) return;
        if (!wingman.compat.ModCompat.bionicsEnabled()) { setNote("Bionics: Bionic Alteration isn't usable here.", false); return; }
        try {
            byte[] b = Bionics.encodeAsk(memberId, officerId);
            if (!WingmanAPI.send(Bionics.CH_ASK, b)) { setNote("Bionics: not connected.", false); return; }
            askedOfficer = officerId;
            askedAt = System.currentTimeMillis();
            setNote("Bionics: asking the host...", true);
        } catch (Throwable t) {
            setNote("Bionics: couldn't ask the host.", false);
        }
    }

    private static void onShip(byte[] data) throws IOException {
        Bionics.ShipMsg m = Bionics.decodeShip(data);
        if (askedOfficer.isEmpty() || !askedOfficer.equals(m.officerId)) return;
        askedOfficer = "";
        String err = wingman.compat.ModCompat.bionicsOpen(m);
        if (err != null) setNote("Bionics: " + err, false);
        else setNote("", true);
    }

    private static void onResult(byte[] data) throws IOException {
        Bionics.ResultMsg r = Bionics.decodeResult(data);
        if (r.officerId.equals(askedOfficer)) askedOfficer = "";
        setNote("Bionics: " + r.text, r.ok);
    }

    /** Every frame on the joiner: a workshop that failed is gone; an ask that was never answered is given up. */
    static boolean frame(float dt) {
        if (!askedOfficer.isEmpty() && System.currentTimeMillis() - askedAt >= 15000L) {
            askedOfficer = "";
            setNote("Bionics: the host didn't answer.", false);
        }
        if (!isOpen()) return false;
        if (!wingman.compat.ModCompat.bionicsFrame(dt)) {
            setNote("Bionics menu failed (see log)", false);
            return false;
        }
        return true;
    }

    static void input(List<com.fs.starfarer.api.input.InputEventAPI> events) {
        if (isOpen()) wingman.compat.ModCompat.bionicsInput(events);
    }

    static void render() {
        if (isOpen()) wingman.compat.ModCompat.bionicsRender();
    }

    /** Closes the workshop; sends what was changed when asked to and still connected. Always takes the mirror down. */
    static void close(boolean send) {
        askedOfficer = "";
        if (!isOpen()) return;
        Bionics.Closed c = null;
        try {
            c = wingman.compat.ModCompat.bionicsClose();
        } catch (Throwable t) {
            Global.getLogger(BionicsRefit.class).warn("Wingman: bionics menu close failed", t);
        }
        if (c == null) return;
        if (send && c.full) { setNote("Bionics: too many changes for one go (" + c.steps.size() + "). Nothing was sent; make fewer at a time.", false); return; }
        if (!send || c.steps.isEmpty()) return;
        try {
            Bionics.SetMsg m = new Bionics.SetMsg();
            m.officerId = c.officerId;
            m.revision = c.revision;
            m.steps.addAll(c.steps);
            if (WingmanAPI.isJoined() && WingmanAPI.send(Bionics.CH_SET, Bionics.encodeSet(m)))
                setNote("Bionics: sent " + c.steps.size() + " change(s)", true);
            else setNote("Bionics: couldn't send the changes.", false);
        } catch (Throwable t) {
            setNote("Bionics: couldn't send the changes.", false);
        }
    }
}
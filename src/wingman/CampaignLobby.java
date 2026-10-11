package wingman;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;

import wingman.api.WingmanAPI;
import wingman.net.HostSession;
import wingman.net.Msg;
import wingman.net.Roster;

/**
 * The pre-battle lobby, host side. With "Friends can connect on the campaign map" on, the co-op session opens
 * as soon as a save is loaded, so friends connect while the host is still on the map. Each of them is sent the
 * host's fleet (Msg.ROSTER) and may pick the ship they want to fly (Msg.PICK). A pick is kept as a reservation
 * (WingmanAPI.reserveShip): when the next battle starts and that ship is deployed, its picker is seated in it.
 *
 * Transient (never written into a save). Runs while the campaign is paused too, so friends can connect while
 * the host is in a menu or a dialog that pauses the map.
 */
final class CampaignLobby implements EveryFrameScript {
    private static final Color NOTE = new Color(120, 210, 255);
    private WingmanConfig cfg;
    private boolean broken, opened, openFailed;
    private long lastSent, cfgAt;
    private String lastSentKey = "";

    public boolean isDone() { return false; }
    public boolean runWhilePaused() { return true; }

    public void advance(float amount) {
        if (broken || Global.getCurrentState() != GameState.CAMPAIGN) return;
        try {
            long now = System.currentTimeMillis();
            if (cfg == null || now - cfgAt > 5000) { cfg = WingmanConfig.load(); cfgAt = now; }
            HostSession s = HostSession.active();
            if (s == null) {
                if (!cfg.hostEnabled || !cfg.campaignLobby || openFailed) return;
                try {
                    s = Hosting.open(cfg);
                    if (!opened) say("Co-op: friends can connect now, see your fleet and pick their ships (port " + cfg.port + ").");
                    opened = true;
                } catch (Throwable t) {
                    openFailed = true; // asked again next load
                    Global.getLogger(CampaignLobby.class).warn("Wingman: could not open the co-op session on the campaign map (port " + cfg.port + ")", t);
                    return;
                }
            }
            for (String line : WingmanAPI.pump()) say("Co-op: " + line);
            handlePicks(s, cfg, true);
            // The fleet as joiners see it: sent when it (or who picked what) changes, and every few seconds anyway.
            if (s.getPlayers().isEmpty()) return;
            Roster r = build(s, cfg, 0);
            String key = signature(r);
            if (key.equals(lastSentKey) && now - lastSent < 5000) return;
            lastSentKey = key;
            lastSent = now;
            byte[] m = r.encode();
            for (HostSession.Player p : s.getPlayers()) p.conn.send(Msg.ROSTER, m);
        } catch (Throwable t) {
            broken = true;
            Global.getLogger(CampaignLobby.class).error("Wingman: the campaign lobby stopped (friends can still join once a battle starts)", t);
        }
    }

    private static void say(String text) {
        try { Global.getSector().getCampaignUI().addMessage(text, NOTE); } catch (Throwable t) { /* the log has it */ }
        Global.getLogger(CampaignLobby.class).info("Wingman: " + text);
    }

    /** The host's fleet, flagship first, then the biggest ships, as it stands now. Empty outside a campaign. */
    static List<FleetMemberAPI> fleet() {
        try {
            CampaignFleetAPI f = Global.getSector() == null ? null : Global.getSector().getPlayerFleet();
            if (f == null || f.getFleetData() == null) return Collections.emptyList();
            final FleetMemberAPI flag = f.getFlagship();
            List<FleetMemberAPI> out = new ArrayList<FleetMemberAPI>(f.getFleetData().getMembersListCopy());
            Collections.sort(out, new Comparator<FleetMemberAPI>() {
                public int compare(FleetMemberAPI a, FleetMemberAPI b) {
                    if (a == flag) return -1;
                    if (b == flag) return 1;
                    return b.getHullSpec().getHullSize().ordinal() - a.getHullSpec().getHullSize().ordinal();
                }
            });
            return out;
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    /** Why a joiner can't pick this member, or null. */
    static String refusal(FleetMemberAPI m, FleetMemberAPI flagship, WingmanConfig cfg) {
        if (m == flagship) return "that is the host's flagship";
        if (m.isMothballed()) return "that ship is mothballed";
        if (m.isStation()) return "a station can't be flown";
        try {
            if (m.getHullSpec().getHullSize().ordinal() > ShipAPI.HullSize.valueOf(cfg.maxShipSize).ordinal()) {
                return "the host only lets joiners fly ships up to " + cfg.maxShipSize.toLowerCase().replace('_', ' ') + " size";
            }
        } catch (Throwable t) { /* no limit known */ }
        return null;
    }

    /** Who picked this member (connected players only), or "". */
    private static String pickerOf(HostSession s, String memberId) {
        for (HostSession.Player p : s.getPlayers()) {
            if (memberId.equals(WingmanAPI.reservedShip(p.name))) return p.name;
        }
        return "";
    }

    /**
     * Picks that have come in (Msg.PICK), checked and kept: on the campaign map, and in a battle for joiners still
     * on their title screen. The player is told when a pick can't be had.
     */
    static boolean handlePicks(HostSession s, WingmanConfig cfg, boolean onMap) {
        boolean any = false;
        for (HostSession.Player p : s.getPlayers()) {
            String want = p.pick;
            if (want == null) continue;
            p.pick = null;
            any = true;
            if (want.isEmpty()) {
                WingmanAPI.reserveShip(p.name, null);
                if (onMap) say("Co-op: " + p.name + " will choose a ship in the battle.");
                continue;
            }
            FleetMemberAPI m = null;
            List<FleetMemberAPI> fleet = fleet();
            for (FleetMemberAPI f : fleet) if (want.equals(f.getId())) m = f;
            String why;
            if (m == null) why = fleet.isEmpty() ? "picks are made on the campaign map" : "that ship is no longer in the fleet";
            else {
                CampaignFleetAPI pf = Global.getSector().getPlayerFleet();
                why = refusal(m, pf == null ? null : pf.getFlagship(), cfg);
                String other = pickerOf(s, want);
                if (why == null && !other.isEmpty() && !other.equalsIgnoreCase(p.name)) why = other + " has picked it already";
            }
            if (why != null) {
                p.conn.send(Msg.NOTICE, Msg.utf("Can't pick that ship: " + why + "."));
                continue;
            }
            WingmanAPI.reserveShip(p.name, want);
            if (onMap) say("Co-op: " + p.name + " picked " + label(m) + ".");
        }
        return any;
    }

    static String label(FleetMemberAPI m) {
        String name = m.getShipName();
        return (name == null || name.isEmpty() ? "" : name + ", ") + m.getHullSpec().getHullName();
    }

    /** The roster joiners are shown. state 0: the host is on the campaign map; 1: in a battle. */
    static Roster build(HostSession s, WingmanConfig cfg, int state) {
        Roster r = new Roster();
        r.hostName = cfg.playerName;
        r.hostState = state;
        r.maxShipSize = cfg.maxShipSize;
        for (HostSession.Player p : s.getPlayers()) {
            Roster.Player rp = new Roster.Player();
            rp.name = p.name;
            rp.slot = p.slot;
            r.players.add(rp);
        }
        CampaignFleetAPI pf = null;
        try { pf = Global.getSector() == null ? null : Global.getSector().getPlayerFleet(); } catch (Throwable t) { /* none */ }
        FleetMemberAPI flag = pf == null ? null : pf.getFlagship();
        for (FleetMemberAPI m : fleet()) {
            if (r.members.size() >= Roster.MAX_MEMBERS) break;
            Roster.Member rm = new Roster.Member();
            rm.id = m.getId();
            rm.shipName = m.getShipName() == null ? "" : m.getShipName();
            rm.hullName = m.getHullSpec().getHullName();
            rm.hullSize = String.valueOf(m.getHullSpec().getHullSize());
            String d = m.getHullSpec().getDesignation();
            rm.designation = d == null ? "" : d;
            try { rm.cr = Math.round(m.getRepairTracker().getCR() * 100f); } catch (Throwable t) { rm.cr = -1; }
            PersonAPI cap = m.getCaptain();
            if (cap != null && !cap.isDefault() && cap.getName() != null) {
                rm.officer = cap.getName().getFullName();
                try { rm.officerLevel = cap.getStats().getLevel(); } catch (Throwable t) { /* no level */ }
            }
            if (m == flag) rm.flags |= Roster.FLAGSHIP;
            if (m.isMothballed()) rm.flags |= Roster.MOTHBALLED;
            String why = refusal(m, flag, cfg);
            if (why != null && m != flag && !m.isMothballed()) { rm.flags |= Roster.CANT_FLY; rm.note = why; }
            rm.pickedBy = pickerOf(s, m.getId());
            r.members.add(rm);
        }
        return r;
    }

    /** What the joiners would see, to send it again only when it changed. */
    private static String signature(Roster r) {
        StringBuilder sb = new StringBuilder(r.hostState + "|" + r.maxShipSize + "|");
        for (Roster.Player p : r.players) sb.append(p.name).append(',');
        for (Roster.Member m : r.members) sb.append(m.id).append(':').append(m.cr).append(':').append(m.flags).append(':').append(m.pickedBy).append(';');
        return sb.toString();
    }

    /** Tell everyone connected the host is now in a battle (their title-screen lobby says so). */
    static void battleStarted(HostSession s, WingmanConfig cfg) {
        try {
            if (s.getPlayers().isEmpty()) return;
            byte[] m = build(s, cfg, 1).encode();
            for (HostSession.Player p : s.getPlayers()) p.conn.send(Msg.ROSTER, m);
        } catch (Throwable t) { /* the battle goes on without it */ }
    }
}

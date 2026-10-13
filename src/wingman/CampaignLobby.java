package wingman;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;

import wingman.api.WingmanAPI;
import wingman.net.Aftermath;
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
    private long lastSent, cfgAt, lastBuilt;
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
            aftermath(s);
            // The fleet as joiners see it: sent when it (or who picked what) changes, and every few seconds anyway.
            if (s.getPlayers().isEmpty()) return;
            if (now - lastBuilt < 1000) return; // the fleet is looked at once a second, not every frame
            lastBuilt = now;
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
            rm.hullId = m.getHullId() == null ? "" : m.getHullId();
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
            loadout(m, rm);
            r.members.add(rm);
        }
        return r;
    }

    /** The ship card's details: picture, weapons ("2x Heavy Mauler"), hullmods, the officer's personality. */
    private static final Map<String, String[]> loadouts = new HashMap<String, String[]>();
    private static long loadoutsAt;

    private static void loadout(FleetMemberAPI m, Roster.Member rm) {
        long now = System.currentTimeMillis();
        if (now - loadoutsAt > 10000) { loadouts.clear(); loadoutsAt = now; } // refits show within ten seconds
        String[] known = loadouts.get(m.getId());
        if (known != null) {
            rm.sprite = known[0]; rm.weapons = known[1]; rm.hullmods = known[2]; rm.personality = known[3]; rm.dmods = known[9];
            if (!known[4].isEmpty()) for (String mod : known[4].split("\n")) rm.modules.add(mod);
            rm.portrait = known[5];
            if (!known[6].isEmpty()) for (String sk : known[6].split("\n")) rm.skills.add(sk);
            if (!known[7].isEmpty()) for (String g : known[7].split("\n")) rm.gear.add(g);
            if (!known[8].isEmpty()) for (String g : known[8].split("\n")) rm.fits.add(g);
            return;
        }
        readLoadout(m, rm);
        StringBuilder mods = new StringBuilder();
        for (String mod : rm.modules) mods.append(mods.length() == 0 ? "" : "\n").append(mod);
        loadouts.put(m.getId(), new String[] { rm.sprite, rm.weapons, rm.hullmods, rm.personality, mods.toString(), rm.portrait, lines(rm.skills), lines(rm.gear), lines(rm.fits), rm.dmods });
    }

    private static String lines(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) sb.append(sb.length() == 0 ? "" : "\n").append(s);
        return sb.toString();
    }

    /** A hull's weapons in their slots, for the ship card's picture (see Roster.Member.fits). */
    private static void fits(com.fs.starfarer.api.combat.ShipVariantAPI v, int index, Roster.Member rm) {
        try {
            for (String slot : v.getFittedWeaponSlots()) {
                if (rm.fits.size() >= Roster.MAX_FITS) return;
                String id = v.getWeaponId(slot);
                if (id != null) rm.fits.add(index + "|" + slot + "|" + id);
            }
        } catch (Throwable t) { /* that hull's weapons aren't drawn */ }
    }

    private static void readLoadout(FleetMemberAPI m, Roster.Member rm) {
        try { rm.sprite = m.getHullSpec().getSpriteName() == null ? "" : m.getHullSpec().getSpriteName(); } catch (Throwable t) { /* no picture */ }
        fits(m.getVariant(), -1, rm);
        // A multi-part ship's modules: which hull sits in which slot, where and turned how, for the picture.
        try {
            com.fs.starfarer.api.combat.ShipVariantAPI v = m.getVariant();
            for (String slotId : v.getModuleSlots()) {
                if (rm.modules.size() >= Roster.MAX_MODULES) break;
                com.fs.starfarer.api.combat.ShipVariantAPI mv = v.getModuleVariant(slotId);
                com.fs.starfarer.api.loading.WeaponSlotAPI slot = m.getHullSpec().getWeaponSlotAPI(slotId);
                if (mv == null || slot == null || mv.getHullSpec() == null || mv.getHullSpec().getSpriteName() == null) continue;
                rm.modules.add(mv.getHullSpec().getHullId() + "|" + mv.getHullSpec().getSpriteName() + "|" + slot.getLocation().x + "|"
                        + slot.getLocation().y + "|" + slot.getAngle());
                fits(mv, rm.modules.size() - 1, rm);
            }
        } catch (Throwable t) { /* the main hull alone */ }
        try {
            com.fs.starfarer.api.combat.ShipVariantAPI v = m.getVariant();
            Map<String, Integer> count = new LinkedHashMap<String, Integer>();
            Map<String, Integer> gear = new LinkedHashMap<String, Integer>();
            for (String slot : v.getFittedWeaponSlots()) {
                String id = v.getWeaponId(slot);
                if (id == null) continue;
                try { // built-in decorations and hidden system weapons aren't part of the loadout
                    com.fs.starfarer.api.loading.WeaponSlotAPI ws = v.getSlot(slot);
                    if (ws != null && (ws.isDecorative() || ws.isSystemSlot() || ws.isHidden())) continue;
                } catch (Throwable t) { /* shown */ }
                Integer gn = gear.get("w|" + id);
                gear.put("w|" + id, gn == null ? 1 : gn + 1);
                String name;
                try { name = Global.getSettings().getWeaponSpec(id).getWeaponName(); } catch (Throwable t) { name = id; }
                Integer n = count.get(name);
                count.put(name, n == null ? 1 : n + 1);
            }
            for (String wing : v.getFittedWings()) {
                Integer gn = gear.get("f|" + wing);
                gear.put("f|" + wing, gn == null ? 1 : gn + 1);
                String name;
                try { name = Global.getSettings().getFighterWingSpec(wing).getWingName(); } catch (Throwable t) { name = wing; }
                Integer n = count.get(name);
                count.put(name, n == null ? 1 : n + 1);
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> e : count.entrySet()) sb.append(sb.length() == 0 ? "" : ", ").append(e.getValue() > 1 ? e.getValue() + "x " : "").append(e.getKey());
            rm.weapons = sb.toString();
            for (Map.Entry<String, Integer> e : gear.entrySet()) {
                if (rm.gear.size() >= Roster.MAX_GEAR) break;
                rm.gear.add(e.getKey() + "|" + e.getValue());
            }
            // Every hull mod the ship has: fitted, permanent (D-mods among them) and built into the hull (a D-hull's
            // own D-mods can be there), each once. D-mods are listed on their own.
            java.util.Set<String> all = new java.util.LinkedHashSet<String>(v.getHullMods());
            try { all.addAll(v.getPermaMods()); } catch (Throwable t) { /* those in the list above */ }
            try { all.addAll(v.getHullSpec().getBuiltInMods()); } catch (Throwable t) { /* those in the list above */ }
            StringBuilder mods = new StringBuilder(), dmods = new StringBuilder();
            for (String id : all) {
                String name;
                boolean dmod = false;
                try {
                    com.fs.starfarer.api.loading.HullModSpecAPI spec = Global.getSettings().getHullModSpec(id);
                    if (spec.isHidden() || spec.isHiddenEverywhere()) continue;
                    name = spec.getDisplayName();
                    dmod = spec.hasTag(com.fs.starfarer.api.impl.campaign.ids.Tags.HULLMOD_DMOD);
                } catch (Throwable t) { name = id; }
                boolean smod = false;
                try { smod = v.getSMods().contains(id) || v.getSModdedBuiltIns().contains(id); } catch (Throwable t) { /* plain */ }
                StringBuilder to = dmod ? dmods : mods;
                to.append(to.length() == 0 ? "" : ", ").append(name).append(smod ? " (S)" : "");
            }
            rm.hullmods = mods.toString();
            rm.dmods = dmods.toString();
        } catch (Throwable t) { /* no loadout shown */ }
        try {
            PersonAPI cap = m.getCaptain();
            if (cap != null && !cap.isDefault() && cap.getPersonalityAPI() != null) rm.personality = cap.getPersonalityAPI().getDisplayName();
            if (cap != null && !cap.isDefault()) {
                if (cap.getPortraitSprite() != null) rm.portrait = cap.getPortraitSprite();
                for (com.fs.starfarer.api.characters.MutableCharacterStatsAPI.SkillLevelAPI sl : cap.getStats().getSkillsCopy()) {
                    if (rm.skills.size() >= Roster.MAX_SKILLS) break;
                    try {
                        if (sl.getLevel() <= 0f || sl.getSkill() == null || sl.getSkill().isAptitudeEffect()) continue;
                        rm.skills.add(sl.getSkill().getId() + ":" + Math.round(sl.getLevel()));
                    } catch (Throwable t) { /* that one is left out */ }
                }
            }
        } catch (Throwable t) { /* none */ }
    }

    /** What the joiners would see, to send it again only when it changed. */
    private static String signature(Roster r) {
        StringBuilder sb = new StringBuilder(r.hostState + "|" + r.maxShipSize + "|");
        for (Roster.Player p : r.players) sb.append(p.name).append(',');
        for (Roster.Member m : r.members) sb.append(m.id).append(':').append(m.cr).append(':').append(m.flags).append(':').append(m.pickedBy).append(';');
        return sb.toString();
    }

    // ---- the aftermath: what the battle changed in the host's fleet, told once the battle's dialog is closed ----

    /** The fleet as it was when the first battle of this encounter started (null: nothing pending). */
    private static Map<String, String> shipsBefore;
    private static Map<String, Float> cargoBefore;
    private static float creditsBefore;

    private static void noteFleetBefore() {
        if (shipsBefore != null) return; // a later battle of the same encounter: the aftermath covers them all
        try {
            CampaignFleetAPI f = Global.getSector() == null ? null : Global.getSector().getPlayerFleet();
            if (f == null) return;
            cargoBefore = cargoOf(f);
            creditsBefore = f.getCargo().getCredits().get();
            shipsBefore = shipsOf(f);
        } catch (Throwable t) { shipsBefore = null; }
    }

    private static Map<String, String> shipsOf(CampaignFleetAPI f) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (FleetMemberAPI fm : f.getFleetData().getMembersListCopy()) m.put(fm.getId(), label(fm));
        return m;
    }

    private static Map<String, Float> cargoOf(CampaignFleetAPI f) {
        Map<String, Float> m = new HashMap<String, Float>();
        for (com.fs.starfarer.api.campaign.CargoStackAPI st : f.getCargo().getStacksCopy()) {
            String name = st.getDisplayName();
            if (name == null) continue;
            Float had = m.get(name);
            m.put(name, (had == null ? 0f : had) + st.getSize());
        }
        return m;
    }

    /** Back on the map with no dialog open: tell joiners what changed, once. */
    private static void aftermath(HostSession s) {
        if (shipsBefore == null) return;
        try {
            if (Global.getSector().getCampaignUI().isShowingDialog()) return;
            CampaignFleetAPI f = Global.getSector().getPlayerFleet();
            if (f == null) { shipsBefore = null; return; }
            Aftermath a = new Aftermath();
            a.credits = f.getCargo().getCredits().get() - creditsBefore;
            Map<String, String> now = shipsOf(f);
            for (Map.Entry<String, String> e : shipsBefore.entrySet()) if (!now.containsKey(e.getKey())) a.shipsLost.add(e.getValue());
            for (Map.Entry<String, String> e : now.entrySet()) if (!shipsBefore.containsKey(e.getKey())) a.shipsGained.add(e.getValue());
            Map<String, Float> cargo = cargoOf(f);
            final Map<String, Float> change = new HashMap<String, Float>();
            for (Map.Entry<String, Float> e : cargo.entrySet()) {
                Float had = cargoBefore.get(e.getKey());
                change.put(e.getKey(), e.getValue() - (had == null ? 0f : had));
            }
            for (Map.Entry<String, Float> e : cargoBefore.entrySet()) if (!cargo.containsKey(e.getKey())) change.put(e.getKey(), -e.getValue());
            List<String> names = new ArrayList<String>();
            for (Map.Entry<String, Float> e : change.entrySet()) if (Math.abs(e.getValue()) >= 0.5f) names.add(e.getKey());
            Collections.sort(names, new Comparator<String>() {
                public int compare(String x, String y) { return Float.compare(Math.abs(change.get(y)), Math.abs(change.get(x))); }
            });
            for (String n : names) {
                if (a.cargoNames.size() >= Aftermath.MAX_CARGO) break;
                a.cargoNames.add(n);
                a.cargoChanges.add(change.get(n));
            }
            shipsBefore = null;
            cargoBefore = null;
            if (a.isEmpty() || s.getPlayers().isEmpty()) return;
            byte[] m = a.encode();
            for (HostSession.Player p : s.getPlayers()) p.conn.send(Msg.AFTERMATH, m);
            Global.getLogger(CampaignLobby.class).info("Wingman: aftermath sent to joiners: credits " + Math.round(a.credits) + ", "
                    + a.shipsLost.size() + " ships lost, " + a.shipsGained.size() + " gained, " + a.cargoNames.size() + " cargo changes");
        } catch (Throwable t) {
            shipsBefore = null;
            Global.getLogger(CampaignLobby.class).warn("Wingman: the battle's aftermath could not be worked out for joiners", t);
        }
    }

    /** Tell everyone connected the host is now in a battle (their title-screen lobby says so). */
    static void battleStarted(HostSession s, WingmanConfig cfg) {
        noteFleetBefore();
        try {
            if (s.getPlayers().isEmpty()) return;
            byte[] m = build(s, cfg, 1).encode();
            for (HostSession.Player p : s.getPlayers()) p.conn.send(Msg.ROSTER, m);
        } catch (Throwable t) { /* the battle goes on without it */ }
    }
}

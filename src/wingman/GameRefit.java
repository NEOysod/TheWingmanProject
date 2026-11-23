package wingman;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.loading.WeaponGroupSpec;
import com.fs.starfarer.api.loading.WeaponGroupType;
import com.fs.starfarer.api.loading.WeaponSlotAPI;

import wingman.net.RefitData;
import wingman.net.RefitRequest;

/**
 * Friend refit in the game's own refit screen (joiner side): the "Wingman: Refit" mission.
 *
 * On the refit page, G puts the ship, as the host last described it (RefitData), into that mission as its only
 * ship. The player opens Missions, picks the mission, clicks the ship and refits it in the game's own screen. The
 * game saves a mission's refitted ship by a name of its own, "mission_" + mission id + "_ship_" + its place in the
 * fleet, and keeps it among its variants, so it can be read back by that name through the API (no files, nothing
 * of the game's internals). Back in CO-OP, what changed against the host's ship becomes a friend refit request,
 * shown on the refit page for the player to check and send; the host checks it all again as for any refit.
 *
 * The ship goes into the mission with MissionDefinitionAPI.addFleetMember, not addToFleet: addToFleet would put an
 * earlier saved refit of the same hull in its place, and the mission must show the host's ship as it is now.
 * Items are asked for from "wherever the host can get them" (the mission's refit screen shows every weapon and hull
 * mod, not only the host's). S-mods can't be built in there; those the ship has are kept.
 */
public final class GameRefit {
    private GameRefit() {}

    public static final String MISSION = "wingman_refit";
    /** The name the game gives the mission's first ship once refitted (see the class comment). */
    static final String SAVED = "mission_" + MISSION + "_ship_0";

    /** The ship as the host described it, waiting for the player to refit it in the mission; null if none. */
    private static RefitData offered;
    /** The saved design as it was when the ship was offered (so only a new save counts), or null. */
    private static String before;
    private static String shipName = "";
    private static int pollFrames;

    /** Offer this ship to the mission (G on the refit page). */
    static void offer(RefitData d, String name) {
        offered = d;
        shipName = name == null ? "" : name;
        before = signature(saved());
    }

    static boolean waiting() { return offered != null; }

    static String waitingFor() { return offered == null ? "" : offered.memberId; }

    static void forget() { offered = null; before = null; }

    /** For the mission: the ship to refit, or null if none was offered. */
    public static FleetMemberAPI member() {
        if (offered == null) return null;
        try {
            ShipVariantAPI v = variantOf(offered);
            if (v == null) return null;
            FleetMemberAPI m = Global.getFactory().createFleetMember(FleetMemberType.SHIP, v);
            if (!shipName.isEmpty()) m.setShipName(shipName);
            try { m.getRepairTracker().setCR(m.getRepairTracker().getMaxCR()); } catch (Throwable t) { /* as made */ }
            return m;
        } catch (Throwable t) {
            Global.getLogger(GameRefit.class).warn("Wingman: the ship couldn't be put in the refit mission", t);
            return null;
        }
    }

    /** What the mission's briefing says. */
    public static String briefing() {
        if (offered == null) return "No ship yet: in CO-OP, pick your ship, open its refit page (R) and press G. Then open this mission again.";
        return "Click the ship, then Refit. Your changes are saved as you make them; then go back to CO-OP, where they are"
                + " loaded into the ship's refit page for you to check and send to the host. Don't start the mission.";
    }

    /**
     * Each frame on the title screen: if the player has refitted the offered ship in the mission since it was
     * offered, the changes as a refit request (and the offer is done); else null.
     */
    static RefitRequest poll() {
        if (offered == null || ++pollFrames < 30) return null;
        pollFrames = 0;
        ShipVariantAPI now = saved();
        String sig = signature(now);
        if (now == null || sig.equals(before)) return null;
        if (!offered.hullId.equals(now.getHullSpec().getHullId())) return null; // another ship's design
        RefitRequest r = diff(offered, now);
        forget();
        return r;
    }

    // ---- the ship as a variant ----

    /** The host's ship as RefitData describes it, as a variant of its own (not one of the game's saved ones). */
    static ShipVariantAPI variantOf(RefitData d) {
        ShipHullSpecAPI hull = Global.getSettings().getHullSpec(d.hullId);
        ShipVariantAPI v = Global.getSettings().createEmptyVariant("wingman_refit_" + d.hullId, hull);
        try {
            for (Map.Entry<String, String> e : hull.getBuiltInWeapons().entrySet()) v.addWeapon(e.getKey(), e.getValue());
        } catch (Throwable t) { /* none */ }
        for (int i = 0; i < d.fitSlots.size() && i < d.fitWeapons.size(); i++) {
            try { v.addWeapon(d.fitSlots.get(i), d.fitWeapons.get(i)); } catch (Throwable t) { /* a slot or weapon this game doesn't have */ }
        }
        for (Map.Entry<String, String> e : d.fixedWeapons.entrySet()) { // built in by a hull mod or the variant, not the hull
            try { if (v.getWeaponId(e.getKey()) == null) v.addWeapon(e.getKey(), e.getValue()); } catch (Throwable t) { /* unknown here */ }
        }
        int builtInWings = 0;
        try { builtInWings = hull.getBuiltInWings().size(); } catch (Throwable t) { /* none */ }
        for (int i = builtInWings; i < d.wings.size(); i++) {
            String w = d.wings.get(i);
            if (w != null && !w.isEmpty()) try { v.setWingId(i, w); } catch (Throwable t) { /* unknown wing */ }
        }
        for (String m : d.permaMods) try { v.addPermaMod(m, false); } catch (Throwable t) { /* unknown */ }
        for (String m : d.sMods) {
            try {
                if (hull.isBuiltInMod(m)) v.getSModdedBuiltIns().add(m);
                else v.addPermaMod(m, true);
            } catch (Throwable t) { /* unknown */ }
        }
        for (String m : d.hullmods) try { v.addMod(m); } catch (Throwable t) { /* unknown */ }
        v.setNumFluxVents(d.vents);
        v.setNumFluxCapacitors(d.caps);
        if (d.groups != null) {
            for (RefitData.Group g : d.groups) {
                WeaponGroupSpec spec = new WeaponGroupSpec(g.alternating ? WeaponGroupType.ALTERNATING : WeaponGroupType.LINKED);
                spec.setAutofireOnByDefault(g.autofire);
                for (String s : g.slots) spec.addSlot(s);
                v.addWeaponGroup(spec);
            }
        } else {
            try { v.autoGenerateWeaponGroups(); } catch (Throwable t) { /* none */ }
        }
        return v;
    }

    /** The mission's saved refit of its first ship, or null. */
    private static ShipVariantAPI saved() {
        try {
            if (!Global.getSettings().doesVariantExist(SAVED)) return null;
            return Global.getSettings().getVariant(SAVED);
        } catch (Throwable t) {
            return null;
        }
    }

    /** True for a slot the player fits (not built in, decorative, a system's or a module's). */
    private static boolean fittable(ShipVariantAPI v, String slot) {
        try {
            WeaponSlotAPI ws = v.getSlot(slot);
            return ws != null && !(ws.isBuiltIn() || ws.isDecorative() || ws.isSystemSlot() || ws.isHidden() || ws.isStationModule());
        } catch (Throwable t) {
            return false;
        }
    }

    private static Map<String, String> weapons(ShipVariantAPI v) {
        Map<String, String> out = new HashMap<String, String>();
        for (String slot : v.getFittedWeaponSlots()) {
            String id = v.getWeaponId(slot);
            if (id != null && fittable(v, slot)) out.put(slot, id);
        }
        return out;
    }

    /** The hull mods the player fitted (not built in, not permanent, not S-mods), as the host counts them. */
    private static Set<String> fitted(ShipVariantAPI v) {
        Set<String> s = new LinkedHashSet<String>(v.getNonBuiltInHullmods());
        s.removeAll(v.getPermaMods());
        try { s.removeAll(v.getSMods()); } catch (Throwable t) { /* none */ }
        return s;
    }

    private static List<RefitData.Group> groups(ShipVariantAPI v) {
        List<RefitData.Group> out = new ArrayList<RefitData.Group>();
        for (WeaponGroupSpec g : v.getWeaponGroups()) {
            if (out.size() >= RefitData.MAX_GROUPS) break;
            RefitData.Group rg = new RefitData.Group();
            rg.alternating = g.getType() == WeaponGroupType.ALTERNATING;
            rg.autofire = g.isAutofireOnByDefault();
            for (String slot : g.getSlots()) if (slot != null && rg.slots.size() < RefitData.MAX_SLOTS) rg.slots.add(slot);
            out.add(rg);
        }
        return out;
    }

    /** A short text of everything a refit changes, to tell one save from another (null: none saved). */
    static String signature(ShipVariantAPI v) {
        if (v == null) return null;
        StringBuilder sb = new StringBuilder(v.getHullSpec().getHullId()).append('|');
        sb.append(new java.util.TreeMap<String, String>(weapons(v))).append('|').append(v.getWings()).append('|');
        sb.append(new java.util.TreeSet<String>(fitted(v))).append('|').append(v.getNumFluxVents()).append('/').append(v.getNumFluxCapacitors()).append('|');
        for (RefitData.Group g : groups(v)) sb.append(g.alternating ? 'A' : 'L').append(g.autofire ? '*' : ' ').append(g.slots).append(';');
        return sb.toString();
    }

    /** What the saved design changes against the host's ship, as a friend refit request. */
    static RefitRequest diff(RefitData d, ShipVariantAPI now) {
        RefitRequest r = new RefitRequest();
        r.memberId = d.memberId;
        r.note = "Refitted in the game's refit screen.";
        Map<String, String> had = new HashMap<String, String>();
        for (int i = 0; i < d.fitSlots.size() && i < d.fitWeapons.size(); i++) had.put(d.fitSlots.get(i), d.fitWeapons.get(i));
        Map<String, String> want = weapons(now);
        Set<String> slots = new LinkedHashSet<String>(had.keySet());
        slots.addAll(want.keySet());
        for (String slot : slots) {
            String a = had.get(slot), b = want.get(slot);
            if (a == null ? b == null : a.equals(b)) continue;
            // A weapon a mod's hull mod fits by itself (Arma Armatura's arms, with its selectors): the host's game fits
            // it again when the selector steps there (ModRefit.Loadouts), so it isn't asked for.
            if (modsOwn(a) || modsOwn(b) || d.loadoutOwning(slot) != null) continue;
            if (r.changes.size() >= RefitRequest.MAX_CHANGES) break;
            RefitRequest.Change c = new RefitRequest.Change();
            c.wing = false;
            c.place = slot;
            c.item = b == null ? "" : b;
            c.from = b == null ? "" : RefitRequest.ANY;
            c.to = RefitRequest.CARGO;
            r.changes.add(c);
        }
        int builtInWings = 0;
        try { builtInWings = now.getHullSpec().getBuiltInWings().size(); } catch (Throwable t) { /* none */ }
        for (int i = builtInWings; i < d.wings.size(); i++) {
            String a = d.wings.get(i), b = null;
            try { b = now.getWingId(i); } catch (Throwable t) { /* empty */ }
            if (a != null && a.isEmpty()) a = null;
            if (a == null ? b == null : a.equals(b)) continue;
            if (r.changes.size() >= RefitRequest.MAX_CHANGES) break;
            RefitRequest.Change c = new RefitRequest.Change();
            c.wing = true;
            c.place = String.valueOf(i);
            c.item = b == null ? "" : b;
            c.from = b == null ? "" : RefitRequest.ANY;
            c.to = RefitRequest.CARGO;
            r.changes.add(c);
        }
        Set<String> wantMods = fitted(now);
        Collection<String> hadMods = d.hullmods;
        for (String m : wantMods) if (!hadMods.contains(m) && !d.sMods.contains(m) && !d.permaMods.contains(m) && r.addMods.size() < RefitRequest.MAX_MODS) r.addMods.add(m);
        for (String m : hadMods) if (!wantMods.contains(m) && r.removeMods.size() < RefitRequest.MAX_MODS) r.removeMods.add(m);
        r.vents = now.getNumFluxVents();
        r.caps = now.getNumFluxCapacitors();
        List<RefitData.Group> g = groups(now);
        boolean same = d.groups != null && d.groups.size() == g.size();
        for (int i = 0; same && i < g.size(); i++) same = g.get(i).same(d.groups.get(i));
        if (d.groups != null && !same) r.groups = g;
        return r;
    }

    /** A weapon only a hull mod fits (the SYSTEM hint, never dropped or sold): not one the player moves. */
    private static boolean modsOwn(String weapon) {
        if (weapon == null) return false;
        try {
            com.fs.starfarer.api.loading.WeaponSpecAPI w = Global.getSettings().getWeaponSpec(weapon);
            return w.getAIHints().contains(WeaponAPI.AIHints.SYSTEM) && w.hasTag("no_drop");
        } catch (Throwable t) {
            return false;
        }
    }

    /** True if the request asks for nothing (the design was saved unchanged). */
    static boolean empty(RefitRequest r, RefitData d) {
        return r.changes.isEmpty() && r.addMods.isEmpty() && r.removeMods.isEmpty() && r.groups == null && r.vents == d.vents && r.caps == d.caps;
    }

    /** The mission's "start" plugin: if the player starts the mission anyway, it ends at once. */
    public static final class EndAtOnce extends com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin {
        private boolean done;
        @Override
        public void advance(float amount, List<com.fs.starfarer.api.input.InputEventAPI> events) {
            if (done) return;
            done = true;
            try { Global.getCombatEngine().endCombat(0f, com.fs.starfarer.api.mission.FleetSide.PLAYER); } catch (Throwable t) { /* leave it be */ }
        }
    }
}

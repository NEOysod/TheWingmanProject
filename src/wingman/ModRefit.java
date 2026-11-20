package wingman;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.loading.VariantSource;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.loading.HullModSpecAPI;

import wingman.net.RefitData;
import wingman.net.RefitRequest;

/**
 * Other mods' loadout rules for a friend's refit, done on the host when it is accepted. Each keys on that mod's own
 * hull and hull mod ids, so it does nothing without the mod. After scardwell15's pull request #1.
 */
final class ModRefit {
    private ModRefit() {}

    /** After a refit's hull mods are applied to the variant. */
    static void afterHullMods(ShipVariantAPI v) {
        try {
            DiableVirtuous.settle(v);
        } catch (Throwable t) {
            Global.getLogger(ModRefit.class).warn("Wingman: Diable Avionics' Virtuous loadout not stepped after a refit", t);
        }
    }

    /**
     * Loadout selectors, for any mod that has them: hidden hull mods that the mod's own hull mod replaces, when one
     * is taken off, with the next in its cycle, changing the weapons (or hull) that go with it. Arma Armatura's
     * strikecraft arms (Tartiflette's selector code: the hull mod's applyEffectsBeforeShipCreation sees no selector
     * and fits the next one) and Diable Avionics' Virtuous (DiableVirtuous below) work this way.
     *
     * Nothing here knows any mod's ids. The host finds each cycle by trying it: on a copy of the ship, take the
     * selector off and have the game work the ship out again (a fleet member's stats run every hull mod's
     * applyEffectsBeforeShipCreation, as the refit screen does), and see which hidden hull mod the mod put on in its
     * place, until the cycle comes back round. What each option fits is read off those copies. The joiner picks an
     * option from that list; on accepting, the host steps the real ship there the same way.
     */
    static final class Loadouts {
        private Loadouts() {}

        /** Most steps tried round one cycle. */
        static final int MAX_STEPS = RefitData.MAX_OPTIONS;

        /** A hull mod not shown in the refit screen but not hidden from everything: what a selector is. */
        static boolean hidden(String id) {
            try {
                HullModSpecAPI s = Global.getSettings().getHullModSpec(id);
                return s.isHidden() && !s.isHiddenEverywhere();
            } catch (Throwable t) {
                return false;
            }
        }

        /** The ship's selector cycles, found on copies of its variant (the ship itself is not touched). */
        static List<RefitData.Loadout> find(ShipVariantAPI v) {
            List<RefitData.Loadout> out = new ArrayList<RefitData.Loadout>();
            Set<String> seen = new HashSet<String>();
            Set<String> fixed = new HashSet<String>();
            try { fixed.addAll(v.getPermaMods()); fixed.addAll(v.getSMods()); } catch (Throwable t) { /* none */ }
            for (String id : new ArrayList<String>(v.getNonBuiltInHullmods())) {
                if (out.size() >= RefitData.MAX_LOADOUTS) break;
                if (seen.contains(id) || fixed.contains(id) || !hidden(id)) continue;
                try {
                    RefitData.Loadout l = cycle(v, id);
                    if (l == null) continue;
                    seen.addAll(l.mods);
                    out.add(l);
                } catch (Throwable t) {
                    Global.getLogger(ModRefit.class).warn("Wingman: loadout selector " + id + " not worked out", t);
                }
            }
            return out;
        }

        /** One selector's cycle from the variant it is on, or null if taking it off puts nothing else on. */
        private static RefitData.Loadout cycle(ShipVariantAPI v, String start) {
            List<String> mods = new ArrayList<String>();
            List<ShipVariantAPI> results = new ArrayList<ShipVariantAPI>();
            mods.add(start);
            results.add(v);
            ShipVariantAPI at = v;
            String on = start;
            for (int step = 0; step < MAX_STEPS; step++) {
                Set<String> before = new HashSet<String>(at.getNonBuiltInHullmods());
                ShipVariantAPI next = step(at, on);
                String put = null;
                for (String id : next.getNonBuiltInHullmods()) {
                    if (id.equals(on) || !hidden(id)) continue;
                    if (!before.contains(id)) { put = id; break; }
                }
                if (put == null || put.equals(start) || mods.contains(put)) break;
                mods.add(put);
                results.add(next);
                at = next;
                on = put;
            }
            if (mods.size() < 2) return null;
            RefitData.Loadout l = new RefitData.Loadout();
            l.current = 0;
            // The slots it owns: those whose weapon isn't the same for every option.
            Set<String> slots = new LinkedHashSet<String>();
            for (ShipVariantAPI r : results) slots.addAll(r.getFittedWeaponSlots());
            for (String s : new ArrayList<String>(slots)) {
                String first = results.get(0).getWeaponId(s);
                boolean same = true;
                for (ShipVariantAPI r : results) {
                    String w = r.getWeaponId(s);
                    if (first == null ? w != null : !first.equals(w)) same = false;
                }
                if (same) slots.remove(s);
            }
            for (String s : slots) if (l.slots.size() < RefitData.MAX_LOADOUT_SLOTS) l.slots.add(s);
            for (int i = 0; i < mods.size(); i++) {
                ShipVariantAPI r = results.get(i);
                l.mods.add(mods.get(i));
                l.hulls.add(r.getHullSpec() == null ? "" : r.getHullSpec().getHullId());
                List<String> w = new ArrayList<String>();
                for (String s : l.slots) { String id = r.getWeaponId(s); w.add(id == null ? "" : id); }
                l.weapons.add(w);
            }
            names(l);
            return l;
        }

        /** "R ARM: HEAVY RIFLE", "R ARM: LASER RIFLE": named R ARM, with HEAVY RIFLE and LASER RIFLE. */
        private static void names(RefitData.Loadout l) {
            List<String> full = new ArrayList<String>();
            for (String id : l.mods) {
                String n = id;
                try { n = Global.getSettings().getHullModSpec(id).getDisplayName(); } catch (Throwable t) { /* its id */ }
                full.add(n == null || n.trim().isEmpty() ? id : n.trim());
            }
            String head = null;
            for (String n : full) {
                int c = n.indexOf(':');
                String h = c > 0 ? n.substring(0, c).trim() : null;
                if (head == null) head = h;
                else if (h == null || !h.equalsIgnoreCase(head)) { head = ""; break; }
            }
            if (head != null && !head.isEmpty()) {
                l.name = head;
                for (String n : full) l.labels.add(n.substring(n.indexOf(':') + 1).trim());
                return;
            }
            String hull = "";
            try { hull = Global.getSettings().getHullSpec(l.hulls.get(0)).getHullName(); } catch (Throwable t) { /* none */ }
            l.name = "Loadout" + (hull.isEmpty() ? "" : " (" + hull + ")");
            l.labels.addAll(full);
        }

        /**
         * One step of a selector's cycle on a copy: the selector off, then the ship worked out as the game would,
         * for the mod's hull mod to put the next one on. Returns the copy.
         */
        static ShipVariantAPI step(ShipVariantAPI v, String selector) {
            ShipVariantAPI c = v.clone();
            c.setSource(VariantSource.REFIT);
            c.removeMod(selector);
            FleetMemberAPI tmp = Global.getFactory().createFleetMember(FleetMemberType.SHIP, c);
            tmp.updateStats();
            ShipVariantAPI out = tmp.getVariant();
            afterHullMods(out);
            return out;
        }

        /**
         * The variant stepped round each loadout to the option the request picks (its hull mod in addMods, the
         * one fitted in removeMods). Returns the stepped copy, or v if nothing changes; what can't be reached is
         * left where it is and logged.
         */
        static ShipVariantAPI reach(ShipVariantAPI v, RefitData d, RefitRequest r) {
            for (RefitData.Loadout l : d.loadouts) {
                String want = picked(l, r);
                if (want == null) continue;
                for (int i = 0; i < MAX_STEPS && !v.hasHullMod(want); i++) {
                    String on = null;
                    for (String m : l.mods) if (v.hasHullMod(m)) on = m;
                    if (on == null) break;
                    v = step(v, on);
                }
                if (!v.hasHullMod(want)) Global.getLogger(ModRefit.class).info("Wingman: loadout " + l.name + " not reached: " + want);
            }
            return v;
        }

        /** The option a request picks for a loadout (its hull mod), or null to leave it. */
        static String picked(RefitData.Loadout l, RefitRequest r) {
            if (!r.removeMods.contains(l.currentMod())) return null;
            for (String m : r.addMods) if (l.mods.contains(m) && !m.equals(l.currentMod())) return m;
            return null;
        }

        /** True if a hull mod in a request is a loadout option (picked by stepping, never fitted as asked). */
        static boolean option(RefitData d, String mod) {
            return d != null && d.loadoutOf(mod) != null;
        }
    }

    /**
     * Diable Avionics' Virtuous: hidden selector hull mods pick its hull config (and ship system), left and right
     * weapon kits and head weapon. Taking a selector off makes Diable's master hull mod put on the next one in its
     * cycle when the ship is next built; the hull config step needs a live ship, which a fleet member at the host's
     * refit isn't. So the same cycle is done here, on the variant, as that hull mod does it. Ids as in Diable
     * Avionics 2.9.5 (data/hullmods/hull_mods.csv).
     */
    static final class DiableVirtuous {
        private DiableVirtuous() {}

        static final String PREFIX = "diableavionics_virtuous_";
        /** Hull configs in Diable's cycle; each is both a selector hull mod and a hull (the first the base, the rest skins). */
        static final List<String> CONFIGS = Arrays.asList(PREFIX + "skirmisher", PREFIX + "brawler", PREFIX + "defender", PREFIX + "scout");
        /** Weapon kits in Diable's cycle; kit + "_L"/"_R" is both the selector hull mod and the weapon in slot LEFT/RIGHT. */
        static final List<String> KITS = Arrays.asList(PREFIX + "scicle", PREFIX + "snowblast", PREFIX + "excision", PREFIX + "glaux",
                PREFIX + "grasshopper", PREFIX + "beast", PREFIX + "roar");
        /** Heads in Diable's cycle; each is both the selector hull mod and the weapon in slot HEAD. */
        static final List<String> HEADS = Arrays.asList(PREFIX + "headA", PREFIX + "headB", PREFIX + "headC", PREFIX + "headD");

        /** Where a selector is missing, the next one in its cycle, with its weapon or hull; nothing on any other ship. */
        static void settle(ShipVariantAPI v) {
            ShipHullSpecAPI hull = v.getHullSpec();
            if (hull == null || !(CONFIGS.get(0).equals(hull.getHullId()) || CONFIGS.get(0).equals(hull.getBaseHullId()))) return;
            Collection<String> mods = v.getHullMods();
            boolean changed = false;
            if (!any(mods, CONFIGS, "")) {
                int at = CONFIGS.indexOf(hull.getHullId());
                if (at >= 0) { // a hull outside the cycle (Diable's wrecked skin) keeps its hull, as Diable's own cycle can't step from it
                    String next = CONFIGS.get((at + 1) % CONFIGS.size());
                    v.setHullSpecAPI(Global.getSettings().getHullSpec(next));
                    v.addMod(next);
                }
            }
            if (!any(mods, KITS, "_L")) { kit(v, "LEFT", "_L"); changed = true; }
            if (!any(mods, KITS, "_R")) { kit(v, "RIGHT", "_R"); changed = true; }
            if (!any(mods, HEADS, "")) {
                String next = HEADS.get((HEADS.indexOf(v.getWeaponId("HEAD")) + 1) % HEADS.size()); // none fitted: the first
                v.addMod(next);
                v.clearSlot("HEAD");
                v.addWeapon("HEAD", next);
                changed = true;
            }
            if (changed) v.autoGenerateWeaponGroups();
        }

        private static void kit(ShipVariantAPI v, String slot, String side) {
            String now = v.getWeaponId(slot), next = KITS.get(0) + side;
            for (int i = 0; i < KITS.size(); i++) {
                if (now != null && now.startsWith(KITS.get(i))) next = KITS.get((i + 1) % KITS.size()) + side;
            }
            v.addMod(next);
            v.clearSlot(slot);
            v.addWeapon(slot, next);
        }

        private static boolean any(Collection<String> mods, List<String> ids, String suffix) {
            for (String id : ids) if (mods.contains(id + suffix)) return true;
            return false;
        }
    }
}

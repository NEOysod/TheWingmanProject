package wingman;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;

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
     * Loadout selectors: hidden hull mods that a mod's own hull mod replaces, when one is taken off, with the next
     * in its cycle, changing a weapon with it (Arma Armatura's arm weapons on its strikecraft, from Tartiflette's
     * selector code: the hull mod's applyEffectsBeforeShipCreation sees no selector and fits the next one). The
     * game's refit runs that as you click; on the host it runs when the ship's stats are next worked out, which a
     * refit does (FriendRefit.accept). Taking one off from Wingman's refit page steps once. A refit made in the
     * game's own screen (GameRefit) can have stepped several times: it asks for the selector it ended on, and the
     * host steps until it gets there, taking off whatever the mod put on each time, at most 12 times.
     */
    static final class Selectors {
        private Selectors() {}

        static boolean hidden(String id) {
            try {
                com.fs.starfarer.api.loading.HullModSpecAPI s = Global.getSettings().getHullModSpec(id);
                return s.isHidden() && !s.isHiddenEverywhere();
            } catch (Throwable t) {
                return false;
            }
        }

        /** A hidden hull mod asked for: one to step to, not to fit. */
        static boolean target(String id) { return hidden(id); }

        /** True if the refit takes a selector off (so hidden ones it asks for are where to step to). */
        static boolean stepping(wingman.net.RefitRequest r) {
            for (String id : r.removeMods) if (hidden(id)) return true;
            return false;
        }

        /** After the refit: step each selector until the one asked for is on (or no step helps). */
        static void stepTo(com.fs.starfarer.api.fleet.FleetMemberAPI m, wingman.net.RefitRequest r) {
            try {
                java.util.List<String> want = new java.util.ArrayList<String>();
                for (String id : r.addMods) if (hidden(id)) want.add(id);
                if (want.isEmpty() || !stepping(r)) return;
                ShipVariantAPI v = m.getVariant();
                java.util.Set<String> keep = new java.util.HashSet<String>();
                for (String id : v.getNonBuiltInHullmods()) if (hidden(id) && !r.removeMods.contains(id)) keep.add(id);
                for (int step = 0; step < 12; step++) {
                    v = m.getVariant();
                    for (java.util.Iterator<String> it = want.iterator(); it.hasNext();) if (v.hasHullMod(it.next())) it.remove();
                    if (want.isEmpty()) return;
                    java.util.List<String> stepOff = new java.util.ArrayList<String>();
                    for (String id : v.getNonBuiltInHullmods()) if (hidden(id) && !keep.contains(id) && !r.addMods.contains(id)) stepOff.add(id);
                    if (stepOff.isEmpty()) break;
                    for (String id : stepOff) v.removeMod(id);
                    m.updateStats();
                }
                Global.getLogger(ModRefit.class).info("Wingman: loadout selectors not all reached after a refit: " + want);
            } catch (Throwable t) {
                Global.getLogger(ModRefit.class).warn("Wingman: loadout selectors not stepped after a refit", t);
            }
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

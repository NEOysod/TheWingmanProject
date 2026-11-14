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

package wingman.compat;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import java.util.Map;
import wingman.net.ShipSpawn;

/** Rebuilds EHM through its own hullmod callbacks, without linking against EHM. */
final class EhmCombat {
    private static final String BASE = "ehm_base", MODULE = "ehm_module_base";
    static boolean applies(ShipVariantAPI variant) {
        return variant != null && (variant.hasHullMod(BASE) || variant.hasHullMod(MODULE));
    }

    private EhmCombat() {}

    static void capture(ShipAPI ship, ShipSpawn sp) {
        ShipVariantAPI v = ship.getVariant();
        if (!applies(v)) return;
        sp.fullVariant = true;
        sp.variantId = v.getHullVariantId();
        if (sp.kind == ShipSpawn.MODULE && ship.getParentStation() != null) {
            String id = ship.getParentStation().getVariant().getStationModules().get(sp.slotId);
            if (id != null && !id.isEmpty()) sp.variantId = id;
        }
        for (String slot : v.getFittedWeaponSlots()) add(sp, slot, v.getWeaponId(slot));
        ShipHullSpecAPI stock = Global.getSettings().getHullSpec(sp.hullId);
        for (Map.Entry<String, String> e : v.getHullSpec().getBuiltInWeapons().entrySet()) {
            if (!e.getValue().equals(stock.getBuiltInWeapons().get(e.getKey()))) add(sp, e.getKey(), e.getValue());
        }
        for (WeaponSlotAPI slot : v.getHullSpec().getAllWeaponSlotsCopy()) sp.slotState.add(slotState(slot));
    }

    private static void add(ShipSpawn sp, String slot, String id) {
        if (slot == null || id == null) throw new IllegalStateException("Missing fitted weapon input for " + sp.hullId);
        int old = sp.fittedSlots.indexOf(slot);
        if (old >= 0) {
            if (!id.equals(sp.fittedIds.get(old))) throw new IllegalStateException("Conflicting weapon input at " + slot);
            return;
        }
        sp.fittedSlots.add(slot);
        sp.fittedIds.add(id);
    }

    static void stage(ShipSpawn sp, ShipVariantAPI v) {
        if (!sp.fullVariant) return;
        if (!v.hasHullMod(BASE) && !v.hasHullMod(MODULE))
            throw new IllegalStateException("Required hull modification unavailable for " + sp.hullId);
        if (!v.getTags().containsAll(sp.variantTags) || !v.getHullMods().containsAll(sp.hullMods))
            throw new IllegalStateException("Missing variant tags or hull modifications for " + sp.hullId);
        for (int i = 0; i < sp.fittedSlots.size(); i++) {
            String slot = sp.fittedSlots.get(i), id = sp.fittedIds.get(i);
            if (Global.getSettings().getWeaponSpec(id) == null) throw new IllegalStateException("Missing weapon " + id);
            v.addWeapon(slot, id);
            if (!id.equals(v.getWeaponId(slot))) throw new IllegalStateException("Cannot restore weapon " + id + " at " + slot);
        }
    }

    static void check(ShipSpawn sp, ShipVariantAPI v) {
        if (!sp.fullVariant) return;
        for (int i = 0; i < sp.fittedSlots.size(); i++) {
            String slot = sp.fittedSlots.get(i), expected = sp.fittedIds.get(i);
            if (v.getSlot(slot) == null || !expected.equals(v.getWeaponId(slot)))
                throw new IllegalStateException("Reconstructed weapon mismatch at " + sp.hullId + "/" + slot);
        }
        java.util.Set<String> actual = new java.util.HashSet<String>();
        for (WeaponSlotAPI slot : v.getHullSpec().getAllWeaponSlotsCopy()) actual.add(slotState(slot));
        if (actual.size() != sp.slotState.size() || !actual.containsAll(sp.slotState))
            throw new IllegalStateException("Reconstructed slot mismatch for " + sp.hullId);
    }

    static void checkShip(ShipSpawn sp, ShipAPI ship) {
        if (!sp.fullVariant) return;
        check(sp, ship.getVariant());
        Map<String, String> weapons = new java.util.HashMap<String, String>();
        for (com.fs.starfarer.api.combat.WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon.getSlot() != null) weapons.put(weapon.getSlot().getId(), weapon.getId());
        }
        for (int i = 0; i < sp.weaponOrder.size(); i++) {
            String slot = sp.weaponOrder.get(i), id = sp.weaponOrderIds.get(i);
            if (!slot.isEmpty() && !id.isEmpty() && !id.equals(weapons.get(slot)))
                throw new IllegalStateException("Live weapon mismatch at " + sp.hullId + "/" + slot);
        }
    }

    private static String slotState(WeaponSlotAPI slot) {
        org.lwjgl.util.vector.Vector2f p = slot.getLocation();
        return slot.getId() + "\000" + slot.getWeaponType() + "\000" + slot.getSlotSize()
                + "\000" + slot.isHardpoint() + "\000" + slot.isTurret() + "\000" + slot.isHidden()
                + "\000" + slot.isBuiltIn() + "\000" + slot.isDecorative() + "\000" + slot.isSystemSlot()
                + "\000" + slot.isStationModule() + "\000" + Float.floatToIntBits(slot.getArc())
                + "\000" + Float.floatToIntBits(slot.getAngle()) + "\000" + (p == null ? "null" : Float.floatToIntBits(p.x) + ":" + Float.floatToIntBits(p.y));
    }
}

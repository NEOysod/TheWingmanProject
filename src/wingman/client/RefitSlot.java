package wingman.client;

import java.util.Collections;
import java.util.List;
import org.lwjgl.util.vector.Vector2f;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import wingman.net.RefitData;

/** Read-only client view of a host slot. */
public final class RefitSlot implements WeaponSlotAPI {
    final RefitData.Slot slot;
    RefitSlot(RefitData.Slot slot) { this.slot = slot; }
    public boolean isHardpoint() { return slot.hardpoint; }
    public boolean isTurret() { return !slot.hardpoint; }
    public boolean isHidden() { return false; }
    public boolean isSystemSlot() { return false; }
    public boolean isBuiltIn() { return slot.builtIn; }
    public boolean isDecorative() { return false; }
    public String getId() { return RefitData.local(slot.place); }
    public WeaponAPI.WeaponType getWeaponType() { return WeaponAPI.WeaponType.valueOf(slot.type); }
    public WeaponAPI.WeaponSize getSlotSize() { return WeaponAPI.WeaponSize.valueOf(slot.size); }
    public float getArc() { return slot.arc; }
    public void setArc(float arc) { throw new UnsupportedOperationException(); }
    public float getAngle() { return slot.angle; }
    public void setAngle(float angle) { throw new UnsupportedOperationException(); }
    public Vector2f getLocation() { return new Vector2f(slot.x, slot.y); }
    public Vector2f computePosition(CombatEntityAPI ship) { throw new UnsupportedOperationException(); }
    public boolean isStationModule() { return false; }
    public boolean isWeaponSlot() { return slot.editable || slot.builtIn; }
    public float getRenderOrderMod() { return 0f; }
    public void setRenderOrderMod(float value) { throw new UnsupportedOperationException(); }
    public float computeMidArcAngle(ShipAPI ship) { throw new UnsupportedOperationException(); }
    public List<Vector2f> getLaunchPointOffsets() { return Collections.emptyList(); }
    public boolean weaponFits(WeaponSpecAPI spec) {
        if (spec == null || spec.getSize().ordinal() > getSlotSize().ordinal()) return false;
        WeaponAPI.WeaponType weapon = spec.getMountType(), mount = getWeaponType();
        if (weapon == WeaponAPI.WeaponType.STATION_MODULE || weapon == WeaponAPI.WeaponType.SYSTEM || spec.getAIHints().contains(WeaponAPI.AIHints.SYSTEM)) return false;
        boolean same = weapon == mount;
        boolean mixed = compatible(weapon, mount) || compatible(mount, weapon);
        if (spec.isRestrictToSpecifiedMountType()) mixed = false;
        return (same || mixed) && (same ? spec.getSize().ordinal() >= getSlotSize().ordinal() - 1 : spec.getSize() == getSlotSize());
    }
    private static boolean compatible(WeaponAPI.WeaponType a, WeaponAPI.WeaponType b) {
        switch (a) {
            case HYBRID: return b == WeaponAPI.WeaponType.BALLISTIC || b == WeaponAPI.WeaponType.ENERGY || b == WeaponAPI.WeaponType.UNIVERSAL;
            case SYNERGY: return b == WeaponAPI.WeaponType.ENERGY || b == WeaponAPI.WeaponType.MISSILE || b == WeaponAPI.WeaponType.UNIVERSAL;
            case COMPOSITE: return b == WeaponAPI.WeaponType.BALLISTIC || b == WeaponAPI.WeaponType.MISSILE || b == WeaponAPI.WeaponType.UNIVERSAL;
            case UNIVERSAL: return b == WeaponAPI.WeaponType.BALLISTIC || b == WeaponAPI.WeaponType.ENERGY || b == WeaponAPI.WeaponType.MISSILE
                    || b == WeaponAPI.WeaponType.HYBRID || b == WeaponAPI.WeaponType.SYNERGY || b == WeaponAPI.WeaponType.COMPOSITE;
            default: return false;
        }
    }
}

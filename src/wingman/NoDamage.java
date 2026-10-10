package wingman;

import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;

/**
 * "This hit did no damage", for handing to a weapon's on-hit script when a joiner plays out a hit the host
 * reported. Damage on a joiner's screen is never real (the host's numbers are what count), but the script
 * still expects to be told something.
 */
final class NoDamage implements ApplyDamageResultAPI {
    private float hull, armor, cell, shields, emp, over;
    private DamageType type = DamageType.HIGH_EXPLOSIVE;
    private boolean dps;

    public float getDamageToHull() { return hull; }
    public float getTotalDamageToArmor() { return armor; }
    public float getDamageToPrimaryArmorCell() { return cell; }
    public float getDamageToShields() { return shields; }
    public void setDamageToHull(float v) { hull = v; }
    public void setTotalDamageToArmor(float v) { armor = v; }
    public void setDamageToPrimaryArmorCell(float v) { cell = v; }
    public void setDamageToShields(float v) { shields = v; }
    public float getEmpDamage() { return emp; }
    public void setEmpDamage(float v) { emp = v; }
    public DamageType getType() { return type; }
    public void setType(DamageType t) { type = t; }
    public float getOverMaxDamageToShields() { return over; }
    public void setOverMaxDamageToShields(float v) { over = v; }
    public boolean isDps() { return dps; }
    public void setDps(boolean v) { dps = v; }
}

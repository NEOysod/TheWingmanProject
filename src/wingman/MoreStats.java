package wingman;

import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.combat.StatBonus;

/**
 * Ship stats beyond ShipStats' fixed list (sent as MoreStats by name: Msg.STAT_NAMES, Msg.STATS_MORE).
 *
 * - "m:" + a getter of MutableShipStatsAPI: every stat the API has that can matter in a battle and that ShipStats
 *   doesn't already carry (damage taken and dealt by kind, malfunctions, repairs, armour rules, ammo and system
 *   uses, missile guidance, overload time, zero-flux at any level, beam speed...). Left out: the campaign's (fuel,
 *   cargo, crew, supplies, burn, sensors, repairs between battles), and the five Wingman sets itself on a copy to
 *   keep it unhurt (hull, armour and EMP damage taken, break-up, fighter refit time; 7.3).
 * - "d:" + key and "b:" + key: a mod's own named stats and bonuses (MutableShipStatsAPI.getDynamic()), which mods use
 *   for their own mechanics and read back in their scripts.
 *
 * A stat is compared and corrected as ShipStats does it: by its final value (a bonus at BASE), with one modifier of
 * Wingman's own on the joiner's copy.
 */
final class MoreStats {
    private MoreStats() {}

    static final String[] GETTERS = {
            "getWeaponMalfunctionChance",
            "getEngineMalfunctionChance",
            "getCriticalMalfunctionChance",
            "getShieldMalfunctionChance",
            "getShieldMalfunctionFluxLevel",
            "getFluxDamageTakenMult",
            "getShieldDamageTakenMult",
            "getEngineDamageTakenMult",
            "getWeaponDamageTakenMult",
            "getBeamDamageTakenMult",
            "getMissileDamageTakenMult",
            "getProjectileDamageTakenMult",
            "getEnergyDamageTakenMult",
            "getKineticDamageTakenMult",
            "getHighExplosiveDamageTakenMult",
            "getFragmentationDamageTakenMult",
            "getBeamShieldDamageTakenMult",
            "getMissileShieldDamageTakenMult",
            "getProjectileShieldDamageTakenMult",
            "getEnergyShieldDamageTakenMult",
            "getKineticShieldDamageTakenMult",
            "getHighExplosiveShieldDamageTakenMult",
            "getFragmentationShieldDamageTakenMult",
            "getBeamWeaponDamageMult",
            "getEnergyWeaponDamageMult",
            "getBallisticWeaponDamageMult",
            "getMissileWeaponDamageMult",
            "getShieldAbsorptionMult",
            "getCombatEngineRepairTimeMult",
            "getCombatWeaponRepairTimeMult",
            "getWeaponHealthBonus",
            "getEngineHealthBonus",
            "getArmorBonus",
            "getHullBonus",
            "getBallisticAmmoBonus",
            "getEnergyAmmoBonus",
            "getMissileAmmoBonus",
            "getEccmChance",
            "getMissileGuidance",
            "getHullCombatRepairRatePercentPerSecond",
            "getMaxCombatHullRepairFraction",
            "getEffectiveArmorBonus",
            "getHitStrengthBonus",
            "getDamageToTargetEnginesMult",
            "getDamageToTargetWeaponsMult",
            "getDamageToTargetShieldsMult",
            "getDamageToTargetHullMult",
            "getAutofireAimAccuracy",
            "getOverloadTimeMod",
            "getMissileTurnAccelerationBonus",
            "getMinArmorFraction",
            "getMaxArmorDamageReduction",
            "getMissileHealthBonus",
            "getSystemUsesBonus",
            "getKineticArmorDamageTakenMult",
            "getDamageToFighters",
            "getDamageToMissiles",
            "getDamageToFrigates",
            "getDamageToDestroyers",
            "getDamageToCruisers",
            "getDamageToCapital",
            "getCriticalMalfunctionDamageMod",
            "getFighterWingRange",
            "getRecoilPerShotMultSmallWeaponsOnly",
            "getEnergyWeaponFluxBasedBonusDamageMagnitude",
            "getEnergyWeaponFluxBasedBonusDamageMinLevel",
            "getAllowZeroFluxAtAnyLevel",
            "getBeamSpeedMod" };
    static final int COUNT = GETTERS.length;
    /** The wire names of the getters ("m:" + getter). */
    static final String[] NAMES = new String[COUNT];
    private static final java.util.Map<String, Integer> BY_NAME = new java.util.HashMap<String, Integer>();
    static {
        for (int i = 0; i < COUNT; i++) { NAMES[i] = "m:" + GETTERS[i]; BY_NAME.put(NAMES[i], i); }
    }

    /** The getter's stat: a MutableStat or a StatBonus (null if out of range). */
    static Object get(MutableShipStatsAPI s, int i) {
        switch (i) {
            case 0: return s.getWeaponMalfunctionChance();
            case 1: return s.getEngineMalfunctionChance();
            case 2: return s.getCriticalMalfunctionChance();
            case 3: return s.getShieldMalfunctionChance();
            case 4: return s.getShieldMalfunctionFluxLevel();
            case 5: return s.getFluxDamageTakenMult();
            case 6: return s.getShieldDamageTakenMult();
            case 7: return s.getEngineDamageTakenMult();
            case 8: return s.getWeaponDamageTakenMult();
            case 9: return s.getBeamDamageTakenMult();
            case 10: return s.getMissileDamageTakenMult();
            case 11: return s.getProjectileDamageTakenMult();
            case 12: return s.getEnergyDamageTakenMult();
            case 13: return s.getKineticDamageTakenMult();
            case 14: return s.getHighExplosiveDamageTakenMult();
            case 15: return s.getFragmentationDamageTakenMult();
            case 16: return s.getBeamShieldDamageTakenMult();
            case 17: return s.getMissileShieldDamageTakenMult();
            case 18: return s.getProjectileShieldDamageTakenMult();
            case 19: return s.getEnergyShieldDamageTakenMult();
            case 20: return s.getKineticShieldDamageTakenMult();
            case 21: return s.getHighExplosiveShieldDamageTakenMult();
            case 22: return s.getFragmentationShieldDamageTakenMult();
            case 23: return s.getBeamWeaponDamageMult();
            case 24: return s.getEnergyWeaponDamageMult();
            case 25: return s.getBallisticWeaponDamageMult();
            case 26: return s.getMissileWeaponDamageMult();
            case 27: return s.getShieldAbsorptionMult();
            case 28: return s.getCombatEngineRepairTimeMult();
            case 29: return s.getCombatWeaponRepairTimeMult();
            case 30: return s.getWeaponHealthBonus();
            case 31: return s.getEngineHealthBonus();
            case 32: return s.getArmorBonus();
            case 33: return s.getHullBonus();
            case 34: return s.getBallisticAmmoBonus();
            case 35: return s.getEnergyAmmoBonus();
            case 36: return s.getMissileAmmoBonus();
            case 37: return s.getEccmChance();
            case 38: return s.getMissileGuidance();
            case 39: return s.getHullCombatRepairRatePercentPerSecond();
            case 40: return s.getMaxCombatHullRepairFraction();
            case 41: return s.getEffectiveArmorBonus();
            case 42: return s.getHitStrengthBonus();
            case 43: return s.getDamageToTargetEnginesMult();
            case 44: return s.getDamageToTargetWeaponsMult();
            case 45: return s.getDamageToTargetShieldsMult();
            case 46: return s.getDamageToTargetHullMult();
            case 47: return s.getAutofireAimAccuracy();
            case 48: return s.getOverloadTimeMod();
            case 49: return s.getMissileTurnAccelerationBonus();
            case 50: return s.getMinArmorFraction();
            case 51: return s.getMaxArmorDamageReduction();
            case 52: return s.getMissileHealthBonus();
            case 53: return s.getSystemUsesBonus();
            case 54: return s.getKineticArmorDamageTakenMult();
            case 55: return s.getDamageToFighters();
            case 56: return s.getDamageToMissiles();
            case 57: return s.getDamageToFrigates();
            case 58: return s.getDamageToDestroyers();
            case 59: return s.getDamageToCruisers();
            case 60: return s.getDamageToCapital();
            case 61: return s.getCriticalMalfunctionDamageMod();
            case 62: return s.getFighterWingRange();
            case 63: return s.getRecoilPerShotMultSmallWeaponsOnly();
            case 64: return s.getEnergyWeaponFluxBasedBonusDamageMagnitude();
            case 65: return s.getEnergyWeaponFluxBasedBonusDamageMinLevel();
            case 66: return s.getAllowZeroFluxAtAnyLevel();
            case 67: return s.getBeamSpeedMod();
            default: return null;
        }
    }

    /** The final value of a stat or bonus (a bonus at ShipStats.BASE); NaN if it can't be read. */
    static float value(Object o) {
        try {
            if (o instanceof MutableStat) return ((MutableStat) o).getModifiedValue();
            if (o instanceof StatBonus) return ((StatBonus) o).computeEffective(ShipStats.BASE);
        } catch (Throwable t) { /* NaN */ }
        return Float.NaN;
    }

    /** The stat a wire name stands for on this ship (a named stat is made if this game hasn't one yet); null if none. */
    static Object resolve(MutableShipStatsAPI s, String name) {
        if (name == null || name.length() < 3) return null;
        try {
            if (name.startsWith("m:")) {
                Integer i = BY_NAME.get(name);
                return i == null ? null : get(s, i);
            }
            if (name.startsWith("d:")) return s.getDynamic().getStat(name.substring(2));
            if (name.startsWith("b:")) return s.getDynamic().getMod(name.substring(2));
        } catch (Throwable t) { /* none */ }
        return null;
    }

    /** Make the stat's final value here the host's; NaN: the host's is gone, so ours is left to this game. True if changed. */
    static boolean force(Object o, float host) { return force(o, host, Float.NaN); }

    /** As above; for a bonus, hostAt0 is the host's value at base 0 (NaN if not known), which makes it right at every base. */
    static boolean force(Object o, float host, float hostAt0) {
        if (o instanceof MutableStat) {
            if (host != host) { ((MutableStat) o).unmodify(ShipStats.KEY); return false; }
            return ShipStats.forceStat((MutableStat) o, host);
        }
        if (o instanceof StatBonus) {
            if (host != host) { ((StatBonus) o).unmodify(ShipStats.KEY); return false; }
            return ShipStats.forceBonus((StatBonus) o, host, hostAt0);
        }
        return false;
    }
}

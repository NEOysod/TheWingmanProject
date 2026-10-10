package wingman;

import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.combat.StatBonus;

/**
 * The ship stats that are kept equal between the host's ship and a joiner's copy of it. The host reads each
 * one's final value; the joiner adds one correction of its own to the stat so that its final value comes out
 * the same, whatever its own hullmods, skills and scripts made of it. Stats that only matter to damage are left
 * out: damage is the host's alone.
 */
final class ShipStats {
    private ShipStats() {}

    static final int COUNT = 43;
    private static final String KEY = "wingman_host";
    /** A bonus is measured by what it makes of this base value. */
    private static final float BASE = 1000f;

    private static MutableStat plain(MutableShipStatsAPI s, int i) {
        switch (i) {
            case 0: return s.getMaxSpeed();
            case 1: return s.getAcceleration();
            case 2: return s.getDeceleration();
            case 3: return s.getMaxTurnRate();
            case 4: return s.getTurnAcceleration();
            case 5: return s.getFluxCapacity();
            case 6: return s.getFluxDissipation();
            case 7: return s.getBallisticRoFMult();
            case 8: return s.getEnergyRoFMult();
            case 9: return s.getMissileRoFMult();
            case 10: return s.getBallisticProjectileSpeedMult();
            case 11: return s.getEnergyProjectileSpeedMult();
            case 12: return s.getProjectileSpeedMult();
            case 13: return s.getTimeMult();
            case 14: return s.getZeroFluxSpeedBoost();
            case 15: return s.getShieldTurnRateMult();
            case 16: return s.getShieldUnfoldRateMult();
            case 17: return s.getVentRateMult();
            case 18: return s.getHardFluxDissipationFraction();
            case 19: return s.getZeroFluxMinimumFluxLevel();
            case 36: return s.getRecoilPerShotMult();
            case 37: return s.getMaxRecoilMult();
            case 38: return s.getRecoilDecayMult();
            default: return null;
        }
    }

    private static StatBonus bonus(MutableShipStatsAPI s, int i) {
        switch (i) {
            case 20: return s.getBallisticWeaponRangeBonus();
            case 21: return s.getEnergyWeaponRangeBonus();
            case 22: return s.getMissileWeaponRangeBonus();
            case 23: return s.getBeamWeaponRangeBonus();
            case 24: return s.getWeaponTurnRateBonus();
            case 25: return s.getBeamWeaponTurnRateBonus();
            case 26: return s.getShieldArcBonus();
            case 27: return s.getSightRadiusMod();
            case 28: return s.getMissileMaxSpeedBonus();
            case 29: return s.getMissileAccelerationBonus();
            case 30: return s.getMissileMaxTurnRateBonus();
            case 31: return s.getSystemRangeBonus();
            case 32: return s.getSystemCooldownBonus();
            case 33: return s.getSystemRegenBonus();
            case 34: return s.getNonBeamPDWeaponRangeBonus();
            case 35: return s.getBeamPDWeaponRangeBonus();
            case 39: return s.getDynamic().getMod(com.fs.starfarer.api.impl.campaign.ids.Stats.PHASE_CLOAK_SPEED_MOD);
            case 40: return s.getPeakCRDuration();
            case 41: return s.getPhaseCloakCooldownBonus();
            case 42: return s.getCRLossPerSecondPercent();
            default: return null;
        }
    }

    /** The stat's final value as it stands (host side). NaN if it can't be read. */
    static float read(MutableShipStatsAPI s, int i) {
        try {
            MutableStat m = plain(s, i);
            if (m != null) return m.getModifiedValue();
            StatBonus b = bonus(s, i);
            return b == null ? Float.NaN : b.computeEffective(BASE);
        } catch (Throwable t) {
            return Float.NaN;
        }
    }

    /** True if two values of a stat are near enough not to bother. */
    static boolean same(float a, float b) {
        return Math.abs(a - b) <= Math.max(0.0005f, Math.abs(b) * 0.005f);
    }

    /**
     * Make the stat's final value here equal the host's (joiner side). Our own earlier correction is taken off
     * first, so that whatever this game's scripts have done to the stat since is what gets corrected.
     * Returns true if a correction was needed.
     */
    static boolean force(MutableShipStatsAPI s, int i, float host) {
        if (host != host || Math.abs(host) > 1.0e7f) return false; // unknown, or nonsense
        try {
            MutableStat m = plain(s, i);
            if (m != null) {
                m.unmodify(KEY);
                float local = m.getModifiedValue();
                if (same(local, host)) return false;
                if (Math.abs(local) > 0.0001f && host / local > 0f) m.modifyMult(KEY, host / local);
                else m.modifyFlat(KEY, host - local);
                return true;
            }
            StatBonus b = bonus(s, i);
            if (b == null) return false;
            b.unmodify(KEY);
            float local = b.computeEffective(BASE);
            if (same(local, host)) return false;
            if (Math.abs(local) > 0.0001f && host / local > 0f) b.modifyMult(KEY, host / local);
            else b.modifyFlat(KEY, host - local);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static final String[] NAMES = { "top speed", "acceleration", "deceleration", "turn rate", "turn acceleration", "flux capacity",
            "flux dissipation", "ballistic rate of fire", "energy rate of fire", "missile rate of fire", "ballistic shot speed", "energy shot speed",
            "shot speed", "time flow", "zero-flux boost", "shield turn rate", "shield unfold rate", "vent rate", "hard flux dissipation",
            "zero-flux threshold", "ballistic range", "energy range", "missile range", "beam range", "weapon turn rate", "beam turn rate",
            "shield arc", "sight radius", "missile speed", "missile acceleration", "missile turn rate", "system range", "system cooldown",
            "system recharge", "point-defence range", "beam point-defence range", "recoil per shot", "greatest recoil", "recoil recovery",
            "speed while phased", "peak performance time", "phase cloak cooldown", "readiness loss rate" };

    static String name(int i) { return i >= 0 && i < NAMES.length ? NAMES[i] : "stat " + i; }
}

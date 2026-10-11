package testmod;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;

/** A weapon's every-frame script and a ship system's script. */
public class Gun implements EveryFrameWeaponEffectPlugin {
    public ShipAPI saw;
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) { saw = engine.getPlayerShip(); }

    public static class Sys extends BaseShipSystemScript {
        public ShipAPI saw;
        public long wide; public double big;
        @Override
        public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) { saw = Global.getCombatEngine().getPlayerShip(); }
    }
}

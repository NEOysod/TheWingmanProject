package testmod;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;

/** A hullmod as mods write them: does something "for the player's ship" in advanceInCombat. */
public class Hull extends BaseHullMod {
    public ShipAPI saw;
    public boolean fail;
    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        saw = Global.getCombatEngine().getPlayerShip();
        if (fail) throw new IllegalStateException("a mod's mistake");
    }

    /** A subclass that overrides and calls super, as mods often do. */
    public static class Sub extends Hull {
        public ShipAPI sawToo;
        @Override
        public void advanceInCombat(ShipAPI ship, float amount) {
            super.advanceInCombat(ship, amount);
            sawToo = Global.getCombatEngine().getPlayerShip();
        }
    }
}

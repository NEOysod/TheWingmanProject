package wingman;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.FighterWingAPI;
import com.fs.starfarer.api.combat.ShipAPI;

/**
 * A fighter a joiner flies (UAF's Isenoria Manual Override hands the player one) must answer their controls.
 *
 * The game locks a fighter's controls ("Ship is not combat-ready, controls are locked") when its wing was launched
 * with less readiness than a ship needs to deploy, and remembers that verdict. The launch readiness is fixed when
 * the wing is made (from the fleet record it is made from; see spawnFighter, which gives fighters at least 70%), so
 * it can't be raised afterwards. Here the remembered verdict is dropped once, in case it was given before the
 * fighter was ready, and if the fighter is still locked the reason goes in the log.
 */
final class FighterUnlock {
    private FighterUnlock() {}

    private static int told;

    static void unlock(ShipAPI fighter) {
        if (fighter == null || !fighter.isFighter() || !fighter.controlsLocked()) return;
        try {
            fighter.setControlsLocked(false); // judged again the next time the game asks
            if (!fighter.controlsLocked() || told++ > 2) return;
            FighterWingAPI wing = fighter.getWing();
            Global.getLogger(FighterUnlock.class).warn("Wingman: the fighter in our hands (" + fighter.getHullSpec().getHullId()
                    + ") has locked controls: its wing's launch readiness " + (wing == null ? "none (no wing)" : String.valueOf(Math.round(getLaunchCR(wing) * 100f)) + "%")
                    + ", readiness at deployment " + Math.round(fighter.getCRAtDeployment() * 100f) + "%, now " + Math.round(fighter.getCurrentCR() * 100f) + "%");
        } catch (Throwable t) { /* stays as the game has it */ }
    }

    private static float getLaunchCR(FighterWingAPI wing) {
        try {
            java.lang.invoke.MethodHandle h = java.lang.invoke.MethodHandles.publicLookup().findVirtual(wing.getClass(), "getLaunchCR",
                    java.lang.invoke.MethodType.methodType(float.class));
            return (float) h.invoke(wing);
        } catch (Throwable t) {
            return -1f;
        }
    }
}

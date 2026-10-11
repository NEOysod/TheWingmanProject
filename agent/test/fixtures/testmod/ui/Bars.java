package testmod.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAPI;

/** Interface code (a "ui" package): left alone, so its "is this the player's ship?" stays this PC's player. */
public class Bars {
    public static boolean shouldDraw(ShipAPI s) { return s == Global.getCombatEngine().getPlayerShip(); }
}

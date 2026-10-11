package testmod;

import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.input.InputEventAPI;

/** Like a "slow time while the player flies this" script: added from a ship's frame, keeps the ship. */
public class TimeScript extends BaseEveryFrameCombatPlugin {
    private final ShipAPI ship;
    public ShipAPI sawPlayer;
    public TimeScript(ShipAPI ship) { this.ship = ship; }
    public void advance(float amount, List<InputEventAPI> events) {
        sawPlayer = Global.getCombatEngine().getPlayerShip();
        if (ship == sawPlayer) Global.getCombatEngine().getTimeMult().modifyMult("slow", 0.25f);
    }
    /** A battle-wide manager added lazily from whichever ship's frame runs first: holds no ship. */
    public static class Manager extends BaseEveryFrameCombatPlugin {
        public ShipAPI sawPlayer;
        public void advance(float amount, List<InputEventAPI> events) { sawPlayer = Global.getCombatEngine().getPlayerShip(); }
    }
    public static void addBoth(ShipAPI ship, TimeScript[] t, Manager[] m) {
        t[0] = new TimeScript(ship);
        m[0] = new Manager();
        Global.getCombatEngine().addPlugin(t[0]);
        Global.getCombatEngine().addPlugin(m[0]);
    }
}

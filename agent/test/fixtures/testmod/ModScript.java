package testmod;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.combat.ShipAPI;

/** Shaped like ordinary mod code: every call the agent swaps, as a mod's script would make it. */
public class ModScript {
    public static ShipAPI who(CombatEngineAPI e) { return e.getPlayerShip(); }
    public static boolean isPlayer(CombatEngineAPI e, ShipAPI s) { return s == e.getPlayerShip(); }
    public static void move(CombatEngineAPI e, ShipAPI s) { e.setPlayerShipExternal(s); }
    public static void status(CombatEngineAPI e) { e.maintainStatusForPlayerShip("k", "icon", "title", "data", false); }
    public static MutableStat time(CombatEngineAPI e) { return e.getTimeMult(); }
    public static int mx() { return org.lwjgl.input.Mouse.getX(); }
    public static int my() { return org.lwjgl.input.Mouse.getY(); }
    public static boolean key(int k) { return org.lwjgl.input.Keyboard.isKeyDown(k); }
    /** Camera and interface sound, as a script does "for the player". */
    public static void camera(CombatEngineAPI e, org.lwjgl.util.vector.Vector2f c) { e.getViewport().setExternalControl(true); e.getViewport().setCenter(c); }
    public static void beep() { com.fs.starfarer.api.Global.getSoundPlayer().playUISound("ui_beep", 1f, 1f); }
    public static boolean byHand(CombatEngineAPI e) { return e.isUIAutopilotOn(); }
    /** Like MagicUI's guard: draws only for the player's ship. */
    public static boolean wouldDraw(ShipAPI s) { return testmod.ui.Bars.shouldDraw(s); }
    /** A move, then asking again in the same script: the answer follows the move. */
    public static ShipAPI moveThenAsk(CombatEngineAPI e, ShipAPI s) { e.setPlayerShipExternal(s); return e.getPlayerShip(); }
    /** Calls inside a switch, a try and a loop, so the walk over the code meets them all. */
    public static int busy(CombatEngineAPI e, int k) {
        int n = 0;
        switch (k) { case 1: n += 1; break; case 2: n += 2; break; case 900: n += 3; break; default: n += 4; }
        switch (k) { case 0: case 1: case 2: case 3: n++; break; default: break; }
        long big = 1L << 40; double d = 2.5;
        try { if (e.getPlayerShip() != null) n += 10; } catch (RuntimeException x) { n = -1; }
        for (int i = 0; i < 3; i++) if (org.lwjgl.input.Keyboard.isKeyDown(k + i)) n += 100;
        return n + (int) (big >>> 40) + (int) d;
    }
}

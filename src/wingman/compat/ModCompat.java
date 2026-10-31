package wingman.compat;

import com.fs.starfarer.api.combat.ShipVariantAPI;

/**
 * What other mods need from the friend refit. Each mod's rules live in a class of its own here; they key on that
 * mod's own hull and hull mod ids, so a mod that isn't loaded never matches.
 */
public final class ModCompat {
    private ModCompat() {}

    /**
     * For a hidden hull mod that is really a loadout switch (taking it off picks the next loadout): what taking it off
     * does, shown to the joiner. Null for any other hull mod.
     */
    public static String switchNote(String hullmodId) {
        return DiableAvionics.switchNote(hullmodId);
    }

    /**
     * Runs on the host after a request's hull mods are put on a variant (the copy for the checks and the new one alike):
     * the switches other mods would make when the ship is next built, made now so the joiner sees the result.
     */
    public static void afterHullmods(ShipVariantAPI v) {
        DiableAvionics.settle(v);
    }

    /** Whether Exotica Technologies is loaded and usable: the refit screen then has an EXOTICA button. */
    public static boolean exoticaEnabled() {
        return Exotica.available();
    }

    /** Joiner: opens Exotica's menu on the host's ship (the ship message). Null when open, else why not. */
    public static String exoticaOpen(Exotica.ShipMsg msg) {
        return Exotica.open(msg);
    }

    public static boolean exoticaActive() { return Exotica.active(); }

    /** Joiner: each frame while the menu is open; false if it failed and closed. */
    public static boolean exoticaFrame(float dt) { return Exotica.frame(dt); }

    public static void exoticaInput(java.util.List<com.fs.starfarer.api.input.InputEventAPI> events) { Exotica.input(events); }

    public static void exoticaRender() { Exotica.render(); }

    /** Joiner: closes the menu; the steps bought (null if it was not open). */
    public static Exotica.Closed exoticaClose() { return Exotica.close(); }

    /** The menu's place on screen: x, y, width, height. */
    public static float[] exoticaRect() { return Exotica.rect(); }

    /** Host: the ship message for an ask. */
    public static byte[] exoticaShip(com.fs.starfarer.api.fleet.FleetMemberAPI m, com.fs.starfarer.api.campaign.econ.MarketAPI market) throws Throwable {
        return Exotica.buildShip(m, market);
    }

    /** Host: plays a joiner's steps on the ship through Exotica's own methods. */
    public static Exotica.Outcome exoticaReplay(com.fs.starfarer.api.fleet.FleetMemberAPI m, com.fs.starfarer.api.campaign.econ.MarketAPI market,
            java.util.List<Exotica.Step> steps) throws Throwable {
        return Exotica.replay(m, market, steps);
    }
}

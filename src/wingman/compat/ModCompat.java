package wingman.compat;

import com.fs.starfarer.api.combat.ShipVariantAPI;

import wingman.net.RefitPlan;

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

    /**
     * Whether a refit only takes off loadout selectors this class knows (ModCompat.switchNote): that mod's own free
     * switch, which costs nothing and takes nothing from the host, so the host does it without being asked.
     */
    public static boolean onlySwitchOffs(RefitPlan p) {
        if (!p.onlyTakesModsOff()) return false;
        for (String id : p.r.removeMods) if (switchNote(id) == null) return false;
        return true;
    }

    /** Capture per-ship reconstruction inputs for mods that alter a hull spec. */
    public static void captureCombatVariant(com.fs.starfarer.api.combat.ShipAPI ship, wingman.net.ShipSpawn spawn) {
        EhmCombat.capture(ship, spawn);
    }

    public static boolean requiresCombatVariant(ShipVariantAPI variant) {
        return EhmCombat.applies(variant);
    }

    /** Restore mod-specific variant inputs before the game's first stats calculation. */
    public static void stageCombatVariant(wingman.net.ShipSpawn spawn, ShipVariantAPI variant) {
        EhmCombat.stage(spawn, variant);
    }

    /** Reject a replica when the reconstructed hull or loadout differs from the host. */
    public static void checkCombatVariant(wingman.net.ShipSpawn spawn, ShipVariantAPI variant) {
        EhmCombat.check(spawn, variant);
    }

    public static void checkCombatShip(wingman.net.ShipSpawn spawn, com.fs.starfarer.api.combat.ShipAPI ship) {
        EhmCombat.checkShip(spawn, ship);
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

    /** Whether Bionic Alteration is loaded and usable: a linked captain then has a BIONICS button in the lobby. */
    public static boolean bionicsEnabled() {
        return Bionics.available();
    }

    /** Joiner: opens the mod's workshop on the host's captain (the captain message). Null when open, else why not. */
    public static String bionicsOpen(Bionics.ShipMsg msg) { return Bionics.open(msg); }

    public static boolean bionicsActive() { return Bionics.active(); }

    /** Joiner: each frame while the workshop is open; false if it failed and closed. */
    public static boolean bionicsFrame(float dt) { return Bionics.frame(dt); }

    public static void bionicsInput(java.util.List<com.fs.starfarer.api.input.InputEventAPI> events) { Bionics.input(events); }

    public static void bionicsRender() { Bionics.render(); }

    /** Joiner: closes the workshop; the changes made (null if it was not open). */
    public static Bionics.Closed bionicsClose() { return Bionics.close(); }

    /** The workshop's place on screen: x, y, width, height. */
    public static float[] bionicsRect() { return Bionics.rect(); }

    /** Host: the captain message for an ask. */
    public static byte[] bionicsShip(com.fs.starfarer.api.characters.PersonAPI person) throws Throwable { return Bionics.buildShip(person); }

    /** Host: plays a joiner's changes on their captain through the mod's own methods. */
    public static Bionics.Outcome bionicsReplay(com.fs.starfarer.api.characters.PersonAPI person, long revision,
            java.util.List<Bionics.Step> steps) throws Throwable {
        return Bionics.replay(person, revision, steps);
    }

    /** Host: puts a captain's bionics on the description the joiners get, so their ships behave the same. */
    public static void captureBionics(com.fs.starfarer.api.characters.PersonAPI captain, wingman.net.Person copy) {
        Bionics.capture(captain, copy);
    }

    /** Joiner: gives a battle's captain the host's bionics before the ship is built. */
    public static void stageBionics(com.fs.starfarer.api.characters.PersonAPI captain) {
        Bionics.stage(captain);
    }

    /**
     * Host, each frame of a battle: Secrets of the Frontier's Fel has just infested one of the enemy's ships, and
     * this is the Msg.MOD payload that names it to the joiners. Null otherwise; at most once a battle.
     */
    public static byte[] felNews(com.fs.starfarer.api.combat.CombatEngineAPI engine,
            java.util.Map<com.fs.starfarer.api.combat.ShipAPI, Integer> ids) {
        return SotfFel.news(engine, ids);
    }

    /** Host: that payload again for a joiner just sent the whole battle, while Fel's ship still fights; else null. */
    public static byte[] felState(com.fs.starfarer.api.combat.CombatEngineAPI engine) { return SotfFel.state(engine); }

    /** Joiner, before asking the host for the battle: hear which ship Fel infested. */
    public static void felListen() { SotfFel.listen(); }

    /** Joiner: the net id of the ship the host said Fel infested, while its copy here is awaited; -1 if none. */
    public static int felWanted() { return SotfFel.wantedId(); }

    /** Joiner: the copy of that ship (null if not here yet), on which SotF's own infestation is then shown. */
    public static void felShow(com.fs.starfarer.api.combat.CombatEngineAPI engine, com.fs.starfarer.api.combat.ShipAPI ship) {
        SotfFel.show(engine, ship);
    }
}

package wingman.agent;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;

/**
 * What the swapped calls (see {@link Patcher}) go to instead.
 *
 * The idea: in a co-op battle, a ship another person flies is, on this PC, an ordinary AI ship (on the host: a
 * joiner's ship; on a joiner: the host's ship and the other joiners'). So scripts that do something for "the
 * player's ship" never do it for that ship here, and every PC disagrees about who the player is. While the
 * game runs a frame of a ship a person on another PC flies (its hullmods, ship system, weapons' scripts,
 * MagicLib subsystems), that ship is the <i>((ShipAPI) Shared.acting)</i> ship, and in code running for it:
 * <ul>
 * <li>{@code engine.getPlayerShip()} answers the ((ShipAPI) Shared.acting) ship, so "if this is the player's ship" is true for
 * it, as it is on its pilot's own screen;</li>
 * <li>{@code engine.setPlayerShipExternal(s)} goes to the Shared.bridge: on the host the joiner is moved into s
 * (Wingman claims it for them); on a joiner nothing happens here (the host decides);</li>
 * <li>the mouse is that person's cursor (where they point, on this screen), and no key is ever this PC's;</li>
 * <li>things only meant for one player's own screen and game speed do nothing here: the status line in the
 * corner, the camera, interface sounds, and the battle's time multiplier (a script slowing time for "the
 * player" would slow the battle for everyone).</li>
 * </ul>
 * Everything else, and everything outside an ((ShipAPI) Shared.acting) ship's frame (screen-wide plugins, this PC's own player's
 * ship), gets the real answer. With no Wingman Shared.bridge set (not in a co-op battle), nothing is changed.
 *
 * All of this is on the game thread; the one static field is fine.
 */
public final class Hooks {
    private Hooks() {}


    public static void setBridge(Bridge b) { Shared.bridge = b; Shared.acting = null; Shared.owners.clear(); }
    public static boolean installed() { return true; }
    public static Object acting() { return Shared.acting; }

    /** The mark Wingman's host writes on a ship a joiner flies (see wingman.PilotMarks). */
    static final String REMOTE = "wingman_remote";

    /** Whose frame this is: the ship itself if a joiner flies it; for a module or a fighter, the ship it belongs to. */
    static ShipAPI actingFor(ShipAPI s) {
        Bridge b = Shared.bridge;
        if (s == null || b == null) return null;
        try {
            if (!b.live()) return null;
            if (remote(s)) return s;
            ShipAPI parent = s.getParentStation();
            if (parent != null && remote(parent)) return parent;
            if (s.isFighter() && s.getWing() != null) {
                ShipAPI src = s.getWing().getSourceShip();
                if (src != null && remote(src)) return src;
            }
        } catch (Throwable t) { /* never break the game's frame */ }
        return null;
    }

    private static boolean remote(ShipAPI s) {
        Map<String, Object> d = s.getCustomData();
        return d != null && d.containsKey(REMOTE);
    }

    /** Run something with this ship ((ShipAPI) Shared.acting) (Wingman uses it when it fires a joiner's ability itself). */
    public static void runAs(ShipAPI ship, Runnable r) {
        ShipAPI prev = ((ShipAPI) Shared.acting);
        Shared.acting = actingFor(ship);
        try { r.run(); } finally { Shared.acting = prev; }
    }

    /**
     * Run something for a ship Wingman knows a joiner flies (it fires their ability itself): that ship acts, even
     * if its mark isn't written yet (they got it this frame).
     */
    public static void runAsPilot(Object ship, Runnable r) {
        Bridge b = Shared.bridge;
        Object prev = Shared.acting;
        boolean live = false;
        try { live = b != null && ship != null && b.live(); } catch (Throwable t) { /* as not live */ }
        Shared.acting = live ? ship : null;
        try { r.run(); } finally { Shared.acting = prev; }
    }

    // ---- the engine's per-ship frame ----

    public static void shipAdvance(com.fs.starfarer.combat.entities.Ship ship, float amount) {
        if (Shared.bridge == null) { ship.advance(amount); return; }
        ShipAPI prev = ((ShipAPI) Shared.acting);
        ShipAPI now = actingFor(ship);
        if (now != null) Shared.contexts++;
        Shared.acting = now;
        try { ship.advance(amount); } finally { Shared.acting = prev; }
    }

    /**
     * MagicLib advances every ship's subsystems from one screen-wide plugin: give each its ship's context. (No
     * java.lang.reflect here: this class may be a copy in the game's script loader, which refuses it. Shared does
     * the looking.)
     */
    public static void subsystemAdvance(Object subsystem, float amount) {
        ShipAPI prev = ((ShipAPI) Shared.acting);
        if (Shared.bridge != null) {
            try { Shared.acting = actingFor((ShipAPI) Shared.field(subsystem, "org.magiclib.subsystems.MagicSubsystem", "ship")); } catch (Throwable t) { Shared.acting = null; }
        }
        try {
            Shared.callFloat(subsystem, "org.magiclib.subsystems.MagicSubsystem", "advanceInternal", amount);
        } finally {
            Shared.acting = prev;
        }
    }

    /**
     * Screen-wide plugins a script added while running for a joiner's ship, and that keep hold of that ship (a
     * "slow time down while the player flies a fighter" script, say): their Shared.frames run for that ship too, as
     * long as a joiner still flies it. A plugin that holds no such ship is left alone: that is how a mod's
     * battle-wide manager looks when it happens to be added from the first ship whose frame runs, and it must
     * stay the host's. Weak keys: a plugin removed from the battle is forgotten with it.
     */

    public static void addPlugin(CombatEngineAPI engine, EveryFrameCombatPlugin plugin) {
        ShipAPI a = ((ShipAPI) Shared.acting);
        if (a != null && plugin != null && holds(plugin, a)) { Shared.owners.put(plugin, a); Shared.pluginsOwned++; }
        engine.addPlugin(plugin);
    }

    private static boolean same(ShipAPI x, ShipAPI y) { return x != null && x == y; }

    /** Does this object keep, in a field of its own, the ((ShipAPI) Shared.acting) ship (or one of its modules or fighters)? */
    static boolean holds(Object o, ShipAPI a) {
        try {
            for (Object v : Shared.fieldsOfType(o, ShipAPI.class)) {
                if (v == a || (v instanceof ShipAPI && same(actingFor((ShipAPI) v), actingFor(a)))) return true;
            }
        } catch (Throwable t) { /* can't look: leave it alone */ }
        return false;
    }

    public static void pluginAdvance(EveryFrameCombatPlugin plugin, float amount, List events) {
        ShipAPI owner = Shared.bridge == null || Shared.owners.isEmpty() ? null : (ShipAPI) Shared.owners.get(plugin);
        if (owner == null) { plugin.advance(amount, events); return; }
        ShipAPI prev = ((ShipAPI) Shared.acting);
        Shared.acting = actingFor(owner);
        try { plugin.advance(amount, events); } finally { Shared.acting = prev; }
    }

    // ---- mods' per-ship methods, wrapped (Patcher.Frame) ----

    /**
     * A wrapped method of a mod starts: as long as it runs, the ship it runs for is the ((ShipAPI) Shared.acting) ship (if a person
     * on another PC flies it; otherwise none). kind: 0 the ship itself, 1 a ship's stats, 2 a weapon, 3 a beam,
     * 4 a plugin (only one added for a joiner's ship, see addPlugin), 5 a projectile. Returns the ((ShipAPI) Shared.acting) ship
     * before, for exitFrame. Without a Shared.bridge nothing changes.
     */
    public static Object enterFrame(Object ctx, int kind) {
        ShipAPI prev = ((ShipAPI) Shared.acting);
        if (Shared.bridge == null || ctx == null) return prev;
        try {
            ShipAPI s = null;
            switch (kind) {
                case 0: s = (ShipAPI) ctx; break;
                case 1: {
                    Object e = ((com.fs.starfarer.api.combat.MutableShipStatsAPI) ctx).getEntity();
                    s = e instanceof ShipAPI ? (ShipAPI) e : null;
                    break;
                }
                case 2: s = ((com.fs.starfarer.api.combat.WeaponAPI) ctx).getShip(); break;
                case 3: s = ((com.fs.starfarer.api.combat.BeamAPI) ctx).getSource(); break;
                case 4: {
                    if (Shared.owners.isEmpty()) return prev;
                    s = (ShipAPI) Shared.owners.get(ctx);
                    if (s == null) return prev; // not a joiner's plugin: as it was
                    break;
                }
                case 5: s = ((com.fs.starfarer.api.combat.DamagingProjectileAPI) ctx).getSource(); break;
                default: return prev;
            }
            if (s == null) return prev;
            ShipAPI now = actingFor(s);
            if (now != null) Shared.frames++;
            Shared.acting = now;
        } catch (Throwable t) { /* never break the mod's call */ }
        return prev;
    }

    public static void exitFrame(Object prev) {
        Shared.acting = prev;
    }

    // ---- what scripts ask ----

    public static ShipAPI getPlayerShip(CombatEngineAPI engine) {
        ShipAPI a = ((ShipAPI) Shared.acting);
        if (a != null) { Shared.playerShipAnswers++; return a; }
        return engine.getPlayerShip();
    }

    public static void setPlayerShipExternal(CombatEngineAPI engine, ShipAPI ship) {
        ShipAPI a = ((ShipAPI) Shared.acting);
        Bridge b = Shared.bridge;
        if (a != null && b != null) {
            if (ship == a || ship == null) return;
            Shared.moves++;
            boolean moved;
            try { moved = b.moveRemote(a, ship); } catch (Throwable t) { moved = false; }
            if (moved) Shared.acting = ship; // the rest of this script runs for the ship the joiner is moving to
            return;
        }
        engine.setPlayerShipExternal(ship);
    }

    public static void maintainStatusForPlayerShip(CombatEngineAPI engine, Object key, String sprite, String title, String data, boolean debuff) {
        if (((ShipAPI) Shared.acting) != null) { Shared.statusDropped++; return; } // the joiner's own game shows it on their screen
        engine.maintainStatusForPlayerShip(key, sprite, title, data, debuff);
    }

    public static MutableStat getTimeMult(CombatEngineAPI engine) {
        if (((ShipAPI) Shared.acting) != null) {
            // The host's speed is everyone's: a copy, reading as the real one, that changes nothing.
            Shared.timeDropped++;
            float now = 1f;
            try { now = engine.getTimeMult().getModifiedValue(); } catch (Throwable t) { /* 1 then */ }
            return new MutableStat(now);
        }
        return engine.getTimeMult();
    }

    /**
     * "Is the player flying by hand?" (the game's {@code isUIAutopilotOn()}, true when they are). Scripts pair it
     * with "is this the player's ship?" to choose between the player's keys and their own AI logic: for someone
     * else's ship, it is that person's autopilot, not this PC's player's.
     */
    public static boolean autopilotOn(CombatEngineAPI engine) {
        ShipAPI a = ((ShipAPI) Shared.acting);
        Bridge b = Shared.bridge;
        if (a != null && b != null) {
            try { return b.manual(a); } catch (Throwable t) { return true; }
        }
        return engine.isUIAutopilotOn();
    }

    // ---- this PC's own screen: the camera and interface sounds ----

    public static void viewSet(ViewportAPI v, float llx, float lly, float w, float h) {
        if (((ShipAPI) Shared.acting) != null) { Shared.screenDropped++; return; }
        v.set(llx, lly, w, h);
    }

    public static void viewMult(ViewportAPI v, float zoom) {
        if (((ShipAPI) Shared.acting) != null) { Shared.screenDropped++; return; }
        v.setViewMult(zoom);
    }

    public static void viewExternal(ViewportAPI v, boolean external) {
        if (((ShipAPI) Shared.acting) != null) { Shared.screenDropped++; return; }
        v.setExternalControl(external);
    }

    public static void viewCenter(ViewportAPI v, org.lwjgl.util.vector.Vector2f c) {
        if (((ShipAPI) Shared.acting) != null) { Shared.screenDropped++; return; }
        v.setCenter(c);
    }

    /** Played silently for someone else's ship (a real sound, so a script that holds on to it still works). */
    public static com.fs.starfarer.api.SoundAPI uiSound(com.fs.starfarer.api.SoundPlayerAPI p, String id, float pitch, float volume) {
        if (((ShipAPI) Shared.acting) != null) { Shared.screenDropped++; return p.playUISound(id, pitch, 0f); }
        return p.playUISound(id, pitch, volume);
    }

    public static void uiLoop(com.fs.starfarer.api.SoundPlayerAPI p, String id, float pitch, float volume) {
        if (((ShipAPI) Shared.acting) != null) { Shared.screenDropped++; return; }
        p.playUILoop(id, pitch, volume);
    }

    public static int mouseX() {
        ShipAPI a = ((ShipAPI) Shared.acting);
        if (a != null) {
            float[] c = cursor(a);
            if (c != null) { Shared.mouseAnswers++; return Math.round(view().convertWorldXtoScreenX(c[0])); }
        }
        return org.lwjgl.input.Mouse.getX();
    }

    public static int mouseY() {
        ShipAPI a = ((ShipAPI) Shared.acting);
        if (a != null) {
            float[] c = cursor(a);
            if (c != null) return Math.round(view().convertWorldYtoScreenY(c[1]));
        }
        return org.lwjgl.input.Mouse.getY();
    }

    public static boolean isKeyDown(int key) {
        ShipAPI a = ((ShipAPI) Shared.acting);
        Bridge b = Shared.bridge;
        if (a != null && b != null) { Shared.keyAnswers++; return b.keyDown(a, key); } // never the host's keys
        if (key > 0 && key < 256) Shared.polledKeys[key >> 6] |= 1L << (key & 63);
        return org.lwjgl.input.Keyboard.isKeyDown(key);
    }

    /** The keys asked about so far (see Shared.polledKeys), as a copy. */
    public static long[] polledKeys() { return Shared.polledKeys.clone(); }

    private static float[] cursor(ShipAPI a) {
        Bridge b = Shared.bridge;
        try { return b == null ? null : b.cursor(a); } catch (Throwable t) { return null; }
    }

    private static ViewportAPI view() { return Global.getCombatEngine().getViewport(); }


    // ---- campaign lookups a joiner's stand-in can't answer (recorded only while Wingman asks: Shared.recording) ----

    /** Wingman turns recording on for a joiner's battle and off when it ends. */
    public static void setRecording(boolean on) { Shared.recording = on; }

    /** The lookups recorded since the last call, most asked first; clears them. Key -> times asked. */
    public static Map<String, Integer> takeMisses() { return Shared.takeMisses(); }
    // (No nested or anonymous classes in Hooks: a copy of Hooks in another class loader couldn't reach them.)

    private static void missIfAbsent(MemoryAPI m, String key) {
        try { if (!m.contains(key)) Shared.miss(key); } catch (Throwable t) { /* not counted */ }
    }

    public static Object memGet(MemoryAPI m, String key) {
        Object v = m.get(key);
        if (v == null && Shared.recording) missIfAbsent(m, key);
        return v;
    }
    public static String memString(MemoryAPI m, String key) { if (Shared.recording) missIfAbsent(m, key); return m.getString(key); }
    public static float memFloat(MemoryAPI m, String key) { if (Shared.recording) missIfAbsent(m, key); return m.getFloat(key); }
    public static boolean memBoolean(MemoryAPI m, String key) { if (Shared.recording) missIfAbsent(m, key); return m.getBoolean(key); }
    public static long memLong(MemoryAPI m, String key) { if (Shared.recording) missIfAbsent(m, key); return m.getLong(key); }
    public static int memInt(MemoryAPI m, String key) { if (Shared.recording) missIfAbsent(m, key); return m.getInt(key); }
    public static boolean memContains(MemoryAPI m, String key) {
        boolean has = m.contains(key);
        if (!has && Shared.recording) Shared.miss(key);
        return has;
    }
    public static boolean memIs(MemoryAPI m, String key, Object value) { if (Shared.recording) missIfAbsent(m, key); return m.is(key, value); }
    public static boolean memIsF(MemoryAPI m, String key, float value) { if (Shared.recording) missIfAbsent(m, key); return m.is(key, value); }
    public static boolean memIsZ(MemoryAPI m, String key, boolean value) { if (Shared.recording) missIfAbsent(m, key); return m.is(key, value); }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> persistentData(com.fs.starfarer.api.campaign.SectorAPI sector) {
        Map<String, Object> m = sector.getPersistentData();
        return Shared.recording && m != null && !(m instanceof RecordingMap) ? new RecordingMap(m) : m;
    }

    /** One line for the log. */
    public static String report() {
        String agent = "Wingman agent";
        try { if (Shared.reportSource != null) agent = String.valueOf(Shared.reportSource.call()); } catch (Throwable t) { /* just the counts */ }
        return agent + "; changed answers so far: player ship " + Shared.playerShipAnswers + ", ship moves " + Shared.moves + ", mouse " + Shared.mouseAnswers
                + ", keys " + Shared.keyAnswers + ", status lines dropped " + Shared.statusDropped + ", time changes dropped " + Shared.timeDropped + ", camera/UI sounds kept off this screen " + Shared.screenDropped
                + ", joiner ship frames " + Shared.contexts + " (and from mods' methods " + Shared.frames + ")" + ", plugins added for joiners " + Shared.pluginsOwned;
    }
}

package wingman.agent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.fs.starfarer.api.Global;
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
 * MagicLib subsystems), that ship is the <i>acting</i> ship, and in code running for it:
 * <ul>
 * <li>{@code engine.getPlayerShip()} answers the acting ship, so "if this is the player's ship" is true for
 * it, as it is on its pilot's own screen;</li>
 * <li>{@code engine.setPlayerShipExternal(s)} goes to the bridge: on the host the joiner is moved into s
 * (Wingman claims it for them); on a joiner nothing happens here (the host decides);</li>
 * <li>the mouse is that person's cursor (where they point, on this screen), and no key is ever this PC's;</li>
 * <li>things only meant for one player's own screen and game speed do nothing here: the status line in the
 * corner, the camera, interface sounds, and the battle's time multiplier (a script slowing time for "the
 * player" would slow the battle for everyone).</li>
 * </ul>
 * Everything else, and everything outside an acting ship's frame (screen-wide plugins, this PC's own player's
 * ship), gets the real answer. With no Wingman bridge set (not in a co-op battle), nothing is changed.
 *
 * All of this is on the game thread; the one static field is fine.
 */
public final class Hooks {
    private Hooks() {}

    /** Set by Wingman's host plugin; null when not hosting. */
    private static volatile Bridge bridge;
    /** The ship whose frame is running, if a joiner flies it (or what it belongs to). Null otherwise. */
    private static ShipAPI acting;
    /** Diagnostics: how often each hook changed an answer. */
    public static volatile long playerShipAnswers, moves, mouseAnswers, keyAnswers, statusDropped, timeDropped, contexts, pluginsOwned, screenDropped;

    public static void setBridge(Bridge b) { bridge = b; acting = null; owners.clear(); }
    public static boolean installed() { return true; }
    public static ShipAPI acting() { return acting; }

    /** The mark Wingman's host writes on a ship a joiner flies (see wingman.PilotMarks). */
    static final String REMOTE = "wingman_remote";

    /** Whose frame this is: the ship itself if a joiner flies it; for a module or a fighter, the ship it belongs to. */
    static ShipAPI actingFor(ShipAPI s) {
        Bridge b = bridge;
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

    /** Run something with this ship acting (Wingman uses it when it fires a joiner's ability itself). */
    public static void runAs(ShipAPI ship, Runnable r) {
        ShipAPI prev = acting;
        acting = actingFor(ship);
        try { r.run(); } finally { acting = prev; }
    }

    /**
     * Run something for a ship Wingman knows a joiner flies (it fires their ability itself): that ship acts, even
     * if its mark isn't written yet (they got it this frame).
     */
    public static void runAsPilot(ShipAPI ship, Runnable r) {
        Bridge b = bridge;
        ShipAPI prev = acting;
        boolean live = false;
        try { live = b != null && ship != null && b.live(); } catch (Throwable t) { /* as not live */ }
        acting = live ? ship : null;
        try { r.run(); } finally { acting = prev; }
    }

    // ---- the engine's per-ship frame ----

    public static void shipAdvance(com.fs.starfarer.combat.entities.Ship ship, float amount) {
        if (bridge == null) { ship.advance(amount); return; }
        ShipAPI prev = acting;
        ShipAPI now = actingFor(ship);
        if (now != null) contexts++;
        acting = now;
        try { ship.advance(amount); } finally { acting = prev; }
    }

    private static Method advanceInternal;
    private static Field subsystemShip;

    /** MagicLib advances every ship's subsystems from one screen-wide plugin: give each its ship's context. */
    public static void subsystemAdvance(Object subsystem, float amount) {
        try {
            if (advanceInternal == null) {
                Class<?> c = subsystem.getClass();
                while (c != null && !"org.magiclib.subsystems.MagicSubsystem".equals(c.getName())) c = c.getSuperclass();
                if (c == null) throw new IllegalStateException("not a MagicSubsystem: " + subsystem.getClass());
                advanceInternal = c.getMethod("advanceInternal", float.class);
                subsystemShip = c.getDeclaredField("ship");
                subsystemShip.setAccessible(true);
            }
        } catch (RuntimeException e) { throw e; } catch (Exception e) { throw new IllegalStateException(e); }
        ShipAPI prev = acting;
        if (bridge != null) {
            try { acting = actingFor((ShipAPI) subsystemShip.get(subsystem)); } catch (Throwable t) { acting = null; }
        }
        try {
            advanceInternal.invoke(subsystem, amount);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException) throw (RuntimeException) c;
            if (c instanceof Error) throw (Error) c;
            throw new IllegalStateException(c);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        } finally {
            acting = prev;
        }
    }

    /**
     * Screen-wide plugins a script added while running for a joiner's ship, and that keep hold of that ship (a
     * "slow time down while the player flies a fighter" script, say): their frames run for that ship too, as
     * long as a joiner still flies it. A plugin that holds no such ship is left alone: that is how a mod's
     * battle-wide manager looks when it happens to be added from the first ship whose frame runs, and it must
     * stay the host's. Weak keys: a plugin removed from the battle is forgotten with it.
     */
    private static final Map<EveryFrameCombatPlugin, ShipAPI> owners = Collections.synchronizedMap(new WeakHashMap<EveryFrameCombatPlugin, ShipAPI>());

    public static void addPlugin(CombatEngineAPI engine, EveryFrameCombatPlugin plugin) {
        ShipAPI a = acting;
        if (a != null && plugin != null && holds(plugin, a)) { owners.put(plugin, a); pluginsOwned++; }
        engine.addPlugin(plugin);
    }

    private static boolean same(ShipAPI x, ShipAPI y) { return x != null && x == y; }

    /** Does this object keep, in a field of its own, the acting ship (or one of its modules or fighters)? */
    static boolean holds(Object o, ShipAPI a) {
        try {
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                    if (!f.getType().isAssignableFrom(ShipAPI.class) && !ShipAPI.class.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v == a || (v instanceof ShipAPI && same(actingFor((ShipAPI) v), actingFor(a)))) return true;
                }
            }
        } catch (Throwable t) { /* can't look: leave it alone */ }
        return false;
    }

    public static void pluginAdvance(EveryFrameCombatPlugin plugin, float amount, List events) {
        ShipAPI owner = bridge == null || owners.isEmpty() ? null : owners.get(plugin);
        if (owner == null) { plugin.advance(amount, events); return; }
        ShipAPI prev = acting;
        acting = actingFor(owner);
        try { plugin.advance(amount, events); } finally { acting = prev; }
    }

    // ---- what scripts ask ----

    public static ShipAPI getPlayerShip(CombatEngineAPI engine) {
        ShipAPI a = acting;
        if (a != null) { playerShipAnswers++; return a; }
        return engine.getPlayerShip();
    }

    public static void setPlayerShipExternal(CombatEngineAPI engine, ShipAPI ship) {
        ShipAPI a = acting;
        Bridge b = bridge;
        if (a != null && b != null) {
            if (ship == a || ship == null) return;
            moves++;
            boolean moved;
            try { moved = b.moveRemote(a, ship); } catch (Throwable t) { moved = false; }
            if (moved) acting = ship; // the rest of this script runs for the ship the joiner is moving to
            return;
        }
        engine.setPlayerShipExternal(ship);
    }

    public static void maintainStatusForPlayerShip(CombatEngineAPI engine, Object key, String sprite, String title, String data, boolean debuff) {
        if (acting != null) { statusDropped++; return; } // the joiner's own game shows it on their screen
        engine.maintainStatusForPlayerShip(key, sprite, title, data, debuff);
    }

    public static MutableStat getTimeMult(CombatEngineAPI engine) {
        if (acting != null) {
            // The host's speed is everyone's: a copy, reading as the real one, that changes nothing.
            timeDropped++;
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
        ShipAPI a = acting;
        Bridge b = bridge;
        if (a != null && b != null) {
            try { return b.manual(a); } catch (Throwable t) { return true; }
        }
        return engine.isUIAutopilotOn();
    }

    // ---- this PC's own screen: the camera and interface sounds ----

    public static void viewSet(ViewportAPI v, float llx, float lly, float w, float h) {
        if (acting != null) { screenDropped++; return; }
        v.set(llx, lly, w, h);
    }

    public static void viewMult(ViewportAPI v, float zoom) {
        if (acting != null) { screenDropped++; return; }
        v.setViewMult(zoom);
    }

    public static void viewExternal(ViewportAPI v, boolean external) {
        if (acting != null) { screenDropped++; return; }
        v.setExternalControl(external);
    }

    public static void viewCenter(ViewportAPI v, org.lwjgl.util.vector.Vector2f c) {
        if (acting != null) { screenDropped++; return; }
        v.setCenter(c);
    }

    /** Played silently for someone else's ship (a real sound, so a script that holds on to it still works). */
    public static com.fs.starfarer.api.SoundAPI uiSound(com.fs.starfarer.api.SoundPlayerAPI p, String id, float pitch, float volume) {
        if (acting != null) { screenDropped++; return p.playUISound(id, pitch, 0f); }
        return p.playUISound(id, pitch, volume);
    }

    public static void uiLoop(com.fs.starfarer.api.SoundPlayerAPI p, String id, float pitch, float volume) {
        if (acting != null) { screenDropped++; return; }
        p.playUILoop(id, pitch, volume);
    }

    public static int mouseX() {
        ShipAPI a = acting;
        if (a != null) {
            float[] c = cursor(a);
            if (c != null) { mouseAnswers++; return Math.round(view().convertWorldXtoScreenX(c[0])); }
        }
        return org.lwjgl.input.Mouse.getX();
    }

    public static int mouseY() {
        ShipAPI a = acting;
        if (a != null) {
            float[] c = cursor(a);
            if (c != null) return Math.round(view().convertWorldYtoScreenY(c[1]));
        }
        return org.lwjgl.input.Mouse.getY();
    }

    public static boolean isKeyDown(int key) {
        ShipAPI a = acting;
        Bridge b = bridge;
        if (a != null && b != null) { keyAnswers++; return b.keyDown(a, key); } // never the host's keys
        return org.lwjgl.input.Keyboard.isKeyDown(key);
    }

    private static float[] cursor(ShipAPI a) {
        Bridge b = bridge;
        try { return b == null ? null : b.cursor(a); } catch (Throwable t) { return null; }
    }

    private static ViewportAPI view() { return Global.getCombatEngine().getViewport(); }

    /** One line for the log. */
    public static String report() {
        return Agent.report() + "; changed answers so far: player ship " + playerShipAnswers + ", ship moves " + moves + ", mouse " + mouseAnswers
                + ", keys " + keyAnswers + ", status lines dropped " + statusDropped + ", time changes dropped " + timeDropped + ", camera/UI sounds kept off this screen " + screenDropped
                + ", joiner ship frames " + contexts + ", plugins added for joiners " + pluginsOwned;
    }
}

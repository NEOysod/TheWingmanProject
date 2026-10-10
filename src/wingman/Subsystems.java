package wingman;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.util.IntervalUtil;

/**
 * MagicLib subsystems on ships flown by joiners.
 *
 * MagicLib's subsystems (extra abilities on their own keys, used by UAF and many other mods) read the keyboard
 * only for the ship the local player flies; on any other ship they fire whenever the mod's AI logic decides. A
 * joiner's ship on the host is such an "other" ship, so its subsystems ignored the joiner and fired by
 * themselves. Now:
 *
 * - the joiner's PC watches the subsystems of the ship its player flies, and when one is switched on (or a
 *   toggle off) by the player's key, tells the host (SUBSYSTEM: ship, subsystem class);
 * - the host holds the subsystems of every ship a joiner flies so the AI logic can't fire them, and fires one
 *   when that joiner asks.
 *
 * Holding is done on MagicLib's own fields: a subsystem that is ready is parked in its cooldown state with a
 * cooldown that never runs out, and set back to ready (then activated) on request, or when the joiner leaves
 * the ship. No callback of the mod runs for that. MagicLib is reached only through java.lang.invoke, by name,
 * so Wingman needs no MagicLib to build or to run; without it this does nothing.
 */
final class Subsystems {
    private Subsystems() {}

    static final String KEY = "magicSubsystems"; // MagicSubsystemsManager.CUSTOM_DATA_KEY
    private static final String BASE = "org.magiclib.subsystems.MagicSubsystem";
    private static boolean broken;
    private static Class<?> base, stateClass;
    private static Object READY, IN, ACTIVE, OUT, COOLDOWN;
    private static MethodHandle getState, setStateField, getInterval, canActivateInternal, canActivate, activate, isToggle;

    /** This ship's subsystems (MagicLib keeps them in the ship's custom data); empty if none or no MagicLib. */
    static List<Object> of(ShipAPI ship) {
        if (broken || ship == null) return Collections.emptyList();
        try {
            return ofData(ship.getCustomData().get(KEY));
        } catch (Throwable t) {
            fail(t);
            return Collections.emptyList();
        }
    }

    /** The subsystems in MagicLib's per-ship map (the ship's custom data entry). */
    static List<Object> ofData(Object m) {
        if (broken) return Collections.emptyList();
        try {
            if (!(m instanceof Map) || ((Map<?, ?>) m).isEmpty()) return Collections.emptyList();
            List<Object> out = new ArrayList<Object>(((Map<?, ?>) m).values());
            if (!out.isEmpty() && !ready(out.get(0))) return Collections.emptyList();
            return out;
        } catch (Throwable t) {
            fail(t);
            return Collections.emptyList();
        }
    }

    /** Find MagicLib's classes and members, from a subsystem object. */
    private static boolean ready(Object sub) throws Throwable {
        if (base != null) return true;
        Class<?> c = sub.getClass();
        while (c != null && !BASE.equals(c.getName())) c = c.getSuperclass();
        if (c == null) return false;
        stateClass = Class.forName(BASE + "$State", true, c.getClassLoader());
        READY = constant("READY"); IN = constant("IN"); ACTIVE = constant("ACTIVE"); OUT = constant("OUT"); COOLDOWN = constant("COOLDOWN");
        MethodHandle pli = MethodHandles.lookup().findStatic(MethodHandles.class, "privateLookupIn",
                MethodType.methodType(MethodHandles.Lookup.class, Class.class, MethodHandles.Lookup.class));
        MethodHandles.Lookup l = (MethodHandles.Lookup) pli.invoke(c, MethodHandles.lookup());
        getState = l.findVirtual(c, "getState", MethodType.methodType(stateClass));
        setStateField = l.findSetter(c, "state", stateClass);
        getInterval = l.findGetter(c, "stateInterval", IntervalUtil.class);
        canActivateInternal = l.findVirtual(c, "canActivateInternal", MethodType.methodType(boolean.class));
        canActivate = l.findVirtual(c, "canActivate", MethodType.methodType(boolean.class));
        activate = l.findVirtual(c, "activate", MethodType.methodType(void.class));
        isToggle = l.findVirtual(c, "isToggle", MethodType.methodType(boolean.class));
        base = c;
        Global.getLogger(Subsystems.class).info("Wingman: MagicLib subsystems on joiners' ships follow the joiner's keys");
        return true;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Object constant(String name) {
        return Enum.valueOf((Class) stateClass, name);
    }

    private static void fail(Throwable t) {
        if (broken) return;
        broken = true;
        Global.getLogger(Subsystems.class).warn("Wingman: MagicLib subsystems can't be followed on this PC; on joiners' ships "
                + "they fire by their own logic as before", t);
    }

    static String name(Object sub) { return sub.getClass().getName(); }

    // ---- Joiner: what the player switched on ----

    private static final Map<Object, Object> lastState = new IdentityHashMap<Object, Object>();

    /**
     * Subsystems of the ship this player flies that the player has just switched on (or a toggle off), by class
     * name. Call once a frame with the ship.
     */
    static List<String> pressed(ShipAPI own) {
        return pressed(of(own));
    }

    static List<String> pressed(List<Object> subs) {
        if (subs.isEmpty()) return Collections.emptyList();
        List<String> out = null;
        try {
            for (Object s : subs) {
                Object now = getState.invoke(s);
                Object was = lastState.put(s, now);
                if (was == null || was == now) continue;
                boolean on = was == READY && (now == IN || now == ACTIVE);
                boolean off = was == ACTIVE && now == OUT && (Boolean) isToggle.invoke(s);
                if (!on && !off) continue;
                if (out == null) out = new ArrayList<String>();
                out.add(name(s));
            }
            if (lastState.size() > 200) lastState.keySet().retainAll(subs);
        } catch (Throwable t) {
            fail(t);
        }
        return out == null ? Collections.<String>emptyList() : out;
    }

    // ---- Host: hold a joiner's ship's subsystems, fire them on request ----

    /** Subsystems parked by hold(), so they can be let go again. */
    private static final Map<Object, Boolean> held = new IdentityHashMap<Object, Boolean>();

    /** Keep the AI logic from firing this ship's ready subsystems (a joiner flies it). Call once a frame. */
    static void hold(ShipAPI ship) {
        hold(of(ship));
    }

    static void hold(List<Object> subs) {
        for (Object s : subs) {
            try {
                if (getState.invoke(s) != READY) continue;
                setStateField.invoke(s, COOLDOWN);
                IntervalUtil iv = (IntervalUtil) getInterval.invoke(s);
                if (iv != null) iv.setInterval(1e9f, 1e9f);
                held.put(s, Boolean.TRUE);
            } catch (Throwable t) {
                fail(t);
                return;
            }
        }
    }

    /** Let this ship's parked subsystems go (nobody flies it now): ready, as they were. */
    static void release(ShipAPI ship) {
        release(of(ship));
    }

    static void release(List<Object> subs) {
        for (Object s : subs) unpark(s);
    }

    private static void unpark(Object s) {
        if (held.remove(s) == null) return;
        try {
            if (getState.invoke(s) == COOLDOWN) setStateField.invoke(s, READY);
        } catch (Throwable t) {
            fail(t);
        }
    }

    /**
     * The joiner flying this ship switched this subsystem on (or a toggle off): do the same here, if the
     * subsystem lets itself be used now. Returns true if it fired.
     */
    static boolean fire(ShipAPI ship, String className) {
        return fire(of(ship), className);
    }

    static boolean fire(List<Object> subs, String className) {
        for (Object s : subs) {
            if (!name(s).equals(className)) continue;
            try {
                unpark(s);
                if ((Boolean) canActivateInternal.invoke(s) && (Boolean) canActivate.invoke(s)) {
                    activate.invoke(s);
                    return true;
                }
            } catch (Throwable t) {
                fail(t);
            }
            return false;
        }
        return false;
    }

    /** Which subsystems every ship has now (by MagicLib's keys), to compare with afterwards. */
    static Map<ShipAPI, java.util.Set<Object>> snapshotAll(com.fs.starfarer.api.combat.CombatEngineAPI engine) {
        Map<ShipAPI, java.util.Set<Object>> out = new IdentityHashMap<ShipAPI, java.util.Set<Object>>();
        if (broken) return out;
        try {
            for (ShipAPI s : engine.getShips()) {
                Object m = s.getCustomData().get(KEY);
                if (m instanceof Map) out.put(s, new java.util.HashSet<Object>(((Map<?, ?>) m).keySet()));
            }
        } catch (Throwable t) { /* nothing to compare: nothing is taken off */ }
        return out;
    }

    /** Take off this ship the subsystems it didn't have in the snapshot (a script just gave them to it). */
    static void dropAddedSince(ShipAPI ship, Map<ShipAPI, java.util.Set<Object>> before) {
        if (broken || ship == null) return;
        try {
            Object m = ship.getCustomData().get(KEY);
            if (!(m instanceof Map)) return;
            java.util.Set<Object> had = before.get(ship);
            for (Object key : new ArrayList<Object>(((Map<?, ?>) m).keySet())) {
                if (had == null || !had.contains(key)) ((Map<?, ?>) m).remove(key);
            }
        } catch (Throwable t) { /* left as the script made it */ }
    }

    /** A new battle: forget objects of the last one. */
    static void reset() {
        lastState.clear();
        held.clear();
    }
}

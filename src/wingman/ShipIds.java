package wingman;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAPI;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Gives a joiner's copy of a ship the host's ship id (joiner side).
 *
 * The game makes each ship's id with Misc.genUID(), so the same ship has a different id on every PC. Mods very
 * often keep a ship's data under a key made from it ("mymod_charge_" + ship.getId(), in the battle's custom data
 * or as a stat modifier's name), so what the host's scripts wrote under its ids was never found by the joiner's
 * scripts, and the script values Wingman copies (NOTES) landed under keys no script here asks for. The id is the
 * private field "id" of the game's ship class (no setter in the API), set through java.lang.invoke as BayLink does.
 * Fail-soft: if the field can't be set, ids stay as the game made them, with one log line.
 */
final class ShipIds {
    private ShipIds() {}

    static final String SHIP_CLASS = "com.fs.starfarer.combat.entities.Ship";
    static final String FIELD = "id";

    private static MethodHandle setter;
    private static boolean broken;
    static int given, refused;

    /** Give the ship this id (the host's). Nothing happens for an empty id, or once setting ids has failed. */
    static void give(ShipAPI ship, String id) {
        if (broken || ship == null || id == null || id.isEmpty()) return;
        try {
            if (id.equals(ship.getId())) return;
            if (setter == null) setter = find(ship);
            setter.invoke(ship, id);
            if (!id.equals(ship.getId())) { // a getter that reads something else: leave ids alone from now on
                broken = true;
                Global.getLogger(ShipIds.class).warn("Wingman: ships' ids can't be made the host's on this PC (the id didn't take); "
                        + "mods' per-ship data may not line up between PCs");
                return;
            }
            if (given++ == 0) Dbg.info(ShipIds.class, "Wingman: ships here carry the host's ship ids");
        } catch (Throwable t) {
            broken = true;
            refused++;
            Global.getLogger(ShipIds.class).warn("Wingman: ships' ids can't be made the host's on this PC; "
                    + "mods' per-ship data may not line up between PCs", t);
        }
    }

    private static MethodHandle find(ShipAPI ship) throws Throwable {
        Class<?> c = Class.forName(SHIP_CLASS, false, ship.getClass().getClassLoader());
        if (!c.isInstance(ship)) c = ship.getClass();
        MethodHandle pli = MethodHandles.lookup().findStatic(MethodHandles.class, "privateLookupIn",
                MethodType.methodType(MethodHandles.Lookup.class, Class.class, MethodHandles.Lookup.class));
        MethodHandles.Lookup l = (MethodHandles.Lookup) pli.invoke(c, MethodHandles.lookup());
        return l.findSetter(c, FIELD, String.class);
    }
}

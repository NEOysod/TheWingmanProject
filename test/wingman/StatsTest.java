package wingman;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.MutableStat;

/**
 * Ship stats kept equal to the host's (ShipStats), with real MutableStats: the engine stats are compared with the
 * engines' own state (the engine-damage penalty, the zero-flux boost) left out, which each side works out itself.
 */
public class StatsTest {
    static int fails, checks;
    static void check(boolean ok, String what) { checks++; System.out.println((ok ? "ok   " : "FAIL ") + what); if (!ok) fails++; }

    static MutableShipStatsAPI ship(final MutableStat top) {
        return (MutableShipStatsAPI) Proxy.newProxyInstance(StatsTest.class.getClassLoader(), new Class<?>[] { MutableShipStatsAPI.class }, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) { return m.getName().equals("getMaxSpeed") ? top : null; }
        });
    }

    public static void main(String[] a) {
        // the host's ship: base 100, a hullmod +10%, flamed out (penalty 0), at zero flux (+50)
        MutableStat host = new MutableStat(100f);
        host.modifyMult("hullmod", 1.1f);
        host.modifyFlat(ShipStats.ZERO_FLUX, 50f);
        host.modifyMult(ShipStats.ENGINE_PENALTY, 0f);
        float sent = ShipStats.read(ship(host), 0);
        check(host.getModifiedValue() == 0f && Math.abs(sent - 110f) < 0.01f, "the host sends its top speed without its flameout and zero-flux boost: " + sent);

        // our copy: no hullmod here, its own engines still running for a moment (the damage update not in yet)
        MutableStat ours = new MutableStat(100f);
        MutableShipStatsAPI copy = ship(ours);
        ShipStats.force(copy, 0, sent);
        check(Math.abs(ours.getModifiedValue() - 110f) < 0.01f, "our engines still running: top speed 110, not 0 (" + ours.getModifiedValue() + ")");
        ours.modifyMult(ShipStats.ENGINE_PENALTY, 0f); // the flameout reaches us
        check(ours.getModifiedValue() == 0f, "our own flameout then makes it 0, as the game does");
        ours.unmodify(ShipStats.ENGINE_PENALTY); // the engines come back
        ShipStats.force(copy, 0, sent);
        check(Math.abs(ours.getModifiedValue() - 110f) < 0.01f, "engines back: 110 again, with nothing stale left from the flameout");

        // half the engines out on both sides, and the zero-flux boost here too
        MutableStat h2 = new MutableStat(100f);
        h2.modifyMult("hullmod", 1.1f);
        h2.modifyFlat(ShipStats.ZERO_FLUX, 50f);
        h2.modifyMult(ShipStats.ENGINE_PENALTY, 0.5f);
        MutableStat o2 = new MutableStat(100f);
        o2.modifyFlat(ShipStats.ZERO_FLUX, 50f);
        o2.modifyMult(ShipStats.ENGINE_PENALTY, 0.5f);
        ShipStats.force(ship(o2), 0, ShipStats.read(ship(h2), 0));
        check(Math.abs(o2.getModifiedValue() - h2.getModifiedValue()) < 0.01f, "half the engines out on both: the same top speed (" + o2.getModifiedValue() + " / " + h2.getModifiedValue() + ")");

        // a stat at nothing here (a mod's script zeroed it): set by a flat correction
        MutableStat z = new MutableStat(100f);
        z.modifyMult("script", 0.5f);
        z.modifyFlat("script", -100f);
        ShipStats.force(ship(z), 0, 80f);
        check(Math.abs(z.getModifiedValue() - 80f) < 0.01f, "from nothing to the host's 80 by a flat correction (" + z.getModifiedValue() + ")");

        System.out.println(fails == 0 ? "STATS PASSED (" + checks + " checks)" : "STATS FAILED (" + fails + " of " + checks + ")");
        if (fails > 0) System.exit(1);
    }
}

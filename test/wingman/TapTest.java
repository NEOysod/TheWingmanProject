package wingman;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;

/** CommandTap with a stand-in ship: the game's queued commands become fire and vent, once each. */
public class TapTest {
    static int fails;
    static void check(boolean ok, String what) { System.out.println((ok ? "ok   " : "FAIL ") + what); if (!ok) fails++; }
    public static void main(String[] a) {
        final List<ShipCommand> queue = new ArrayList<ShipCommand>();
        final List<Object> listeners = new ArrayList<Object>();
        final boolean[] old = { false };
        ShipAPI ship = (ShipAPI) Proxy.newProxyInstance(TapTest.class.getClassLoader(), new Class[] { ShipAPI.class }, (p, m, args) -> {
            switch (m.getName()) {
                case "getCommands2": if (old[0]) throw new AbstractMethodError("getCommands2"); return new ArrayList<ShipCommand>(queue);
                case "addListener": listeners.add(args[0]); return null;
                case "removeListener": listeners.remove(args[0]); return null;
                case "hashCode": return System.identityHashCode(p);
                case "equals": return p == args[0];
                default: return null;
            }
        });
        CommandTap t = CommandTap.on(ship);
        check(t != null && listeners.size() == 1, "put on the ship as an advance listener");
        check(!t.live(), "not live before the ship has advanced");
        queue.addAll(Arrays.asList(ShipCommand.ACCELERATE, ShipCommand.FIRE));
        ((AdvanceableListener) listeners.get(0)).advance(0.016f);
        check(t.live(), "live once the ship advances");
        check(t.takeFire() && !t.takeFire(), "a fire command is seen once");
        check(!t.takeVent(), "no vent without the command");
        queue.clear(); queue.add(ShipCommand.VENT_FLUX);
        ((AdvanceableListener) listeners.get(0)).advance(0.016f);
        check(t.takeVent() && !t.takeFire(), "a vent command is seen");
        check(CommandTap.on(ship) == t, "the same tap while on the same ship");
        CommandTap.off();
        check(listeners.isEmpty(), "taken off the ship");
        old[0] = true;
        CommandTap t2 = CommandTap.on(ship);
        ((AdvanceableListener) listeners.get(0)).advance(0.016f);
        check(!t2.live() && CommandTap.on(ship) == null, "an older game: not live, controls worked out as before");
        System.out.println(fails == 0 ? "TAP PASSED" : fails + " FAILED");
    }
}

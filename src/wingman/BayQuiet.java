package wingman;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.FighterLaunchBayAPI;
import com.fs.starfarer.api.combat.ShipAPI;

/**
 * Keeps the launch bays of a joiner's copies of carriers from showing a fighter being built.
 *
 * A copy of a carrier never builds fighters itself: the fighters on screen are the host's (and its fighter
 * rebuild time is stretched so that it can't). But a bay that loses a fighter asks for a replacement, and while
 * one is under way the game draws its "being built" sparks on the bay; at that rebuild time, for the rest of the
 * battle. So a bay's pending replacement is cleared here, the way the game itself clears it when a fighter is
 * finished, without anything being launched: the request, its timer, the sparks, and any queued requests.
 *
 * The bay class keeps these in private fields (com.fs.starfarer.combat.entities.ship.A.C), set through
 * java.lang.invoke, which the script sandbox allows. Their names are obfuscated with accented letters, written as
 * escapes. If they can't be reached, the sparks show as before, with one log line.
 */
final class BayQuiet {
    private BayQuiet() {}

    private static final String PENDING = "O\u00f5\u00f4o00";        // the replacement under way (CombatFleetManager-side record)
    private static final String TIMER = "\u00d6\u00f4\u00f4o00";      // its build timer
    private static final String SPARKS = "String.String$interface"; // the "being built" effect
    private static final String QUEUED = "super.String$interface";  // further replacements asked for
    private static boolean broken;
    private static MethodHandle getPending, setPending, setTimer, setSparks, setQueued;
    static int cleared;

    /** Clear any fighter being built in this ship's bays. */
    static void quiet(ShipAPI carrier) {
        if (broken || carrier == null || !carrier.hasLaunchBays()) return;
        try {
            List<FighterLaunchBayAPI> bays = carrier.getLaunchBaysCopy();
            if (bays == null) return;
            for (FighterLaunchBayAPI bay : bays) {
                if (getPending == null && !ready(bay.getClass())) return;
                if (getPending.invoke(bay) == null) continue;
                setPending.invoke(bay, (Object) null);
                setTimer.invoke(bay, (Object) null);
                setSparks.invoke(bay, (Object) null);
                setQueued.invoke(bay, 0);
                cleared++;
            }
        } catch (Throwable t) {
            broken = true;
            Global.getLogger(BayQuiet.class).warn("Wingman: carriers' bays here may show fighters being built that the host isn't building", t);
        }
    }

    private static boolean ready(Class<?> bayClass) throws Throwable {
        ClassLoader cl = bayClass.getClassLoader();
        Class<?> pending = Class.forName("com.fs.starfarer.combat.ai.M$Oo", false, cl);
        Class<?> timer = Class.forName("com.fs.starfarer.util.IntervalTracker", false, cl);
        Class<?> sparks = Class.forName("com.fs.starfarer.renderers.fx.oOOO", false, cl);
        MethodHandle pli = MethodHandles.lookup().findStatic(MethodHandles.class, "privateLookupIn",
                MethodType.methodType(MethodHandles.Lookup.class, Class.class, MethodHandles.Lookup.class));
        MethodHandles.Lookup l = (MethodHandles.Lookup) pli.invoke(bayClass, MethodHandles.lookup());
        getPending = l.findGetter(bayClass, PENDING, pending);
        setPending = l.findSetter(bayClass, PENDING, pending);
        setTimer = l.findSetter(bayClass, TIMER, timer);
        setSparks = l.findSetter(bayClass, SPARKS, sparks);
        setQueued = l.findSetter(bayClass, QUEUED, int.class);
        return true;
    }
}

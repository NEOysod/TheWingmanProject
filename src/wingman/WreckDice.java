package wingman;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.Random;

import com.fs.starfarer.api.Global;

/**
 * The dice a wreck is cut with, shared between the host and joiners, so a wreck breaks along the same lines on
 * every screen.
 *
 * The game cuts a wreck in two in com.fs.starfarer.util.Tesselator (called from Ship.splitShip), from the
 * wreck's outline and one random number generator: a private static java.util.Random, made once when the game
 * loads with an unpredictable seed and drawn from by every break-up of the session. (An older mode re-seeds it
 * from the clock on every cut; the game leaves that off.) Each PC drew different numbers, so the cuts differed.
 *
 * Here that generator is replaced, through java.lang.invoke (allowed by the script sandbox), with a Dice: the
 * same algorithm as java.util.Random, but its state can be read and set. On the host, after each frame in
 * which the game cut a wreck, the dice are given a fresh seed, and the break-ups found that frame are sent
 * with the seed they were cut with and their order. A joiner sets its dice to that seed and makes the same cuts
 * in the same order, so it draws the same numbers. The field's name is obfuscated, written as an escape.
 */
final class WreckDice {
    private WreckDice() {}

    private static final String CLASS = "com.fs.starfarer.util.Tesselator";
    private static final String FIELD = "\u00d500000";
    private static final long MULT = 0x5DEECE66DL, ADD = 0xBL, MASK = (1L << 48) - 1;

    /** java.util.Random's own algorithm, with its state in the open. */
    static final class Dice extends Random {
        private static final long serialVersionUID = 1L;
        private long state;
        /** Numbers drawn since the last seed. */
        int draws;
        /** The seed last given (0 never is). */
        long seed;

        Dice(long seed) { super(seed); }

        @Override public synchronized void setSeed(long s) {
            state = (s ^ MULT) & MASK;
            seed = s;
            draws = 0;
        }

        @Override protected int next(int bits) {
            draws++;
            state = (state * MULT + ADD) & MASK;
            return (int) (state >>> (48 - bits));
        }

        long state() { return state; }
        void restore(long s) { state = s; }

        /** The state a seed gives, without drawing. */
        static long stateOf(long s) { return (s ^ MULT) & MASK; }
    }

    private static boolean broken;
    private static MethodHandle getter, setter;
    private static final Random seeds = new Random();

    /** The game's wreck dice, made ours first if they aren't yet; null if they can't be reached. */
    static Dice dice() {
        if (broken) return null;
        try {
            if (getter == null) {
                ClassLoader cl = Global.class.getClassLoader();
                Class<?> c = Class.forName(CLASS, true, cl);
                MethodHandle pli = MethodHandles.lookup().findStatic(MethodHandles.class, "privateLookupIn",
                        java.lang.invoke.MethodType.methodType(MethodHandles.Lookup.class, Class.class, MethodHandles.Lookup.class));
                MethodHandles.Lookup l = (MethodHandles.Lookup) pli.invoke(c, MethodHandles.lookup());
                getter = l.findStaticGetter(c, FIELD, Random.class);
                setter = l.findStaticSetter(c, FIELD, Random.class);
            }
            Object now = getter.invoke();
            if (now instanceof Dice) return (Dice) now;
            Dice d = new Dice(freshSeed());
            setter.invoke((Random) d);
            return d;
        } catch (Throwable t) {
            broken = true;
            Global.getLogger(WreckDice.class).warn("Wingman: wrecks can't be made to break along the same lines here as on the host", t);
            return null;
        }
    }

    /** A new seed, never 0 (0 means "no seed known"). */
    static long freshSeed() {
        long s;
        synchronized (seeds) { do { s = seeds.nextLong(); } while (s == 0L); }
        return s;
    }
}

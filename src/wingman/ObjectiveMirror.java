package wingman;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.Map;

import com.fs.starfarer.api.Global;

/**
 * Makes a held-back objective kind run MirrorObjectiveEffect instead of its own script, on this PC only, for
 * the join mission.
 *
 * The game builds an objective's script from its kind's entry (the "effectClass" from battle_objectives.json)
 * at the moment the objective is made, which is when the joiner's battle starts. So while the join mission is
 * set up, the entry of each kind that can't run here is pointed at our do-nothing script; the objectives are
 * then made with their real name, icon and look but no script of their own. Once the battle has started the
 * entries are put back exactly as they were, so a later battle on this PC (a campaign battle, or hosting) gets
 * the real scripts. They are also put back whenever a campaign is loaded, and on the title screen once a
 * change is older than the join mission could need it.
 *
 * The entry is the game's own (an unnamed class in the game's code), reached through its spec store by method
 * handles, as each game build names them (newest first; the first set that is all there is used). If any of that
 * fails, the kind gets the older Sensor Array stand-in instead (see JoinSetup.kindToMake).
 */
final class ObjectiveMirror {
    private ObjectiveMirror() {}

    static final String EFFECT = "wingman.MirrorObjectiveEffect";

    /** Kind -> its own script class, for each kind changed now. */
    private static final Map<String, String> changed = new HashMap<String, String>();
    private static boolean broken;
    private static long changedAt;

    /** The objective spec class, the spec store's lookup (Class, String) and the spec's "effect class" setter: 0.98.5a-RC7, RC6, RC5. */
    private static final String[][] NAMES = {
        { "com.fs.starfarer.loading.specs.supersuper", "super", "Object" },
        { "com.fs.starfarer.loading.specs.oOOo", "o00000", "\u00d300000" },
        { "com.fs.starfarer.loading.specs.supersuper", "o00000", "\u00d300000" },
    };
    private static MethodHandle lookupSpec, setEffect;
    private static Class<?> specClass;

    private static void handles() throws Throwable {
        if (lookupSpec != null) return;
        ClassLoader cl = com.fs.starfarer.title.TitleScreenState.class.getClassLoader();
        Class<?> store = Class.forName("com.fs.starfarer.loading.SpecStore", false, cl);
        MethodHandles.Lookup l = MethodHandles.publicLookup();
        Throwable missing = null;
        for (String[] n : NAMES) {
            try {
                Class<?> c = Class.forName(n[0], false, cl);
                MethodHandle look = l.findStatic(store, n[1], MethodType.methodType(Object.class, Class.class, String.class));
                MethodHandle set = l.findVirtual(c, n[2], MethodType.methodType(void.class, String.class));
                l.findVirtual(c, "\u00d400000", MethodType.methodType(com.fs.starfarer.api.combat.BattleObjectiveEffect.class)); // its script: the right class
                specClass = c; setEffect = set; lookupSpec = look;
                return;
            } catch (ClassNotFoundException e) { missing = e; }
            catch (NoSuchMethodException e) { missing = e; }
        }
        throw missing;
    }

    /** Make objectives of this kind run nothing here until restore(). False if that can't be done. */
    static synchronized boolean blank(String type, String ownEffect) {
        if (broken || ownEffect == null || ownEffect.isEmpty()) return false;
        if (changed.containsKey(type)) { changedAt = System.currentTimeMillis(); return true; }
        try {
            Object spec = spec(type);
            if (spec == null) return false;
            setEffect.invoke(spec, EFFECT);
            changed.put(type, ownEffect);
            changedAt = System.currentTimeMillis();
            return true;
        } catch (Throwable t) {
            broken = true;
            Global.getLogger(ObjectiveMirror.class).warn("Wingman: held-back objectives can't be shown as themselves on this PC; "
                    + "they get a Sensor Array stand-in", t);
            return false;
        }
    }

    /** Put every changed kind back as it was. */
    static synchronized void restore() {
        if (changed.isEmpty()) return;
        for (Map.Entry<String, String> e : changed.entrySet()) {
            try {
                Object spec = spec(e.getKey());
                if (spec != null) setEffect.invoke(spec, e.getValue());
            } catch (Throwable t) {
                Global.getLogger(ObjectiveMirror.class).error("Wingman: could not put objective kind '" + e.getKey()
                        + "' back to its own script; restart the game before playing a battle with it", t);
            }
        }
        changed.clear();
    }

    /** On the title screen: put back changes the join mission no longer needs (it starts within seconds). */
    static void restoreIfLeft() {
        if (!changed.isEmpty() && System.currentTimeMillis() - changedAt > 15000) restore();
    }

    private static Object spec(String type) throws Throwable {
        handles();
        return lookupSpec.invoke(specClass, type);
    }
}

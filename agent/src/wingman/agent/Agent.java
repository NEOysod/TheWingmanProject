package wingman.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Wingman's optional Java agent: started by a line in the game's launch settings
 * ({@code -javaagent:../mods/Wingman/jars/WingmanAgent.jar}), on the host's PC only.
 *
 * As each class loads it swaps a handful of calls for calls to {@link Hooks} (see {@link Patcher}): in mods'
 * classes (and the game's own scripts in com/fs/starfarer/api/impl), the calls that ask for or change "the
 * player's ship", read the mouse or keyboard, show the player's status line or change the battle's speed;
 * in the game itself, the calls that run each ship's frame and each screen-wide plugin's. Nothing is written to any file; a game
 * started without the line is untouched.
 *
 * Options after '=' (comma-separated): {@code novanilla} leaves the game's own scripts alone; {@code off}
 * patches nothing.
 */
public final class Agent {
    private Agent() {}

    static final List<Patcher.Rule> MOD = Patcher.modRules(), ENGINE = Patcher.engineRules();
    static volatile int classesSeen, classesPatched, failures;
    static volatile String lastFailure = "";
    static boolean vanilla = true, on = true;

    public static void premain(String args, Instrumentation inst) {
        if (args != null) {
            for (String a : args.split(",")) {
                if ("novanilla".equalsIgnoreCase(a.trim())) vanilla = false;
                if ("off".equalsIgnoreCase(a.trim())) on = false;
            }
        }
        System.setProperty("wingman.agent", on ? "on" : "off"); // so Wingman can tell "not started" from "can't reach it"
        if (!on) return;
        inst.addTransformer(new ClassFileTransformer() {
            public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain pd, byte[] bytes) {
                if (name == null || bytes == null || redefined != null) return null;
                List<Patcher.Rule> rules = rulesFor(name);
                if (rules == null) return null;
                if (!seesHooks(loader)) { hidden++; return null; } // its code couldn't reach the hooks: leave it as it is
                classesSeen++;
                try {
                    Patcher.Result r = Patcher.patch(bytes, rules);
                    if (r == null) return null;
                    classesPatched++;
                    return r.bytes;
                } catch (Throwable t) { // a class we can't read is left exactly as it was
                    failures++;
                    lastFailure = name + ": " + t;
                    return null;
                }
            }
        });
    }

    static volatile int hidden;
    private static final Map<ClassLoader, Boolean> loaders = Collections.synchronizedMap(new WeakHashMap<ClassLoader, Boolean>());
    private static final ThreadLocal<Boolean> asking = new ThreadLocal<Boolean>();

    /**
     * Can classes defined by this loader reach {@link Hooks}? A changed call to a class its loader can't find
     * would fail when it runs, so classes from a loader that can't (one that doesn't hand unknown names to the
     * game's own class path, as some mods' loaders might not) are left alone. Asked once per loader.
     */
    static boolean seesHooks(ClassLoader loader) {
        if (loader == null) return false; // the JDK's own
        Boolean known = loaders.get(loader);
        if (known != null) return known.booleanValue();
        if (asking.get() != null) return false; // a class loaded while asking: leave it alone
        boolean ok;
        asking.set(Boolean.TRUE);
        try {
            ok = loader.loadClass("wingman.agent.Hooks") == Hooks.class;
        } catch (Throwable t) {
            ok = false;
        } finally {
            asking.remove();
        }
        loaders.put(loader, Boolean.valueOf(ok));
        return ok;
    }

    /** Which rules apply to a class, by name; null: leave it alone. */
    public static List<Patcher.Rule> rulesFor(String name) {
        if (name.startsWith("java/") || name.startsWith("javax/") || name.startsWith("jdk/") || name.startsWith("sun/")
                || name.startsWith("com/sun/") || name.startsWith("wingman/") || name.startsWith("org/lwjgl/")
                || name.startsWith("org/apache/") || name.startsWith("org/json/") || name.startsWith("org/codehaus/")
                || name.startsWith("net/java/") || name.startsWith("kotlin/") || name.startsWith("org/jetbrains/")
                || name.startsWith("de/") || name.startsWith("paulscode/") || name.startsWith("com/thoughtworks/")
                || name.startsWith("org/xmlpull/") || name.startsWith("org/objectweb/")) return null;
        if (name.startsWith("com/fs/starfarer/api/")) return screenOnly(name) ? null : vanilla && name.startsWith("com/fs/starfarer/api/impl/") ? MOD : null;
        if (name.startsWith("com/fs/starfarer/")) return ENGINE; // the game itself (its names are scrambled, so by package)
        if (name.startsWith("com/fs/")) return null;
        return screenOnly(name) ? null : MOD;
    }

    /**
     * Interface code (MagicLib's MagicUI and its copies in mods, status bars, anything in a "ui", "gui", "combatgui"
     * or "hud" package) and achievements: they ask "is this the player's ship?" to decide what to draw on this
     * PC's screen or count for this PC's player, and the answer must stay this PC's own player, or other people's
     * bars would be drawn on top of yours.
     */
    static boolean screenOnly(String name) {
        int slash = name.lastIndexOf('/');
        String simple = name.substring(slash + 1);
        int dollar = simple.indexOf('$');
        if (dollar >= 0) simple = simple.substring(0, dollar);
        if (simple.equals("MagicUI") || simple.equals("StatusBarData")) return true;
        // Packages of interface code, and of things that are this PC's player's alone (MagicLib's achievements:
        // a kill a joiner makes is not this player's).
        String pkg = slash < 0 ? "" : "/" + name.substring(0, slash).toLowerCase() + "/";
        return pkg.contains("/ui/") || pkg.contains("/gui/") || pkg.contains("/combatgui/") || pkg.contains("/hud/")
                || pkg.contains("/achievements/");
    }

    /** What has been patched, for the log. */
    public static String report() {
        StringBuilder sb = new StringBuilder("Wingman agent: " + classesPatched + " classes changed of " + classesSeen + " looked at");
        if (!vanilla) sb.append(" (game scripts left alone)");
        sb.append("; calls swapped:");
        Map<String, Integer> byLabel = new java.util.LinkedHashMap<String, Integer>();
        for (Patcher.Rule r : MOD) if (r.hits > 0) byLabel.put(r.label, (byLabel.containsKey(r.label) ? byLabel.get(r.label) : 0) + r.hits);
        for (Map.Entry<String, Integer> e : byLabel.entrySet()) sb.append(' ').append(e.getKey()).append(' ').append(e.getValue());
        for (Patcher.Rule r : ENGINE) sb.append(' ').append(r.label).append(' ').append(r.hits);
        if (hidden > 0) sb.append("; ").append(hidden).append(" classes left alone (their loader can't reach the agent)");
        if (failures > 0) sb.append("; ").append(failures).append(" classes could not be read (left alone), last: ").append(lastFailure);
        return sb.toString();
    }
}

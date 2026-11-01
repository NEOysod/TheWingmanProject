package wingman.patch;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whose code a call comes from. The hooks sit in the called methods (the engine's getPlayerShip, the mouse, the
 * campaign's memory...), so they hear the game's own calls too; they change answers only for calls from mods'
 * code, as the agent did by rewriting only mods' classes. PatchLib can't rewrite the calls themselves in most
 * mods' classes (jars built for Java 8 and loose scripts are older class files than its call redirects take).
 *
 * A class is a mod's (MOD: mods' jars and scripts, and the game's own script code in com.fs.starfarer.api.impl,
 * which is what mods copy), the engine's (ENGINE: the rest of com.fs.starfarer), or neither (NONE: Java and
 * libraries, Wingman itself, and code that only draws this PC's own screen: a mod's interface that asks for the
 * mouse or "the player's ship" for the person at this PC).
 */
final class Scope {
    private Scope() {}

    static final int NONE = 0, MOD = 1, ENGINE = 2;
    /** How many frames below the hooked method a caller is looked for. */
    private static final int MAX_FRAMES = 40;

    private static final String[] EXCLUDED = { "java/", "javax/", "jdk/", "sun/", "com/sun/", "wingman/", "org/lwjgl/", "org/apache/",
            "org/json/", "org/codehaus/", "net/java/", "kotlin/", "org/jetbrains/", "de/", "paulscode/", "com/thoughtworks/", "org/xmlpull/",
            "org/objectweb/" };
    private static final ConcurrentHashMap<String, Integer> scopes = new ConcurrentHashMap<String, Integer>();

    /** The scope of a class, by its binary name (a.b.C$D). */
    static int of(String className) {
        Integer s = scopes.get(className);
        if (s == null) {
            s = rulesFor(className.replace('.', '/'));
            scopes.put(className, s);
        }
        return s;
    }

    static int of(Class<?> c) { return c == null ? NONE : of(c.getName()); }

    /** The scope of a class, by its internal name (a/b/C$D). */
    static int rulesFor(String internal) {
        for (String p : EXCLUDED) if (internal.startsWith(p)) return NONE;
        if (internal.startsWith("com/fs/starfarer/api/")) {
            if (screenOnly(internal)) return NONE;
            return internal.startsWith("com/fs/starfarer/api/impl/") ? MOD : NONE;
        }
        if (internal.startsWith("com/fs/starfarer/")) return ENGINE;
        if (internal.startsWith("com/fs/")) return NONE;
        return screenOnly(internal) ? NONE : MOD;
    }

    /** Code that only draws this PC's own screen: a heads-up display, a menu. */
    static boolean screenOnly(String internal) {
        int slash = internal.lastIndexOf('/');
        String simple = internal.substring(slash + 1);
        int inner = simple.indexOf('$');
        if (inner >= 0) simple = simple.substring(0, inner);
        if (simple.equals("MagicUI") || simple.equals("StatusBarData")) return true;
        String pkg = "/" + (slash < 0 ? "" : internal.substring(0, slash).toLowerCase(java.util.Locale.ROOT)) + "/";
        return pkg.contains("/ui/") || pkg.contains("/gui/") || pkg.contains("/combatgui/") || pkg.contains("/hud/") || pkg.contains("/achievements/");
    }

    /** Is the hooked method (running now, below the hook) called from a mod's code? */
    static boolean fromMod(String hookedMethod) {
        String c = caller(hookedMethod);
        return c != null && of(c) == MOD;
    }

    /** The class that called the hooked method, or null if not found. */
    static String caller(String hookedMethod) {
        try {
            if (WALK != null) return (String) WALK.invoke(WALKER, new Walk(hookedMethod));
        } catch (Throwable t) { /* the slower way */ }
        try {
            final StackTraceElement[] st = new Throwable().getStackTrace();
            return pick(new Iterator<String[]>() {
                int i;
                public boolean hasNext() { return i < st.length; }
                public String[] next() { StackTraceElement e = st[i++]; return new String[] { e.getClassName(), e.getMethodName() }; }
                public void remove() { throw new UnsupportedOperationException(); }
            }, hookedMethod);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * From the innermost frame out ({class, method}): past the hook's own frames to the hooked method, past
     * PatchLib's and Java's frames (and the method's own bridge or super calls), to the first other frame.
     */
    static String pick(Iterator<String[]> frames, String hookedMethod) {
        String host = null;
        for (int n = 0; frames.hasNext() && n < MAX_FRAMES; n++) {
            String[] f = frames.next();
            String cls = f[0], m = f[1];
            if (host == null) {
                if (m.equals(hookedMethod) && !cls.startsWith("wingman.patch.") && !cls.startsWith("patchlib.") && !cls.startsWith("java.")) host = cls;
                continue;
            }
            if (cls.startsWith("patchlib.") || cls.startsWith("java.lang.invoke.") || cls.startsWith("jdk.internal.")) continue;
            if (cls.equals(host) && m.equals(hookedMethod)) continue;
            return cls;
        }
        return null;
    }

    // ---- StackWalker (Java 9+), through method handles: this class is built for Java 8 ----

    private static final Object WALKER;
    private static final MethodHandle WALK, CLASS_NAME, METHOD_NAME;

    static {
        Object walker = null;
        MethodHandle walk = null, className = null, methodName = null;
        try {
            MethodHandles.Lookup l = MethodHandles.publicLookup();
            Class<?> sw = Class.forName("java.lang.StackWalker");
            Class<?> frame = Class.forName("java.lang.StackWalker$StackFrame");
            walker = l.findStatic(sw, "getInstance", MethodType.methodType(sw)).invoke();
            className = l.findVirtual(frame, "getClassName", MethodType.methodType(String.class));
            methodName = l.findVirtual(frame, "getMethodName", MethodType.methodType(String.class));
            walk = l.findVirtual(sw, "walk", MethodType.methodType(Object.class, java.util.function.Function.class));
        } catch (Throwable t) {
            walk = null;
        }
        WALKER = walker;
        WALK = walk;
        CLASS_NAME = className;
        METHOD_NAME = methodName;
    }

    /** The walk: only as many frames as it takes are looked at. */
    private static final class Walk implements java.util.function.Function<java.util.stream.Stream<Object>, String> {
        private final String hookedMethod;

        Walk(String hookedMethod) { this.hookedMethod = hookedMethod; }

        public String apply(java.util.stream.Stream<Object> s) {
            final Iterator<Object> it = s.iterator();
            return pick(new Iterator<String[]>() {
                public boolean hasNext() { return it.hasNext(); }
                public String[] next() {
                    Object f = it.next();
                    try {
                        return new String[] { (String) CLASS_NAME.invoke(f), (String) METHOD_NAME.invoke(f) };
                    } catch (Throwable t) {
                        throw new IllegalStateException(t);
                    }
                }
                public void remove() { throw new UnsupportedOperationException(); }
            }, hookedMethod);
        }
    }
}

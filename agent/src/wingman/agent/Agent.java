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
    static final List<Patcher.Frame> FRAMES = Patcher.frameRules();
    static final List<Patcher.Frame> NO_FRAMES = java.util.Collections.<Patcher.Frame>emptyList();
    /**
     * The JVM checks every name in a class an agent changed, even with verification off (Java 22 and later; the
     * game's own classes have names that check refuses). Found out at start by trying (see probeStrict).
     */
    static volatile boolean strict;

    /** The method wrappers that go with a rule set (mods' classes only). */
    public static List<Patcher.Frame> framesFor(List<Patcher.Rule> rules) { return rules == MOD ? FRAMES : NO_FRAMES; }
    static volatile int classesSeen, classesPatched, failures;
    static volatile String lastFailure = "";
    static boolean vanilla = true;
    static volatile boolean on = true;

    /** Started by {@link Launch} (Launcher-Agent-Class), for games whose own launcher can't take -javaagent. */
    public static void agentmain(String args, Instrumentation inst) {
        premain(args, inst);
    }

    /**
     * Never lets the game fail to start: anything going wrong here is written to the console and the game starts
     * without the agent (Wingman then logs "started, but ..." or "not started").
     */
    public static void premain(String args, Instrumentation inst) {
        try {
            start(args, inst);
        } catch (Throwable t) {
            on = false;
            try { System.err.println("Wingman agent: could not start, the game goes on without it: " + t); } catch (Throwable ignored) { /* nothing more */ }
        }
    }

    /**
     * The Hooks class the changed calls reach: this one, or a copy defined in the game's own class loader (see
     * inject). seesHooks compares with it.
     */
    static volatile Class<?> hooks = Hooks.class;
    private static volatile Instrumentation instrumentation;
    static volatile String injectNote = "";

    /**
     * Where a launcher puts in a class loader of its own that loads the game's classes itself (Fast Rendering's
     * com.genir.renderer.loaders.AppClassLoader), this agent's classes come from Java's loader below it, and a
     * Hooks there would see a second copy of the game's API (a LinkageError the first time it touches one). So in
     * that case ("inject mode", decided by the loader of the first game class) every loader whose classes are
     * changed gets its own copy of Hooks, defined from this jar: a class's references resolve to a class its own
     * loader defined before anything else, so the changed calls reach that copy, which sees the game's classes as
     * the changed class does. The copies keep their state in Shared, which (with Bridge) names no game class and
     * stays in this agent's loader. Returns false if the copy can't be placed: that class is then left alone.
     */
    private static boolean injectInto(ClassLoader l) {
        if (injected.containsKey(l)) return injected.get(l).booleanValue();
        boolean ok = false;
        try {
            if (define == null) {
                // java.lang.ClassLoader.defineClass is protected: open java.lang to this agent (Java 9+), as an agent may.
                try {
                    Object base = Class.class.getMethod("getModule").invoke(Object.class);
                    Object me = Class.class.getMethod("getModule").invoke(Agent.class);
                    Class<?> moduleClass = Class.forName("java.lang.Module");
                    java.util.Map<String, java.util.Set<Object>> opens = new java.util.HashMap<String, java.util.Set<Object>>();
                    opens.put("java.lang", java.util.Collections.singleton(me));
                    Instrumentation.class.getMethod("redefineModule", moduleClass, java.util.Set.class, java.util.Map.class,
                            java.util.Map.class, java.util.Set.class, java.util.Map.class)
                            .invoke(instrumentation, base, java.util.Collections.emptySet(), java.util.Collections.emptyMap(), opens,
                                    java.util.Collections.emptySet(), java.util.Collections.emptyMap());
                } catch (NoSuchMethodException old) { /* Java 8: nothing to open */ }
                java.lang.reflect.Method d = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class);
                d.setAccessible(true);
                hooksBytes = read("wingman/agent/Hooks.class");
                define = d;
            }
            // Its copy must share this agent's Shared and Bridge (they name no game class).
            if (l.loadClass("wingman.agent.Shared") != Shared.class || l.loadClass("wingman.agent.Bridge") != Bridge.class) {
                throw new IllegalStateException("it doesn't hand out the agent's own Shared and Bridge");
            }
            Class<?> h;
            try {
                h = (Class<?>) define.invoke(l, "wingman.agent.Hooks", hooksBytes, 0, hooksBytes.length);
            } catch (java.lang.reflect.InvocationTargetException e) {
                if (!(e.getCause() instanceof LinkageError)) throw e;
                h = Class.forName("wingman.agent.Hooks", false, l); // it has one already
            }
            ok = h.getClassLoader() == l;
            if (ok) copies++;
        } catch (Throwable t) {
            injectFailures++;
            injectNote = "; could not place the hooks in " + l.getClass().getName() + " (" + t + "), its classes are left alone";
        }
        injected.put(l, Boolean.valueOf(ok));
        return ok;
    }

    private static final Map<ClassLoader, Boolean> injected = Collections.synchronizedMap(new WeakHashMap<ClassLoader, Boolean>());
    private static volatile java.lang.reflect.Method define;
    private static volatile byte[] hooksBytes;
    static volatile int copies, injectFailures;
    static volatile String gameLoader = "";
    /** 0: not known yet, 1: the game's classes come from this agent's loader (the usual case), 2: inject mode. */
    static volatile int mode;

    private static byte[] read(String path) throws java.io.IOException {
        java.io.InputStream in = Agent.class.getClassLoader().getResourceAsStream(path);
        if (in == null) throw new java.io.IOException(path + " not found in WingmanAgent.jar");
        try {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0;) o.write(buf, 0, n);
            return o.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void start(String args, Instrumentation inst) {
        if (args != null) {
            for (String a : args.split(",")) {
                if ("novanilla".equalsIgnoreCase(a.trim())) vanilla = false;
                if ("off".equalsIgnoreCase(a.trim())) on = false;
            }
        }
        instrumentation = inst;
        System.setProperty("wingman.agent", on ? "on" : "off"); // so Wingman can tell "not started" from "can't reach it"
        Shared.reportSource = new java.util.concurrent.Callable<String>() { public String call() { return report(); } };
        if (!on) return;
        inst.addTransformer(new ClassFileTransformer() {
            public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain pd, byte[] bytes) {
                if (name == null || bytes == null || redefined != null) return null;
                if (name.equals(PROBE)) return probing ? bytes.clone() : null;
                if (!on) return null;
                if (mode == 0 && loader != null && name.startsWith("com/fs/")) {
                    // The first of the game's classes: from this agent's loader (the usual case), or a launcher's own.
                    mode = loader == Agent.class.getClassLoader() ? 1 : 2;
                    gameLoader = loader.getClass().getName();
                }
                List<Patcher.Rule> rules = rulesFor(name);
                if (rules == null) return null;
                if (mode == 2) {
                    // Classes of this agent's own loader see a different copy of the game's API: leave them alone.
                    if (loader == null || loader == Agent.class.getClassLoader() || below(loader) || !injectInto(loader)) { hidden++; return null; }
                } else if (!seesHooks(loader)) { hidden++; return null; } // its code couldn't reach the hooks: leave it as it is
                classesSeen++;
                try {
                    Patcher.Result r = Patcher.patch(bytes, rules, rules == MOD ? FRAMES : NO_FRAMES, strict);
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
        strict = probeStrict();
    }

    /** A loader the agent's own loader asks first (Java's platform loader): it can never see the agent, nor is it the game's. */
    static boolean below(ClassLoader l) {
        for (ClassLoader p = Agent.class.getClassLoader().getParent(); p != null; p = p.getParent()) if (p == l) return true;
        return false;
    }

        static final String PROBE = "wingman/agent/StrictProbe";
    private static volatile boolean probing;

    /**
     * Does this JVM refuse an odd name in a class an agent changed? Defines a tiny class with a field named "a.b"
     * (as the game's obfuscated classes have) and hands it back changed (a copy), as a real change would.
     */
    static boolean probeStrict() {
        byte[] probe = probeClass();
        probing = true;
        try {
            new ClassLoader(Agent.class.getClassLoader()) {
                Class<?> make() { return defineClass(PROBE.replace('/', '.'), probe, 0, probe.length); }
            }.make();
            return false;
        } catch (Throwable t) { // ClassFormatError: strict
            return true;
        } finally {
            probing = false;
        }
    }

    /** A class with no methods and one int field named "a.b". */
    static byte[] probeClass() {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream o = new java.io.DataOutputStream(bo);
        try {
            o.writeInt(0xCAFEBABE); o.writeShort(0); o.writeShort(52);
            o.writeShort(7);
            o.writeByte(1); o.writeUTF(PROBE);               // 1
            o.writeByte(7); o.writeShort(1);                 // 2
            o.writeByte(1); o.writeUTF("java/lang/Object");  // 3
            o.writeByte(7); o.writeShort(3);                 // 4
            o.writeByte(1); o.writeUTF("a.b");               // 5
            o.writeByte(1); o.writeUTF("I");                 // 6
            o.writeShort(0x0021); o.writeShort(2); o.writeShort(4);
            o.writeShort(0);                                 // interfaces
            o.writeShort(1);                                 // fields
            o.writeShort(0x0001); o.writeShort(5); o.writeShort(6); o.writeShort(0);
            o.writeShort(0);                                 // methods
            o.writeShort(0);                                 // attributes
            o.flush();
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        return bo.toByteArray();
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
            ok = loader.loadClass("wingman.agent.Hooks") == hooks;
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
        StringBuilder sb = new StringBuilder("Wingman agent: " + classesPatched + " classes changed of " + classesSeen + " looked at"
                + (mode == 2 ? " (the game's classes come from " + gameLoader + "; hooks placed in " + copies + " class loader" + (copies == 1 ? "" : "s") + ")" : "") + injectNote);
        if (!vanilla) sb.append(" (game scripts left alone)");
        sb.append("; calls swapped:");
        Map<String, Integer> byLabel = new java.util.LinkedHashMap<String, Integer>();
        for (Patcher.Rule r : MOD) if (r.hits > 0) byLabel.put(r.label, (byLabel.containsKey(r.label) ? byLabel.get(r.label) : 0) + r.hits);
        for (Map.Entry<String, Integer> e : byLabel.entrySet()) sb.append(' ').append(e.getKey()).append(' ').append(e.getValue());
        for (Patcher.Rule r : ENGINE) sb.append(' ').append(r.label).append(' ').append(r.hits);
        Map<String, Integer> frames = new java.util.LinkedHashMap<String, Integer>();
        for (Patcher.Frame f : FRAMES) frames.put(f.label, (frames.containsKey(f.label) ? frames.get(f.label) : 0) + f.hits);
        sb.append("; methods wrapped:");
        for (Map.Entry<String, Integer> e : frames.entrySet()) sb.append(' ').append(e.getKey()).append(' ').append(e.getValue());
        if (strict) sb.append("; this Java checks changed classes strictly, so " + Patcher.strictSkipped + " of the game's own classes are left alone (ship frames come from mods' methods)");
        if (hidden > 0) sb.append("; ").append(hidden).append(" classes left alone (their loader can't reach the agent)");
        if (failures > 0) sb.append("; ").append(failures).append(" classes could not be read (left alone), last: ").append(lastFailure);
        return sb.toString();
    }
}

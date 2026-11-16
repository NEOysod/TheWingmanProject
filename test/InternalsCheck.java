import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks, against a copy of the game, every piece of the game's internals that Wingman finds by name at run time
 * (those fail silently in play: a feature just turns itself off with a log line). Run it after a game update:
 *   java -cp build/test InternalsCheck /path/to/starsector-core jars/Wingman.jar
 * Classes are loaded without being started, so no game window or native library is needed.
 * The names here are for Starsector 0.98.5a and mirror the constants in the classes named in each check.
 */
public class InternalsCheck {
    static ClassLoader game;
    static int fails;

    static void ok(String what) { System.out.println("ok   " + what); }
    static void fail(String what, Object why) { System.out.println("FAIL " + what + ": " + why); fails++; }

    static Class<?> cls(String name) throws Exception { return Class.forName(name, false, game); }

    static void hasClass(String what, String name) {
        try { cls(name); ok(what + ": class " + show(name)); } catch (Throwable t) { fail(what + ": class " + show(name), t); }
    }

    static void hasField(String what, String owner, String field, String type, boolean isStatic) {
        try {
            Field f = cls(owner).getDeclaredField(field);
            if (!f.getType().getName().equals(type)) { fail(what, "field " + show(field) + " is a " + f.getType().getName() + ", not " + type); return; }
            if (Modifier.isStatic(f.getModifiers()) != isStatic) { fail(what, "field " + show(field) + " static: " + !isStatic); return; }
            ok(what + ": " + show(owner) + "." + show(field));
        } catch (Throwable t) { fail(what + ": field " + show(field) + " of " + show(owner), t); }
    }

    static void hasMethod(String what, String owner, String method, String returns, String... params) {
        try {
            Class<?>[] p = new Class<?>[params.length];
            for (int i = 0; i < p.length; i++) p[i] = type(params[i]);
            Method m = null;
            for (Class<?> c = cls(owner); c != null && m == null; c = c.getSuperclass()) {
                try { m = c.getDeclaredMethod(method, p); } catch (NoSuchMethodException e) { /* up */ }
            }
            if (m == null) { fail(what, "no method " + show(method) + " in " + show(owner)); return; }
            if (!m.getReturnType().getName().equals(returns)) { fail(what, show(method) + " returns " + m.getReturnType().getName() + ", not " + returns); return; }
            ok(what + ": " + show(owner) + "." + show(method) + "()");
        } catch (Throwable t) { fail(what + ": method " + show(method) + " of " + show(owner), t); }
    }

    static Class<?> type(String n) throws Exception {
        if (n.equals("int")) return int.class;
        if (n.equals("float")) return float.class;
        if (n.equals("boolean")) return boolean.class;
        if (n.equals("Object[]")) return Object[].class;
        return cls(n);
    }

    static String show(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) sb.append(c < 128 ? String.valueOf(c) : String.format("\\u%04x", (int) c));
        return sb.toString();
    }

    public static void main(String[] a) throws Exception {
        File core = new File(a[0]);
        List<URL> urls = new ArrayList<URL>();
        for (File f : core.listFiles()) if (f.getName().endsWith(".jar")) urls.add(f.toURI().toURL());
        if (a.length > 1) urls.add(new File(a[1]).toURI().toURL());
        game = new URLClassLoader(urls.toArray(new URL[0]), InternalsCheck.class.getClassLoader());

        // BayQuiet, BayLink: the launch bay class and its private fields
        String bay = null;
        try {
            // the class of a ship's launch bays: the one FighterLaunchBayAPI in the game jar
            java.util.zip.ZipFile z = new java.util.zip.ZipFile(new File(core, "starfarer_obf.jar"));
            Class<?> api = cls("com.fs.starfarer.api.combat.FighterLaunchBayAPI");
            for (java.util.Enumeration<? extends java.util.zip.ZipEntry> e = z.entries(); e.hasMoreElements();) {
                String n = e.nextElement().getName();
                if (!n.startsWith("com/fs/starfarer/combat/entities/ship/") || !n.endsWith(".class") || n.length() > 120) continue;
                try {
                    Class<?> c = cls(n.substring(0, n.length() - 6).replace('/', '.'));
                    if (!c.isInterface() && api.isAssignableFrom(c)) bay = c.getName();
                } catch (Throwable t) { /* not it */ }
            }
            z.close();
        } catch (Throwable t) { fail("launch bay class", t); }
        if (bay == null) fail("launch bay class", "none found");
        else {
            ok("launch bay class: " + show(bay));
            hasField("BayQuiet PENDING", bay, "super.void$interface", "com.fs.starfarer.combat.ai.M$Oo", false);
            hasField("BayQuiet TIMER", bay, "do.return$interface", "com.fs.starfarer.util.IntervalTracker", false);
            hasField("BayQuiet SPARKS", bay, "øOõo00", "com.fs.starfarer.renderers.fx.A", false);
            hasField("BayQuiet QUEUED", bay, "ôOõo00", "int", false);
            hasField("BayLink FIELD", bay, "õ0õo00", "com.fs.starfarer.combat.CombatFleetManager$O0", false);
        }
        // WreckDice
        hasField("WreckDice", "com.fs.starfarer.util.Tesselator", "Õ00000", "java.util.Random", true);
        // TitleMenu
        hasField("TitleMenu", "com.fs.starfarer.title.TitleScreenState", "titleWidget", "com.fs.starfarer.title.Object", false);
        hasMethod("TitleMenu hide", "com.fs.starfarer.title.Object", "hide", "void");
        hasMethod("TitleMenu show", "com.fs.starfarer.title.Object", "show", "void");
        // TitleLauncher
        hasMethod("TitleLauncher missions", "com.fs.starfarer.loading.SpecStore", "Ò00000", "java.util.List");
        hasMethod("TitleLauncher mission id", "com.fs.starfarer.title.C.Oo0" + new String(new char[253]).replace((char) 0, 'O'), "Õ00000", "java.lang.String");
        hasMethod("TitleLauncher accept", "com.fs.starfarer.title.TitleScreenState", "missionAccepted", "void", "com.fs.starfarer.title.C.K");
        hasMethod("TitleLauncher back", "com.fs.starfarer.title.TitleScreenState", "backToMenu", "void");
        try {
            Constructor<?> k = cls("com.fs.starfarer.title.C.K").getConstructor(cls("com.fs.starfarer.title.C.Oo0" + new String(new char[253]).replace((char) 0, 'O')));
            ok("TitleLauncher mission: " + k);
        } catch (Throwable t) { fail("TitleLauncher mission constructor", t); }
        // MessageTap, MessageSpy
        // (the feed's own names are read from the game by the build, tools/GameNames: checked here as built)
        String feed = null, line = null;
        try {
            Class<?> f = cls("com.fs.starfarer.combat.CombatState").getDeclaredField("messageWidget").getType();
            feed = f.getName();
            line = f.getDeclaredMethod("o00000", Object[].class).getReturnType().getName();
            ok("MessageTap feed: " + show(f.getName()));
        } catch (Throwable t) { fail("MessageTap feed", t); }
        if (feed != null) hasMethod("MessageSpy all lines", feed, "o00000", line, "int", "Object[]");
        if (a.length > 1) {
            try {
                Class<?> spy = cls("wingman.MessageSpy");
                if (!spy.getSuperclass().getName().equals(feed)) fail("MessageSpy", "extends " + spy.getSuperclass().getName());
                else { spy.getDeclaredMethods(); ok("MessageSpy as built: extends the game's feed and links"); }
            } catch (Throwable t) { fail("MessageSpy as built", t); }
        }
        // ObjectiveMirror
        hasMethod("ObjectiveMirror spec lookup", "com.fs.starfarer.loading.SpecStore", "super", "java.lang.Object", "java.lang.Class", "java.lang.String");
        hasMethod("ObjectiveMirror set script", "com.fs.starfarer.loading.specs.supersuper", "Object", "void", "java.lang.String");
        hasMethod("ObjectiveMirror its script", "com.fs.starfarer.loading.specs.supersuper", "Ô00000", "com.fs.starfarer.api.combat.BattleObjectiveEffect");
        // straight out of a battle (WingmanClientPlugin.straightOut), CampaignStandIn
        hasMethod("straightOut", "com.fs.starfarer.combat.CombatEngine", "getStateToReturnTo", "java.lang.String");
        hasMethod("straightOut", "com.fs.starfarer.combat.CombatEngine", "doEndOfCombatProcessing", "void");
        hasMethod("straightOut", "com.fs.starfarer.combat.CombatState", "goToState", "void", "java.lang.String");
        hasField("straightOut", "com.fs.starfarer.title.TitleScreenState", "STATE_ID", "java.lang.String", true);
        // teleporting systems (WingmanClientPlugin.teleporters), Backdrop, GroupFire, FighterUnlock
        hasClass("teleporter system", "com.fs.starfarer.combat.systems.I");
        hasField("Backdrop light", "com.fs.starfarer.combat.entities.terrain.Planet", "lightColorOverride", "java.awt.Color", false);
        hasMethod("GroupFire", "com.fs.starfarer.combat.systems.WeaponGroup", "setCurrentActive", "void", "int");
        hasMethod("FighterUnlock", "com.fs.starfarer.combat.ai.M", "getLaunchCR", "float");
        // the agent (Patcher.engineRules, Launch)
        hasMethod("agent Ship.advance", "com.fs.starfarer.combat.entities.Ship", "advance", "void", "float");
        hasClass("agent main class", "com.fs.starfarer.StarfarerLauncher");

        System.out.println(fails == 0 ? "INTERNALS OK" : fails + " INTERNALS FAILED");
        if (fails != 0) System.exit(1);
    }
}

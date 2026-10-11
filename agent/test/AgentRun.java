import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.combat.CombatEngine;
import com.fs.starfarer.combat.entities.Ship;

import org.magiclib.subsystems.MagicSubsystem;
import org.magiclib.subsystems.MagicSubsystemsCombatPlugin;
import org.magiclib.subsystems.TestSubsystem;

import testmod.ModScript;
import wingman.agent.Bridge;
import wingman.agent.Hooks;

/**
 * Runs with -javaagent:WingmanAgent.jar: the fixture classes are patched as they load, and this checks that
 * the swapped calls answer as they should with and without a bridge, inside and outside a joiner's ship's frame.
 * (This class itself makes none of the swapped calls, so what it sees of the engine is the real thing.)
 */
public class AgentRun {
    static int fails, checks;
    static final List<String> engineCalls = new ArrayList<String>();
    static final MutableStat realTime = new MutableStat(1f);
    static Ship host, joiner, joinerModule, other;
    static boolean live = true, joinerAuto;
    static float lastVolume = -1f;
    static final List<com.fs.starfarer.api.combat.EveryFrameCombatPlugin> added = new ArrayList<com.fs.starfarer.api.combat.EveryFrameCombatPlugin>();
    static class TestPlugins {
        testmod.TimeScript[] t = new testmod.TimeScript[1], ht = new testmod.TimeScript[1];
        testmod.TimeScript.Manager[] m = new testmod.TimeScript.Manager[1], hm = new testmod.TimeScript.Manager[1];
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) { fails++; System.out.println("FAIL " + what); }
    }

    static Ship ship(String name) throws Exception {
        Ship s = Ship.class.getDeclaredConstructor().newInstance();
        s.name = name;
        return s;
    }

    public static void main(String[] args) throws Exception {
        host = ship("host"); joiner = ship("joiner"); joinerModule = ship("module"); other = ship("other");
        joiner.data.put("wingman_remote", Boolean.TRUE);
        joinerModule.parent = joiner;
        final ViewportAPI view = (ViewportAPI) Proxy.newProxyInstance(AgentRun.class.getClassLoader(), new Class<?>[] { ViewportAPI.class }, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                if (m.getName().startsWith("set")) engineCalls.add("view." + m.getName());
                if (m.getName().equals("convertWorldXtoScreenX") || m.getName().equals("convertWorldYtoScreenY")) return ((Float) a[0]) / 2f;
                return null;
            }
        });
        CombatEngineAPI engine = (CombatEngineAPI) Proxy.newProxyInstance(AgentRun.class.getClassLoader(), new Class<?>[] { CombatEngineAPI.class }, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                engineCalls.add(m.getName());
                if (m.getName().equals("getPlayerShip")) return host;
                if (m.getName().equals("getTimeMult")) return realTime;
                if (m.getName().equals("getViewport")) return view;
                if (m.getName().equals("isUIAutopilotOn")) return Boolean.FALSE; // this PC's player: on autopilot
                if (m.getName().equals("addPlugin")) added.add((com.fs.starfarer.api.combat.EveryFrameCombatPlugin) a[0]);
                Class<?> r = m.getReturnType();
                if (r == boolean.class) return false;
                if (r == float.class) return 0f;
                if (r == int.class) return 0;
                return null;
            }
        });
        Global.setCombatEngine(engine);
        Global.setSoundPlayer((com.fs.starfarer.api.SoundPlayerAPI) Proxy.newProxyInstance(AgentRun.class.getClassLoader(),
                new Class<?>[] { com.fs.starfarer.api.SoundPlayerAPI.class }, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                if (m.getName().equals("playUISound")) lastVolume = (Float) a[2];
                return null;
            }
        }));

        // 1. No bridge (not hosting): everything is the real answer, even "inside" a joiner's frame.
        Hooks.runAs(joiner, new Runnable() { public void run() { realAnswers("no bridge"); } });
        realAnswers("no bridge, outside");

        // 2. A bridge set (hosting): outside any joiner's frame, still the real answers.
        final List<String> moves = new ArrayList<String>();
        Hooks.setBridge(new Bridge() {
            public boolean live() { return live; }
            public boolean moveRemote(Object from, Object to) { moves.add(from + "->" + to); return to != host; }
            public float[] cursor(Object s) { return s == joiner ? new float[] { 100f, 60f } : null; }
            public boolean keyDown(Object s, int key) { return s == joiner && key == 30; }
            public boolean manual(Object s) { return s != joiner || !joinerAuto; }
        });
        realAnswers("bridge, outside");
        Hooks.runAs(host, new Runnable() { public void run() { realAnswers("bridge, host's frame"); } });

        // 3. Inside the joiner's frame: the joiner's answers.
        Hooks.runAs(joiner, new Runnable() { public void run() { joinerAnswers("joiner's frame", moves); } });
        // A module of the joiner's ship counts as the joiner's.
        Hooks.runAs(joinerModule, new Runnable() { public void run() {
            check(ModScript.who(Global.getCombatEngine()) == joiner, "module: player ship is the joiner's ship");
        } });
        // After the frame, back to the real answers.
        realAnswers("bridge, after the joiner's frame");

        // 4. The engine's per-ship loop (CombatEngine swapped call).
        Ship.log.clear();
        new CombatEngine().advanceShips(Arrays.asList(host, joiner, joinerModule, other), 0.016f);
        check(Ship.log.equals(Arrays.asList("host sees host", "joiner sees joiner", "module sees joiner", "other sees host")), "engine loop: " + Ship.log);

        // 5. MagicLib's subsystem plugin (swapped advanceInternal).
        TestSubsystem a = new TestSubsystem(host), b = new TestSubsystem(joiner), c = new TestSubsystem(other);
        new MagicSubsystemsCombatPlugin().advance(Arrays.<MagicSubsystem>asList(a, b, c), 0.016f);
        check(a.seen == host && b.seen == joiner && c.seen == host, "subsystems: " + a.seen + " " + b.seen + " " + c.seen);

        // 6. A move inside the frame: the rest of that script now answers for the new ship (not remote: real answers).
        moves.clear();
        Hooks.runAs(joiner, new Runnable() { public void run() {
            ShipAPI after = ModScript.moveThenAsk(Global.getCombatEngine(), other);
            check(moves.equals(Arrays.asList("joiner->other")), "move then ask: moved " + moves);
            check(after == other, "move then ask: the rest of the script answers for the ship moved to, got " + after);
        } });
        // A refused move (the host's ship): the joiner stays where they are.
        Hooks.runAs(joiner, new Runnable() { public void run() {
            ShipAPI after = ModScript.moveThenAsk(Global.getCombatEngine(), host);
            check(after == joiner, "refused move: still the joiner's ship, got " + after);
        } });

        // 6a. Wingman firing a joiner's ability on a ship not marked yet: that ship acts all the same.
        final Ship fresh = ship("fresh");
        Hooks.runAsPilot(fresh, new Runnable() { public void run() {
            check(ModScript.who(Global.getCombatEngine()) == fresh, "runAsPilot: the ship acts before its mark");
        } });
        check(ModScript.who(Global.getCombatEngine()) == host, "after runAsPilot: the real answer");

        // 6b. Plugins added from the joiner's frame: one that keeps the ship runs for the joiner; a manager doesn't.
        final TestPlugins tp = new TestPlugins();
        Hooks.runAs(joiner, new Runnable() { public void run() { testmod.TimeScript.addBoth(joiner, tp.t, tp.m); } });
        Hooks.runAs(host, new Runnable() { public void run() { testmod.TimeScript.addBoth(host, tp.ht, tp.hm); } });
        check(added.size() == 4, "plugins reached the engine: " + added.size());
        realTime.unmodify("slow");
        com.fs.starfarer.title.PluginRunner.run(added, 0.016f);
        check(tp.t[0].sawPlayer == joiner, "joiner's time script sees the joiner as the player, got " + tp.t[0].sawPlayer);
        check(tp.m[0].sawPlayer == host, "a manager added from the joiner's frame stays the host's, got " + tp.m[0].sawPlayer);
        check(tp.ht[0].sawPlayer == host && tp.hm[0].sawPlayer == host, "host's plugins: the host");
        check(realTime.getModifiedValue() == 0.25f, "the host's own time script still slows time, got " + realTime.getModifiedValue());
        realTime.unmodify("slow");
        joiner.data.remove("wingman_remote"); // the joiner left: their plugin is the host's again
        com.fs.starfarer.title.PluginRunner.run(added, 0.016f);
        check(tp.t[0].sawPlayer == host, "after the joiner left, their plugin answers the real player ship");
        joiner.data.put("wingman_remote", Boolean.TRUE);
        realTime.unmodify("slow");

        // 6c. A joiner's bridge: it never moves anyone; the script carries on for the same ship.
        final Bridge hostBridge = new Bridge() {
            public boolean live() { return live; }
            public boolean moveRemote(Object from, Object to) { return to != host; }
            public float[] cursor(Object s) { return s == joiner ? new float[] { 100f, 60f } : null; }
            public boolean keyDown(Object s, int key) { return s == joiner && key == 30; }
            public boolean manual(Object s) { return s != joiner || !joinerAuto; }
        };
        Hooks.setBridge(new Bridge() {
            public boolean live() { return true; }
            public boolean moveRemote(Object from, Object to) { return false; }
            public float[] cursor(Object s) { return null; }
            public boolean keyDown(Object s, int key) { return false; }
            public boolean manual(Object s) { return s != joiner || !joinerAuto; }
        });
        engineCalls.clear();
        Hooks.runAs(joiner, new Runnable() { public void run() {
            ShipAPI after = ModScript.moveThenAsk(Global.getCombatEngine(), other);
            check(after == joiner && !engineCalls.contains("setPlayerShipExternal"), "joiner's bridge: nobody moved, got " + after);
            check(ModScript.mx() == 0, "joiner's bridge: no cursor known: this PC's mouse");
        } });
        Hooks.setBridge(hostBridge);

        // 6d. Mods' per-ship methods, called straight (not from inside a ship's frame): wrapped, they run for
        // their ship all the same. (On Java 22+ this is how a ship's frame is known; the game can't be changed.)
        testmod.Hull hull = new testmod.Hull();
        hull.advanceInCombat(joiner, 0.016f);
        check(hull.saw == joiner, "hullmod for the joiner's ship sees the joiner, got " + hull.saw);
        hull.advanceInCombat(host, 0.016f);
        check(hull.saw == host, "hullmod for the host's ship sees the host");
        check(ModScript.who(Global.getCombatEngine()) == host, "after a hullmod: the real answer");
        testmod.Hull.Sub sub = new testmod.Hull.Sub();
        sub.advanceInCombat(joiner, 0.016f);
        check(sub.saw == joiner && sub.sawToo == joiner, "a subclass calling super: no loop, the joiner's both times");
        hull.fail = true;
        try { hull.advanceInCombat(joiner, 0.016f); check(false, "the mod's exception came through"); } catch (IllegalStateException expected) { /* passed on */ }
        check(ModScript.who(Global.getCombatEngine()) == host, "after a mod's exception: the real answer again");
        hull.fail = false;
        final ShipAPI joinerApi = joiner;
        com.fs.starfarer.api.combat.WeaponAPI gunOnJoiner = (com.fs.starfarer.api.combat.WeaponAPI) Proxy.newProxyInstance(AgentRun.class.getClassLoader(),
                new Class<?>[] { com.fs.starfarer.api.combat.WeaponAPI.class }, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) { return m.getName().equals("getShip") ? joinerApi : null; }
        });
        testmod.Gun gun = new testmod.Gun();
        gun.advance(0.016f, Global.getCombatEngine(), gunOnJoiner);
        check(gun.saw == joiner, "weapon script on the joiner's ship sees the joiner, got " + gun.saw);
        com.fs.starfarer.api.combat.MutableShipStatsAPI statsOfJoiner = (com.fs.starfarer.api.combat.MutableShipStatsAPI) Proxy.newProxyInstance(AgentRun.class.getClassLoader(),
                new Class<?>[] { com.fs.starfarer.api.combat.MutableShipStatsAPI.class }, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) { return m.getName().equals("getEntity") ? joinerApi : null; }
        });
        testmod.Gun.Sys sys = new testmod.Gun.Sys();
        sys.apply(statsOfJoiner, "id", com.fs.starfarer.api.plugins.ShipSystemStatsScript.State.ACTIVE, 1f);
        check(sys.saw == joiner, "ship system script of the joiner's ship sees the joiner, got " + sys.saw);
        check(ModScript.who(Global.getCombatEngine()) == host, "after them: the real answer");

        // 7a. A bridge left from an earlier battle (not live): real answers.
        live = false;
        Hooks.runAs(joiner, new Runnable() { public void run() { realAnswers("bridge not live"); } });
        live = true;

        // 7. Bridge cleared (session over): real answers again.
        Hooks.setBridge(null);
        Hooks.runAs(joiner, new Runnable() { public void run() { realAnswers("bridge cleared"); } });
        Ship.log.clear();
        new CombatEngine().advanceShips(Arrays.asList(joiner), 0.016f);
        check(Ship.log.equals(Arrays.asList("joiner sees host")), "engine loop with no bridge: " + Ship.log);

        // 8. A class loader that can't reach the agent (parent: none): its classes are left alone, and still work.
        java.net.URL[] path = new java.net.URL[] { new java.io.File("build/agentfix/").toURI().toURL(),
                new java.io.File("build/apiimp/").toURI().toURL(), new java.io.File("build/agentstubs/").toURI().toURL(), new java.io.File("build/lib3/").toURI().toURL() };
        ClassLoader island = new java.net.URLClassLoader(path, null);
        Class<?> lone = Class.forName("testmod.ModScript", true, island);
        try {
            check(((Integer) lone.getMethod("mx").invoke(null)).intValue() == 0, "isolated loader: the real mouse");
        } catch (java.lang.reflect.InvocationTargetException e) {
            check(false, "isolated loader: " + e.getCause());
        }
        check(Hooks.report().contains("left alone (their loader can't reach the agent)"), "isolated loader counted: " + Hooks.report());

        System.out.println(Hooks.report());
        System.out.println(fails == 0 ? "AGENT RUN PASSED (" + checks + " checks)" : "AGENT RUN FAILED: " + fails + " of " + checks);
        if (fails > 0) System.exit(1);
    }

    static void realAnswers(String when) {
        CombatEngineAPI e = Global.getCombatEngine();
        engineCalls.clear();
        check(ModScript.who(e) == host, when + ": player ship");
        check(ModScript.isPlayer(e, host) && !ModScript.isPlayer(e, joiner), when + ": is-player test");
        ModScript.move(e, other);
        check(engineCalls.contains("setPlayerShipExternal"), when + ": move goes to the engine");
        engineCalls.clear();
        ModScript.status(e);
        check(engineCalls.contains("maintainStatusForPlayerShip"), when + ": status line shown");
        check(ModScript.time(e) == realTime, when + ": the real time multiplier");
        check(ModScript.mx() == 0 && ModScript.my() == 0, when + ": the real mouse");
        check(!ModScript.key(30), when + ": the real keyboard");
        check(ModScript.busy(e, 2) == 2 + 1 + 10 + 1 + 2, when + ": busy method, got " + ModScript.busy(e, 2));
        engineCalls.clear();
        ModScript.camera(e, new org.lwjgl.util.vector.Vector2f(1f, 2f));
        check(engineCalls.contains("view.setExternalControl") && engineCalls.contains("view.setCenter"), when + ": the camera moves");
        lastVolume = -1f;
        ModScript.beep();
        check(lastVolume == 1f, when + ": UI sound heard");
        check(ModScript.wouldDraw(host) && !ModScript.wouldDraw(joiner), when + ": interface code draws for this PC's player");
        check(!ModScript.byHand(e), when + ": this PC's player's autopilot");
    }

    static void joinerAnswers(String when, List<String> moves) {
        CombatEngineAPI e = Global.getCombatEngine();
        engineCalls.clear();
        check(ModScript.who(e) == joiner, when + ": player ship is the joiner's");
        check(ModScript.isPlayer(e, joiner) && !ModScript.isPlayer(e, host), when + ": is-player test");
        check(!engineCalls.contains("getPlayerShip"), when + ": engine not asked");
        ModScript.status(e);
        check(!engineCalls.contains("maintainStatusForPlayerShip"), when + ": status line dropped");
        MutableStat t = ModScript.time(e);
        check(t != realTime && t != null, when + ": time multiplier is a throwaway");
        t.modifyMult("x", 0.25f); // a script slowing time for "the player": must not touch the real one
        check(realTime.getModifiedValue() == 1f, when + ": the real time multiplier untouched");
        check(ModScript.mx() == 50 && ModScript.my() == 30, when + ": the joiner's cursor, got " + ModScript.mx() + "," + ModScript.my());
        check(ModScript.key(30) && !ModScript.key(31), when + ": the joiner's keys");
        check(ModScript.busy(e, 30) == 4 + 10 + 100 + 1 + 2, when + ": busy method, got " + ModScript.busy(e, 30));
        engineCalls.clear();
        ModScript.camera(e, new org.lwjgl.util.vector.Vector2f(1f, 2f));
        check(!engineCalls.contains("view.setExternalControl") && !engineCalls.contains("view.setCenter"), when + ": this PC's camera stays");
        lastVolume = -1f;
        ModScript.beep();
        check(lastVolume == 0f, when + ": UI sound silent here, got " + lastVolume);
        check(ModScript.wouldDraw(host) && !ModScript.wouldDraw(joiner), when + ": interface code still draws only for this PC's player");
        joinerAuto = false;
        check(ModScript.byHand(e), when + ": the joiner flies by hand");
        joinerAuto = true;
        check(!ModScript.byHand(e), when + ": the joiner's autopilot");
        joinerAuto = false;
        realTime.modifyMult("real", 0.5f);
        check(ModScript.time(e).getModifiedValue() == 0.5f, when + ": the time multiplier copy reads as the real one");
        realTime.unmodify("real");
        moves.clear();
        engineCalls.clear();
        ModScript.move(e, other);
        check(moves.equals(Arrays.asList("joiner->other")) && !engineCalls.contains("setPlayerShipExternal"), when + ": move is the joiner's: " + moves);
    }
}

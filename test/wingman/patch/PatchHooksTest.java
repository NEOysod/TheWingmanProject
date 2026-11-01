package wingman.patch;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Whose code a hooked call comes from (Scope), and the campaign lookups noted while recording. */
public final class PatchHooksTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        scopes();
        callers();
        misses();
        recordingMap();
        System.out.println("PatchHooksTest: " + checks + " checks passed");
    }

    private static void scopes() {
        check(Scope.of("data.scripts.hullmods.MyHullmod") == Scope.MOD, "a loose script is a mod's");
        check(Scope.of("org.magiclib.subsystems.MagicSubsystem") == Scope.MOD, "a library mod's class is a mod's");
        check(Scope.of("com.fs.starfarer.api.impl.combat.EntropyAmplifierStats") == Scope.MOD, "vanilla script code is a mod's");
        check(Scope.of("com.fs.starfarer.api.Global") == Scope.NONE, "the API itself is neither");
        check(Scope.of("com.fs.starfarer.api.impl.campaign.ui.SomeScreen") == Scope.NONE, "vanilla interface code is neither");
        check(Scope.of("com.fs.starfarer.combat.CombatEngine") == Scope.ENGINE, "the engine");
        check(Scope.of("com.fs.graphics.Sprite") == Scope.NONE, "the framework is neither");
        check(Scope.of("wingman.WingmanHostPlugin") == Scope.NONE, "Wingman itself is neither");
        check(Scope.of("org.lwjgl.input.Mouse") == Scope.NONE, "libraries are neither");
        check(Scope.of("java.util.ArrayList") == Scope.NONE, "Java is neither");
        check(Scope.of("org.magiclib.util.MagicUI") == Scope.NONE, "MagicUI draws this screen only");
        check(Scope.of("data.scripts.combat.hud.Overlay$1") == Scope.NONE, "a hud package draws this screen only");
        check(Scope.of("data.scripts.guidance.Missile") == Scope.MOD, "'gui' only as a whole package name");
        check(Scope.of((Class<?>) null) == Scope.NONE, "no class");
    }

    private static void callers() {
        // innermost first: the hook, PatchLib, the hooked method (and its super call), then the caller
        String c = Scope.pick(frames(
                "java.lang.Thread", "getStackTrace",
                "wingman.patch.Scope", "caller",
                "wingman.patch.Patches$Engine", "getPlayerShip",
                "patchlib.internal.Dispatch", "before",
                "com.fs.starfarer.combat.CombatEngine", "getPlayerShip",
                "com.fs.starfarer.combat.CombatEngine", "getPlayerShip",
                "java.lang.invoke.LambdaForm$MH", "invoke",
                "data.scripts.hullmods.MyHullmod", "advanceInCombat",
                "com.fs.starfarer.combat.entities.Ship", "advance"), "getPlayerShip");
        check("data.scripts.hullmods.MyHullmod".equals(c), "caller past hook, PatchLib and super calls: " + c);

        c = Scope.pick(frames("wingman.patch.Patches$Engine", "getPlayerShip", "patchlib.x.Y", "z"), "getPlayerShip");
        check(c == null, "no hooked method on the stack: no caller");

        // the real stack: callers() is Wingman's own (wingman.patch), never a hooked method's host
        check(Scope.caller("callers") == null, "Wingman's own frames are never the hooked method");
        check(!Scope.fromMod("callers"), "so not from a mod");
    }

    private static Iterator<String[]> frames(String... f) {
        String[][] out = new String[f.length / 2][];
        for (int i = 0; i < out.length; i++) out[i] = new String[] { f[2 * i], f[2 * i + 1] };
        return Arrays.asList(out).iterator();
    }

    private static void misses() {
        Hooks.takeMisses();
        Hooks.miss("$a");
        Hooks.miss("$b");
        Hooks.miss("$b");
        Hooks.miss(null);
        Map<String, Integer> m = Hooks.takeMisses();
        check(m.size() == 2 && m.get("$b") == 2 && m.get("$a") == 1, "counted: " + m);
        check(m.keySet().iterator().next().equals("$b"), "most asked first");
        check(Hooks.takeMisses().isEmpty(), "cleared once taken");
        for (int i = 0; i < Hooks.MAX_MISSES + 10; i++) Hooks.miss("$k" + i);
        check(Hooks.takeMisses().size() == Hooks.MAX_MISSES, "bounded");
    }

    private static void recordingMap() {
        Map<String, Object> inner = new HashMap<String, Object>();
        inner.put("there", 1);
        RecordingMap r = new RecordingMap(inner);
        Hooks.takeMisses();
        Hooks.setRecording(false);
        r.get("gone");
        check(Hooks.takeMisses().isEmpty(), "nothing noted while not recording");
        Hooks.setRecording(true);
        try {
            check(Integer.valueOf(1).equals(r.get("there")), "passes through");
            r.get("gone");
            r.containsKey("gone2");
            r.put("new", 2);
            check(inner.get("new").equals(2), "writes reach the real map");
            Map<String, Integer> m = Hooks.takeMisses();
            check(m.size() == 2 && m.containsKey("saved data: gone") && m.containsKey("saved data: gone2"), "misses noted: " + m);
        } finally {
            Hooks.setRecording(false);
        }
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
}

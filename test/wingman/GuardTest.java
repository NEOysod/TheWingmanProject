package wingman;

import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.input.InputEventAPI;

/** PluginGuard without a game: who is blamed for an error, and a guarded script switched off at its first error. */
public class GuardTest {
    static int fails;
    static void check(boolean ok, String what) { System.out.println((ok ? "ok   " : "FAIL ") + what); if (!ok) fails++; }

    public static class ModPlugin implements EveryFrameCombatPlugin {
        int advances;
        public void init(CombatEngineAPI e) {}
        public void processInputPreCoreControls(float a, List<InputEventAPI> ev) {}
        public void advance(float a, List<InputEventAPI> ev) { advances++; if (advances == 2) throw new NullPointerException("no campaign"); }
        public void renderInWorldCoords(ViewportAPI v) {}
        public void renderInUICoords(ViewportAPI v) {}
        class Helper { void go() { throw new IllegalStateException("helper"); } }
    }

    public static void main(String[] a) {
        check(PluginGuard.culprit(new RuntimeException()) == null, "nothing running: nobody blamed");
        PluginGuard.noteRunning(ModPlugin.class.getName());
        Throwable direct = null;
        try { new ModPlugin().advance(0, null); new ModPlugin() { { advances = 1; } }.advance(0, null); } catch (Throwable t) { direct = t; }
        // an anonymous subclass is GuardTest$1, not the plugin: blame comes from the frames of ModPlugin.advance
        check(ModPlugin.class.getName().equals(PluginGuard.culprit(direct)), "the script whose code threw is blamed");
        Throwable viaHelper = null;
        try { new ModPlugin().new Helper().go(); } catch (Throwable t) { viaHelper = t; }
        check(ModPlugin.class.getName().equals(PluginGuard.culprit(viaHelper)), "an inner class counts as its script");
        Throwable wrapped = new RuntimeException("game", direct);
        check(ModPlugin.class.getName().equals(PluginGuard.culprit(wrapped)), "found in the cause too");
        check(PluginGuard.culprit(new RuntimeException("elsewhere")) == null, "an error elsewhere blames no script");
        PluginGuard.battleEnded();
        check(PluginGuard.culprit(direct) == null, "after the battle nothing is blamed");

        ModPlugin p = new ModPlugin();
        PluginGuard.Guarded g = new PluginGuard.Guarded(p, null);
        List<InputEventAPI> none = new ArrayList<InputEventAPI>();
        boolean escaped = false;
        try { g.advance(0.016f, none); g.advance(0.016f, none); g.advance(0.016f, none); } catch (Throwable t) { escaped = true; }
        check(!escaped, "a guarded script's error doesn't reach the game");
        check(p.advances == 2, "switched off after its first error (ran " + p.advances + " times)");
        System.out.println(fails == 0 ? "GUARD PASSED" : fails + " FAILED");
    }
}

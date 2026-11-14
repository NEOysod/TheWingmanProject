package wingman;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.input.InputEventAPI;

/**
 * Joiner side: a safety net for mods' battle scripts (EveryFrameCombatPlugins). A joiner's battle is a copy
 * started from the title screen, with no campaign, and a mod's battle script written for a normal battle can trip
 * over that; the game then closes, with nothing for this mod to catch.
 *
 * So while a joiner's battle runs, the mods' battle scripts in it are known (CombatEngineAPI.getPlugins, new in
 * 0.98.5a), and Wingman listens to the game's log: when the game logs the error it is closing on and one of those
 * scripts is in it, that script's class is noted (SystemWatch). From the next battle on, on this PC, that script
 * runs guarded: wrapped so that an error in it switches it off for that battle, with a "Co-op:" message, and the
 * battle carries on. Only scripts that have crashed here are wrapped: a wrapped script can't be found by its own
 * class (CombatEngineAPI.getPluginOfClass), which some mods rely on, so the rest are left exactly as they are.
 * F5 in the CO-OP panel undoes it (SystemWatch.reset).
 */
final class PluginGuard {
    private PluginGuard() {}

    /** Mods' battle scripts running in this joiner's battle, by class. */
    private static final Set<String> running = new HashSet<String>();
    private static boolean listening;
    private static long scannedAt;

    /** Every so often in a joiner's battle: note the mods' scripts, and wrap any that crashed here before. */
    static void scan(CombatEngineAPI engine) {
        long now = System.currentTimeMillis();
        if (now - scannedAt < 1000) return;
        scannedAt = now;
        listen();
        List<EveryFrameCombatPlugin> all;
        try { all = engine.getPlugins(); } catch (Throwable t) { return; } // (older game: no list to look at)
        SystemWatch watch = SystemWatch.get();
        for (EveryFrameCombatPlugin p : all) {
            if (p == null || p instanceof Guarded) continue;
            String cls = p.getClass().getName();
            if (!isMods(cls)) continue;
            synchronized (running) { running.add(cls); }
            if (!watch.isGuarded(cls)) continue;
            try {
                engine.removePlugin(p);
                engine.addPlugin(new Guarded(p, engine)); // (addPlugin inits the wrapper, which leaves the script as it was)
                Dbg.info(PluginGuard.class, "Wingman: running " + cls + " guarded");
            } catch (Throwable t) {
                Global.getLogger(PluginGuard.class).warn("Wingman: couldn't guard " + cls, t);
            }
        }
    }

    static void battleEnded() {
        synchronized (running) { running.clear(); }
    }

    private static boolean isMods(String cls) {
        return !cls.startsWith("com.fs.") && !cls.startsWith("wingman.") && !cls.startsWith("java.");
    }

    /** The game's log, for the error it closes on. */
    private static void listen() {
        if (listening) return;
        listening = true;
        try {
            Logger.getRootLogger().addAppender(new AppenderSkeleton() {
                @Override
                protected void append(LoggingEvent e) {
                    try {
                        if (e.getThrowableInformation() == null || !e.getLevel().isGreaterOrEqual(Level.ERROR)) return;
                        String from = e.getLoggerName();
                        if (from == null || !from.startsWith("com.fs.")) return; // the game's own report, not a mod's caught error
                        String cls = culprit(e.getThrowableInformation().getThrowable());
                        if (cls != null) {
                            SystemWatch.get().guardFromNow(cls);
                            Global.getLogger(PluginGuard.class).warn("Wingman: " + cls + " (a mod's battle script) is in this error; it will run guarded in joiners' battles on this PC");
                        }
                    } catch (Throwable t) {
                        // never get in the way of the game's log
                    }
                }

                @Override
                public void close() {}

                @Override
                public boolean requiresLayout() { return false; }
            });
        } catch (Throwable t) {
            Dbg.info(PluginGuard.class, "Wingman: can't listen to the game's log (" + t + ")");
        }
    }

    /** The first of this battle's mods' scripts found in the error, or null. */
    static String culprit(Throwable t) {
        Set<String> now;
        synchronized (running) {
            if (running.isEmpty()) return null;
            now = new HashSet<String>(running);
        }
        for (int depth = 0; t != null && depth < 8; depth++, t = t.getCause()) {
            for (StackTraceElement el : t.getStackTrace()) {
                String c = el.getClassName();
                if (now.contains(c)) return c;
                for (int i = c.lastIndexOf('$'); i > 0; i = c.lastIndexOf('$', i - 1)) {
                    String outer = c.substring(0, i); // an inner class of the script (Helper, $1) counts as the script
                    if (now.contains(outer)) return outer;
                }
            }
        }
        return null;
    }

    /** For tests: as if this script were running in a joiner's battle. */
    static void noteRunning(String cls) {
        synchronized (running) { running.add(cls); }
    }

    /** A mod's battle script, switched off for the battle at its first error. */
    static final class Guarded implements EveryFrameCombatPlugin {
        final EveryFrameCombatPlugin inner;
        private final CombatEngineAPI engine;
        private boolean off;

        Guarded(EveryFrameCombatPlugin inner, CombatEngineAPI engine) {
            this.inner = inner;
            this.engine = engine;
        }

        private void failed(String where, Throwable t) {
            off = true;
            String name = inner.getClass().getName();
            Global.getLogger(PluginGuard.class).warn("Wingman: " + name + " (a mod's battle script) hit an error in " + where
                    + "; switched off for this battle", t);
            try {
                Ui.say(engine, "Co-op: " + name.substring(name.lastIndexOf('.') + 1)
                        + " (a mod's battle script) hit an error and is off for this battle. The battle goes on.");
            } catch (Throwable e) {
                // the log has it
            }
        }

        @Override
        public void init(CombatEngineAPI engine) {
            // Already set up by the game when the battle began, or by the mod that added it.
        }

        @Override
        public void processInputPreCoreControls(float amount, List<InputEventAPI> events) {
            if (off) return;
            try { inner.processInputPreCoreControls(amount, events); } catch (Throwable t) { failed("input", t); }
        }

        @Override
        public void advance(float amount, List<InputEventAPI> events) {
            if (off) return;
            try { inner.advance(amount, events); } catch (Throwable t) { failed("advance", t); }
        }

        @Override
        public void renderInWorldCoords(ViewportAPI viewport) {
            if (off) return;
            try { inner.renderInWorldCoords(viewport); } catch (Throwable t) { failed("drawing", t); }
        }

        @Override
        public void renderInUICoords(ViewportAPI viewport) {
            if (off) return;
            try { inner.renderInUICoords(viewport); } catch (Throwable t) { failed("drawing", t); }
        }
    }
}

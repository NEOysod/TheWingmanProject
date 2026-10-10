package wingman;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.ModSpecAPI;
import com.fs.starfarer.api.combat.ShipSystemSpecAPI;
import com.fs.starfarer.api.loading.BeamWeaponSpecAPI;
import com.fs.starfarer.api.loading.HullModSpecAPI;
import com.fs.starfarer.api.loading.MissileSpecAPI;
import com.fs.starfarer.api.loading.ProjectileSpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;

/**
 * The compatibility report: what scripted content the enabled mods bring, how co-op treats each kind, and
 * what has actually been seen of each item in play.
 *
 * Two sources. The scan reads the game's own lists of weapons, ship systems and hullmods and notes which
 * carry a script and of what kind; that is known before any battle. The evidence is counted during battles on
 * a joiner: for each kind of missile or shot, how many hits the game made by itself at the host's word and
 * how many had to be carried out by hand, how many copies had to be put back or could not be built, and
 * which scripts threw errors. Evidence is kept between sessions in saves/common/wingman_compat.data.
 *
 * It reports; it does not fix. An item that keeps turning up under "worth a look" needs a person to find
 * out why.
 */
final class Compat {
    private static final String FILE = "wingman_compat";
    private static Compat instance;

    /** key ("missile:id", "shot:id", "script:class") -> { went as intended, needed help or failed }. */
    private final Map<String, int[]> seen = new HashMap<String, int[]>();
    private boolean dirty, broken;

    static synchronized Compat get() {
        if (instance == null) instance = new Compat();
        return instance;
    }

    private Compat() {
        try {
            if (!Global.getSettings().fileExistsInCommon(FILE)) return;
            String text = Global.getSettings().readTextFileFromCommon(FILE);
            for (String line : text == null ? new String[0] : text.split("\n")) {
                String[] part = line.trim().split("\t");
                if (part.length != 3) continue;
                try { seen.put(part[0], new int[] { Integer.parseInt(part[1]), Integer.parseInt(part[2]) }); } catch (NumberFormatException e) { /* skip */ }
            }
        } catch (Throwable t) {
            Global.getLogger(Compat.class).warn("Wingman: could not read the compatibility notes; starting them afresh", t);
        }
    }

    private int[] entry(String key) {
        int[] e = seen.get(key);
        if (e == null) {
            if (seen.size() > 3000) return new int[2]; // far more kinds than any mod list has: stop taking notes
            e = new int[2];
            seen.put(key, e);
        }
        return e;
    }

    /** Went as intended. */
    void good(String key) { entry(key)[0]++; dirty = true; }
    /** Needed help (a hit carried out by hand, a copy put back) or failed (could not be built, a script error). */
    void odd(String key) { entry(key)[1]++; dirty = true; }

    void reset() {
        seen.clear();
        dirty = true;
        save();
    }

    /** Written at the end of a battle, not during one. */
    void save() {
        if (!dirty || broken) return;
        dirty = false;
        try {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, int[]> e : seen.entrySet()) {
                sb.append(e.getKey()).append('\t').append(Math.min(e.getValue()[0], 1000000)).append('\t').append(Math.min(e.getValue()[1], 1000000)).append('\n');
            }
            Global.getSettings().writeTextFileToCommon(FILE, sb.toString());
        } catch (Throwable t) {
            broken = true;
            Global.getLogger(Compat.class).warn("Wingman: could not write the compatibility notes", t);
        }
    }

    private static boolean modScript(String className) {
        return className != null && !className.trim().isEmpty() && !className.startsWith("com.fs.starfarer.");
    }

    private static String modName(ModSpecAPI mod) {
        if (mod == null) return null;
        String n = mod.getName();
        return n == null || n.trim().isEmpty() ? mod.getId() : n.trim();
    }

    /** One line per mod that brings scripted combat content, then the items worth a look. For the panel and the log. */
    List<String> report(boolean shotsHostDecided) {
        List<String> out = new ArrayList<String>();
        // per mod: missile hits, gun hits, on-fire effects, beam effects, ship systems, hullmods
        Map<String, int[]> mods = new TreeMap<String, int[]>(String.CASE_INSENSITIVE_ORDER);
        try {
            for (WeaponSpecAPI w : Global.getSettings().getActuallyAllWeaponSpecs()) {
                String mod = modName(w.getSourceMod());
                if (mod == null) continue;
                int[] c = mods.get(mod);
                if (c == null) { c = new int[6]; mods.put(mod, c); }
                Object ps = w.getProjectileSpec();
                if (ps instanceof MissileSpecAPI) {
                    if (modScript(((MissileSpecAPI) ps).getOnHitClassName())) c[0]++;
                    if (modScript(((MissileSpecAPI) ps).getOnFireClassName())) c[2]++;
                } else if (ps instanceof ProjectileSpecAPI) {
                    if (modScript(((ProjectileSpecAPI) ps).getOnHitClassName())) c[1]++;
                    if (modScript(((ProjectileSpecAPI) ps).getOnFireClassName())) c[2]++;
                }
                if (w instanceof BeamWeaponSpecAPI && modScript(((BeamWeaponSpecAPI) w).getEffectClassName())) c[3]++;
            }
            for (ShipSystemSpecAPI s : Global.getSettings().getAllShipSystemSpecs()) {
                String mod = modName(s.getSourceMod());
                if (mod == null || !modScript(s.getStatsScriptClassName())) continue;
                int[] c = mods.get(mod);
                if (c == null) { c = new int[6]; mods.put(mod, c); }
                c[4]++;
            }
            for (HullModSpecAPI h : Global.getSettings().getAllHullModSpecs()) {
                String mod = modName(h.getSourceMod());
                if (mod == null || !modScript(h.getEffectClass())) continue;
                int[] c = mods.get(mod);
                if (c == null) { c = new int[6]; mods.put(mod, c); }
                c[5]++;
            }
        } catch (Throwable t) {
            out.add("Could not read the game's lists of mod content: " + t);
            Global.getLogger(Compat.class).warn("Wingman: compatibility scan failed", t);
        }
        int listed = 0;
        for (Map.Entry<String, int[]> e : mods.entrySet()) {
            int[] c = e.getValue();
            if (c[0] + c[1] + c[2] + c[3] + c[4] + c[5] == 0) continue;
            List<String> bits = new ArrayList<String>();
            if (c[0] > 0) bits.add(c[0] + " missile hit effect" + (c[0] == 1 ? "" : "s"));
            if (c[1] > 0) bits.add(c[1] + " gun hit effect" + (c[1] == 1 ? "" : "s") + (shotsHostDecided ? "" : " (each PC's own)"));
            if (c[2] > 0) bits.add(c[2] + " on-fire effect" + (c[2] == 1 ? "" : "s"));
            if (c[4] > 0) bits.add(c[4] + " ship system" + (c[4] == 1 ? "" : "s"));
            if (c[5] > 0) bits.add(c[5] + " hullmod" + (c[5] == 1 ? "" : "s"));
            if (c[3] > 0) bits.add(c[3] + " beam effect" + (c[3] == 1 ? "" : "s") + " (each PC's own)");
            StringBuilder sb = new StringBuilder(e.getKey()).append(":  ");
            for (int i = 0; i < bits.size(); i++) sb.append(i == 0 ? "" : ", ").append(bits.get(i));
            out.add(sb.toString());
            listed++;
        }
        if (listed == 0) out.add("None of the enabled mods adds scripted weapons, ship systems or hullmods.");

        // what has been seen in play
        List<String> look = new ArrayList<String>();
        int kinds = 0, fine = 0;
        for (Map.Entry<String, int[]> e : seen.entrySet()) {
            int ok = e.getValue()[0], odd = e.getValue()[1];
            kinds++;
            boolean script = e.getKey().startsWith("script:"), system = e.getKey().startsWith("system:");
            if (script || system ? odd > 0 : (odd >= 5 && odd > ok)) {
                String what = e.getKey().substring(e.getKey().indexOf(':') + 1);
                look.add(system ? "ship system " + what + ": used " + (ok + odd) + " times as a joiner, did not start on the host " + odd + " time" + (odd == 1 ? "" : "s")
                        : script ? "script " + what + ": failed " + odd + " time" + (odd == 1 ? "" : "s")
                        : what + ": " + ok + " as intended, " + odd + " needed help");
            } else {
                fine++;
            }
        }
        Collections.sort(look);
        out.add("");
        if (kinds == 0) out.add("Nothing seen in play yet. This fills in as you join battles.");
        else out.add("Seen in play as a joiner: " + kinds + " kinds of missile, shot, script and ship system; " + fine + " behaved as intended.");
        for (String l : look) out.add("Worth a look:  " + l);
        return out;
    }
}

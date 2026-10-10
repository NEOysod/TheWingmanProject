package wingman;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipSystemAPI;

/**
 * A crash breadcrumb for ship systems from mods.
 *
 * A joiner's copies of ships have their systems switched on when the host's are, and the system's own script
 * then runs in the joiner's game. For a mod's script that is a step into the unknown: it was written for a
 * normal battle, and if it trips over something here the game itself goes down, with nothing for this mod to
 * catch. So the first time a mod's system is about to be switched on, its id is written to a small file
 * first. If it is still running five seconds later the id is marked as safe. If instead the game dies, the
 * id is still marked "in use" the next time the game starts, and from then on that system is left alone on
 * this PC (ships still show its effects on position, flux and so on, which come from the host).
 *
 * The CO-OP panel on the title screen lists the systems switched off this way and has a key to switch them
 * all back on. The notes are kept in saves/common/wingman_systems.data.
 */
final class SystemWatch {
    private static final String FILE = "wingman_systems";
    private static SystemWatch instance;

    private final Set<String> trusted = new HashSet<String>(), blocked = new HashSet<String>();
    private final Set<String> vanilla = new HashSet<String>();
    private final Map<String, Long> trying = new HashMap<String, Long>();

    // ---- Objectives from mods ----
    // A joiner's battle makes the host's objectives, and each objective kind's effect script starts with the
    // battle. Some were written for campaign battles only and reach for the campaign (the player fleet's
    // cargo, credits, a derelict fleet), which a joiner, on the title screen, doesn't have: the game then
    // crashes while setting the battle up, before any of this mod's code runs again. Those known to do so
    // are never made on a joiner; a plain stand-in marks the spot instead. Any other mod's kind is noted
    // here before it is made, as for ship systems, and blocked from the next start if the game died.
    /** Effect scripts (or packages, ending in a dot) known to need a running campaign. */
    static final String[] CAMPAIGN_ONLY_OBJECTIVES = {
            "data.scripts.combat.obj.SotfReinforcerEffect",   // Secrets of the Frontier: reads the player fleet's AI cores
            "SOTFECO.combatObj.SOTFECO_derelictFleetObj",     // SotF Extra Combat Objectives: campaign rewards
            "SOTFECO.combatObj.campaign.",
    };
    private final Set<String> objectivesTrying = new HashSet<String>();

    /** May a mod's objective of this kind, run by this effect script, be made in a joiner's battle? */
    boolean allowObjective(String type, String effectClass) {
        if (effectClass == null || effectClass.isEmpty() || effectClass.startsWith("com.fs.starfarer.")) return true;
        for (String c : CAMPAIGN_ONLY_OBJECTIVES) if (effectClass.equals(c) || (c.endsWith(".") && effectClass.startsWith(c))) return false;
        String id = "objective:" + type;
        if (blocked.contains(id)) return false;
        if (trusted.contains(id) || broken) return true;
        if (objectivesTrying.add(id)) save();
        return true;
    }

    /** A joiner's battle with these objectives came to an end without the game dying: they are safe here. */
    void objectivesSurvived() {
        if (objectivesTrying.isEmpty()) return;
        trusted.addAll(objectivesTrying);
        objectivesTrying.clear();
        save();
    }
    /** Systems found "in use" when the game started: blocked just now, to be told to the player once. */
    final List<String> newlyBlocked = new ArrayList<String>();
    private boolean broken;

    static synchronized SystemWatch get() {
        if (instance == null) instance = new SystemWatch();
        return instance;
    }

    private SystemWatch() {
        try {
            if (!Global.getSettings().fileExistsInCommon(FILE)) return;
            String text = Global.getSettings().readTextFileFromCommon(FILE);
            boolean changed = false;
            for (String line : text == null ? new String[0] : text.split("\n")) {
                line = line.trim();
                if (line.length() < 3) continue;
                String id = line.substring(2).trim();
                if (line.charAt(0) == 'T') trusted.add(id);
                else if (line.charAt(0) == 'B') blocked.add(id);
                else if (line.charAt(0) == 'P') { blocked.add(id); newlyBlocked.add(id); changed = true; }
            }
            if (changed) save();
        } catch (Throwable t) {
            Global.getLogger(SystemWatch.class).warn("Wingman: could not read the ship system safety file; every system gets a fresh chance", t);
        }
    }

    /** The systems currently switched off on this PC after a suspected crash, in name order. */
    List<String> blockedIds() {
        List<String> out = new ArrayList<String>(blocked);
        java.util.Collections.sort(out);
        return out;
    }

    /** Give every blocked system a fresh chance (the CO-OP panel's reset key). */
    void reset() {
        blocked.clear();
        newlyBlocked.clear();
        objectivesTrying.clear();
        broken = false;
        save();
    }

    /** May this system be switched on from the host's state? Notes the attempt first if it is a mod's and untried. */
    boolean allow(ShipSystemAPI sys, List<String> never) {
        String id;
        try { id = sys.getId(); } catch (Throwable t) { return true; }
        if (id == null) return true;
        if (blocked.contains(id) || (never != null && never.contains(id))) return false;
        if (trusted.contains(id) || vanilla.contains(id) || trying.containsKey(id) || broken) return true;
        Object script = null;
        try { script = sys.getScript(); } catch (Throwable t) { /* treated as the game's own */ }
        if (script == null || script.getClass().getName().startsWith("com.fs.starfarer.")) { vanilla.add(id); return true; }
        trying.put(id, System.currentTimeMillis());
        save();
        return true;
    }

    /** Call now and then: systems that have run for five seconds without the game dying are marked safe. */
    void tick() {
        if (trying.isEmpty()) return;
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Iterator<Map.Entry<String, Long>> it = trying.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, Long> e = it.next();
            if (now - e.getValue() < 5000) continue;
            trusted.add(e.getKey());
            it.remove();
            changed = true;
        }
        if (changed) save();
    }

    /** The battle ended normally: anything still on trial is neither safe nor blocked, and gets tried again. */
    void battleEnded() {
        if (trying.isEmpty()) return;
        trying.clear();
        save();
    }

    private void save() {
        if (broken) return;
        try {
            StringBuilder sb = new StringBuilder();
            for (String id : trusted) sb.append("T ").append(id).append('\n');
            for (String id : blocked) sb.append("B ").append(id).append('\n');
            for (String id : trying.keySet()) sb.append("P ").append(id).append('\n');
            for (String id : objectivesTrying) sb.append("P ").append(id).append('\n');
            Global.getSettings().writeTextFileToCommon(FILE, sb.toString());
        } catch (Throwable t) {
            broken = true; // can't keep notes: carry on without the safety net rather than block systems
            Global.getLogger(SystemWatch.class).warn("Wingman: could not write the ship system safety file", t);
        }
    }
}

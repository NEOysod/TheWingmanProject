package wingman;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;

import wingman.net.CampaignCopy;

/**
 * The campaign questions mods' battle scripts asked on this (joiner's) PC that the stand-in campaign couldn't
 * answer: memory keys and the sector's saved data. Listed by the agent's hooks (only with the agent), written
 * to the log when the battle is left, split into what the host has but couldn't send (not plain data) and
 * what wasn't in the copy at all. It tells what a mod list needs copied; it changes nothing.
 */
final class MissedLookups {
    private MissedLookups() {}

    static final int SHOWN = 40;

    static void log(CampaignCopy copy) {
        try {
            boolean recorded = AgentGate.recordedThisBattle;
            AgentGate.recordedThisBattle = false;
            AgentGate.recordLookups(false);
            AgentGate.recordedThisBattle = false;
            Map<String, Integer> misses = AgentGate.takeMisses();
            if (misses == null) return; // no agent: nothing was listed
            if (!recorded) {
                Global.getLogger(MissedLookups.class).info("Wingman campaign lookups: not recorded in this battle (no stand-in campaign: the host's battle wasn't a campaign one)");
                return;
            }
            if (misses.isEmpty()) {
                Global.getLogger(MissedLookups.class).info("Wingman campaign lookups: every lookup mods' scripts made here was answered (or none were made)");
                return;
            }
            List<String> hostHas = new ArrayList<String>(), notCopied = new ArrayList<String>();
            for (Map.Entry<String, Integer> e : misses.entrySet()) {
                String line = e.getKey() + " (" + e.getValue() + "x)";
                if (copy != null && copy.skippedKeys.contains(e.getKey())) hostHas.add(line); else notCopied.add(line);
            }
            Global.getLogger(MissedLookups.class).info("Wingman campaign lookups: mods' scripts asked for " + misses.size()
                    + " things this PC's stand-in campaign doesn't have" + (copy == null ? " (the host sent no campaign copy)" : ""));
            if (!hostHas.isEmpty()) Global.getLogger(MissedLookups.class).info("Wingman   the host has these, but they aren't plain data: " + join(hostHas));
            if (!notCopied.isEmpty()) Global.getLogger(MissedLookups.class).info("Wingman   not in the copy (often simply not set on the host either): " + join(notCopied));
        } catch (Throwable t) {
            Global.getLogger(MissedLookups.class).warn("Wingman: could not list the campaign lookups", t);
        }
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size() && i < SHOWN; i++) sb.append(i == 0 ? "" : ", ").append(l.get(i));
        if (l.size() > SHOWN) sb.append(", and ").append(l.size() - SHOWN).append(" more");
        return sb.toString();
    }
}

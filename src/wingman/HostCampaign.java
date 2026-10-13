package wingman;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.BattleCreationContext;
import com.fs.starfarer.api.combat.CombatEngineAPI;

import wingman.net.CampaignCopy;
import wingman.net.Out;
import wingman.net.Plain;

/**
 * Host side of the larger campaign copy (see CampaignCopy, CampaignStandIn): reads what mods' battle scripts may
 * look up in the campaign, as plain data only. Made once per battle, when the first joiner needs it.
 */
final class HostCampaign {
    private HostCampaign() {}

    /** The copy for this battle, encoded; null outside a campaign battle or if it couldn't be read. */
    static byte[] build(CombatEngineAPI engine) {
        try {
            if (engine == null || !engine.isInCampaign()) return null;
            SectorAPI sector = Global.getSector();
            if (sector == null || sector.getPlayerFleet() == null) return null;
            CampaignCopy c = new CampaignCopy();
            int[] budget = { CampaignCopy.MAX_BYTES - 64 * 1024 };
            Map<String, Object> pd = sector.getPersistentData();
            if (pd != null) budget[0] -= ShipMasteryBridge.addTo(pd, c.persistent); // first: it is made for this
            if (pd != null) copyMap(pd, c.persistent, c, budget);

            CampaignFleetAPI pf = sector.getPlayerFleet();
            BattleCreationContext ctx = engine.getContext();
            CampaignFleetAPI enemy = ctx == null ? null : ctx.getOtherFleet();
            PersonAPI enemyBoss = enemy == null ? null : enemy.getCommander();
            LocationAPI loc = pf.getContainingLocation();

            memory(c, "sector", sector.getMemoryWithoutUpdate(), budget);
            memory(c, "player", sector.getPlayerPerson() == null ? null : sector.getPlayerPerson().getMemoryWithoutUpdate(), budget);
            memory(c, "playerFleet", pf.getMemoryWithoutUpdate(), budget);
            memory(c, "playerFaction", sector.getPlayerFaction() == null ? null : sector.getPlayerFaction().getMemoryWithoutUpdate(), budget);
            memory(c, "enemyFleet", enemy == null ? null : enemy.getMemoryWithoutUpdate(), budget);
            memory(c, "enemyCommander", enemyBoss == null ? null : enemyBoss.getMemoryWithoutUpdate(), budget);
            memory(c, "enemyFaction", enemy == null || enemy.getFaction() == null ? null : enemy.getFaction().getMemoryWithoutUpdate(), budget);
            memory(c, "location", loc == null ? null : loc.getMemoryWithoutUpdate(), budget);

            try {
                FactionAPI me = sector.getPlayerFaction();
                if (me != null) {
                    c.playerFactionName = String.valueOf(me.getDisplayName());
                    for (FactionAPI f : sector.getAllFactions()) {
                        if (f == null || f == me || f.getId() == null) continue;
                        c.relations.put(f.getId(), me.getRelationship(f.getId()));
                    }
                }
            } catch (Throwable t) { /* no standings then */ }

            if (enemy != null) {
                try {
                    c.enemyFaction = enemy.getFaction() == null ? "" : enemy.getFaction().getId();
                    c.enemyFleetName = String.valueOf(enemy.getName());
                    if (enemyBoss != null && !enemyBoss.isDefault()) {
                        if (enemyBoss.getName() != null) {
                            c.enemyCommanderFirst = String.valueOf(enemyBoss.getName().getFirst());
                            c.enemyCommanderLast = String.valueOf(enemyBoss.getName().getLast());
                        }
                        c.enemyCommanderPortrait = enemyBoss.getPortraitSprite() == null ? "" : enemyBoss.getPortraitSprite();
                        c.enemyCommanderRank = enemyBoss.getRankId() == null ? "" : enemyBoss.getRankId();
                        c.enemyCommanderPost = enemyBoss.getPostId() == null ? "" : enemyBoss.getPostId();
                        c.enemyCommanderLevel = enemyBoss.getStats() == null ? 0 : enemyBoss.getStats().getLevel();
                    }
                } catch (Throwable t) { /* a nameless enemy then */ }
            }
            if (ctx != null) {
                c.playerGoal = ctx.getPlayerGoal() == null ? "" : ctx.getPlayerGoal().name();
                c.enemyGoal = ctx.getOtherGoal() == null ? "" : ctx.getOtherGoal().name();
            }
            if (loc != null) {
                c.locationName = loc.getName() == null ? "" : loc.getName();
                c.locationId = loc.getId() == null ? "" : loc.getId();
                c.hyperspace = loc.isHyperspace();
                Collection<String> tags = loc.getTags();
                if (tags != null) for (String t : tags) if (t != null && c.locationTags.size() < 200) c.locationTags.add(t);
            }
            try {
                c.timestamp = sector.getClock().getTimestamp();
                c.dateText = sector.getClock().getDateString();
            } catch (Throwable t) { /* no date then */ }

            byte[] out = c.encode();
            Global.getLogger(HostCampaign.class).info("Wingman: campaign copy for joiners: " + c.count() + " values ("
                    + c.persistent.size() + " of mods' saved data), " + c.skipped + " left out (not plain data), "
                    + (out.length / 1024) + " KB");
            return out;
        } catch (Throwable t) {
            Global.getLogger(HostCampaign.class).warn("Wingman: could not copy the campaign for joiners; they get the basic stand-in", t);
            return null;
        }
    }

    private static void memory(CampaignCopy c, String scope, MemoryAPI mem, int[] budget) {
        if (mem == null) return;
        Map<String, Object> into = new LinkedHashMap<String, Object>();
        try {
            for (String key : mem.getKeys()) {
                if (key == null || key.startsWith("$wingman")) continue;
                Object v;
                try { v = mem.get(key); } catch (Throwable t) { skip(c, key); continue; }
                put(key, v, into, c, budget, key);
            }
        } catch (Throwable t) { /* what was read so far */ }
        c.memories.put(scope, into);
    }

    private static void copyMap(Map<String, Object> from, Map<String, Object> into, CampaignCopy c, int[] budget) {
        try {
            for (Map.Entry<String, Object> e : from.entrySet()) {
                if (e.getKey() == null || e.getKey().startsWith("wingman")) continue;
                put(e.getKey(), e.getValue(), into, c, budget, "saved data: " + e.getKey());
            }
        } catch (Throwable t) { /* what was read so far */ }
    }

    /** Keep one value if it is plain data and there is room for it. */
    private static void put(String key, Object v, Map<String, Object> into, CampaignCopy c, int[] budget, String label) {
        if (key.length() > 200 || !Plain.isPlain(v)) { skip(c, label); return; }
        try {
            Out o = new Out();
            Plain.write(o, v);
            int size = o.toByteArray().length + key.length() * 2 + 8;
            if (size > budget[0]) { skip(c, label); return; }
            budget[0] -= size;
            into.put(key, v);
        } catch (Throwable t) {
            skip(c, label);
        }
    }

    /** Note a value left out, by the name a lookup on the joiner would show (see the missed-lookups log). */
    private static void skip(CampaignCopy c, String label) {
        c.skipped++;
        if (c.skippedKeys.size() < CampaignCopy.MAX_SKIPPED_KEYS && !c.skippedKeys.contains(label)) c.skippedKeys.add(label);
    }
}

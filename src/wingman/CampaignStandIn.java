package wingman;

import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;

import wingman.net.BattleInfo;
import wingman.net.CampaignCopy;

/**
 * A stand-in campaign on a joiner's PC, for mods' battle scripts to look at.
 *
 * A joiner's battle starts from the title screen, where the game has a sector but no player fleet, no player
 * cargo and none of the campaign's flags. Most mods never look; some do (an objective that counts the player's
 * AI cores, a hullmod that checks the player's skills or a campaign flag), and crash when they find nothing,
 * often while the battle is being set up, where nothing of ours can catch it. So, while the join mission is
 * set up, a player fleet is made from what the host sent: its cargo (commodities) and credits, a commander
 * with the player's name, level and skills, and the player's story points; the campaign's simple flags are
 * set on the sector's memory. The fleet is put in a place of its own (an empty star system, or hyperspace if
 * the host fights there), since scripts ask the player fleet where it is and crash on "nowhere" (Arma
 * Armatura's strikecraft hullmod looks for star coronas around it). The copies of ships are also put in stand-in fleets, one per side, so a script
 * that asks a ship for its fleet gets one.
 *
 * None of it changes the battle: the host's game still decides everything, and the stand-in only answers
 * questions. A real campaign is never touched: if this PC has a player fleet already, nothing is installed.
 * It is all taken away again when the joiner leaves the battle.
 */
public final class CampaignStandIn {
    private CampaignStandIn() {}

    private static CampaignFleetAPI player, enemy;
    private static boolean installed;
    private static final List<String> flagsSet = new ArrayList<String>();
    private static int storyPointsBefore = -1;
    private static boolean broken;
    /** Where the stand-in fleet is, and the empty star system made for it (null if it is in hyperspace). */
    private static LocationAPI place;
    private static StarSystemAPI madeSystem;
    private static long installedAt;
    /** The larger copy (see CampaignCopy) in use, and what of it was put into things that outlive the battle. */
    private static CampaignCopy copy;
    private static final List<String> persistentSet = new ArrayList<String>();
    private static final List<Object[]> memorySet = new ArrayList<Object[]>(); // {MemoryAPI, key}
    private static final java.util.Map<String, Float> relationsBefore = new java.util.LinkedHashMap<String, Float>();

    /** Put the host's campaign in place. Called while the join mission is set up. */
    static synchronized void install(BattleInfo b, CampaignCopy c) {
        SectorAPI sector = Global.getSector();
        if (sector == null || broken) return;
        if (installed) remove(); // an earlier battle's, left behind
        if (sector.getPlayerFleet() != null) return; // a real campaign: leave it alone
        if (!b.hasCampaign) return; // the host is not in a campaign battle either
        try {
            CampaignFleetAPI f = Global.getFactory().createEmptyFleet("player", "Wingman stand-in", true);
            try {
                for (int i = 0; i < b.cargoIds.size(); i++) f.getCargo().addCommodity(b.cargoIds.get(i), b.cargoAmounts.get(i));
                f.getCargo().getCredits().set(b.credits);
            } catch (Throwable t) { /* an empty hold then */ }
            try {
                PersonAPI me = Global.getFactory().createPerson();
                me.setName(new FullName(b.playerFirst, b.playerLast, FullName.Gender.ANY));
                if (!b.playerPortrait.isEmpty()) me.setPortraitSprite(b.playerPortrait);
                me.getStats().setLevel(b.playerLevel);
                for (int i = 0; i < b.skillIds.size(); i++) {
                    try { me.getStats().setSkillLevel(b.skillIds.get(i), b.skillLevels.get(i)); } catch (Throwable t) { /* a skill this game lacks */ }
                }
                f.setCommander(me);
            } catch (Throwable t) { /* a fleet without a named commander */ }
            sector.setPlayerFleet(f);
            player = f;
            copy = c;
            placeFleet(sector, f, b.hyperspace);
            installed = true;
            installedAt = System.currentTimeMillis();
            try {
                if (sector.getPlayerStats() != null) {
                    storyPointsBefore = sector.getPlayerStats().getStoryPoints();
                    sector.getPlayerStats().setStoryPoints(b.storyPoints);
                }
            } catch (Throwable t) { /* no story points then */ }
            try {
                MemoryAPI mem = sector.getMemoryWithoutUpdate();
                for (int i = 0; i < b.flagKeys.size(); i++) {
                    String key = b.flagKeys.get(i);
                    if (mem.contains(key)) continue;
                    mem.set(key, b.flagValue(i));
                    flagsSet.add(key);
                }
            } catch (Throwable t) { /* no flags then */ }
            String more = c == null ? "" : applyCopy(sector, c);
            Global.getLogger(CampaignStandIn.class).info("Wingman: stand-in campaign set up for mods' scripts: "
                    + b.cargoIds.size() + " kinds of cargo, " + b.skillIds.size() + " skills, " + flagsSet.size() + " flags" + more);
        } catch (Throwable t) {
            broken = true;
            Global.getLogger(CampaignStandIn.class).warn("Wingman: the stand-in campaign can't be made on this PC; mods' scripts will find none", t);
            remove();
        }
    }

    /**
     * The larger copy: mods' saved data, the memory of everything a battle script may ask about, standings with
     * the factions, the place. Anything put into what outlives the battle (the sector, factions, hyperspace) is
     * noted and taken away again by remove(); keys this PC already has are left as they are.
     */
    private static String applyCopy(SectorAPI sector, CampaignCopy c) {
        int persistent = 0, memory = 0, standings = 0;
        try {
            java.util.Map<String, Object> pd = sector.getPersistentData();
            for (java.util.Map.Entry<String, Object> e : c.persistent.entrySet()) {
                if (pd.containsKey(e.getKey())) continue;
                pd.put(e.getKey(), e.getValue());
                persistentSet.add(e.getKey());
                persistent++;
            }
        } catch (Throwable t) { /* without mods' saved data then */ }
        memory += fill(c, "sector", sector.getMemoryWithoutUpdate(), true);
        try { if (player.getCommander() != null) memory += fill(c, "player", player.getCommander().getMemoryWithoutUpdate(), false); } catch (Throwable t) { /* none */ }
        try { memory += fill(c, "playerFleet", player.getMemoryWithoutUpdate(), false); } catch (Throwable t) { /* none */ }
        try { memory += fill(c, "playerFaction", sector.getPlayerFaction().getMemoryWithoutUpdate(), true); } catch (Throwable t) { /* none */ }
        try {
            com.fs.starfarer.api.campaign.FactionAPI ef = c.enemyFaction.isEmpty() ? null : sector.getFaction(c.enemyFaction);
            if (ef != null) memory += fill(c, "enemyFaction", ef.getMemoryWithoutUpdate(), true);
        } catch (Throwable t) { /* none */ }
        try {
            if (place != null) memory += fill(c, "location", place.getMemoryWithoutUpdate(), place != madeSystem);
            if (madeSystem != null) for (String tag : c.locationTags) madeSystem.addTag(tag);
        } catch (Throwable t) { /* none */ }
        try {
            com.fs.starfarer.api.campaign.FactionAPI me = sector.getPlayerFaction();
            for (java.util.Map.Entry<String, Float> e : c.relations.entrySet()) {
                if (sector.getFaction(e.getKey()) == null) continue;
                if (!relationsBefore.containsKey(e.getKey())) relationsBefore.put(e.getKey(), me.getRelationship(e.getKey()));
                me.setRelationship(e.getKey(), e.getValue().floatValue());
                standings++;
            }
        } catch (Throwable t) { /* standings as they are here */ }
        return "; from the host's campaign: " + persistent + " of mods' saved values, " + memory + " memory values, "
                + standings + " faction standings" + (c.skipped > 0 ? " (" + c.skipped + " values the host couldn't send: not plain data)" : "");
    }

    /** Copy one memory scope; 'shared' memory (outliving the battle) only gets keys it lacks, each noted for removal. */
    private static int fill(CampaignCopy c, String scope, MemoryAPI mem, boolean shared) {
        java.util.Map<String, Object> m = c.memories.get(scope);
        if (m == null || mem == null) return 0;
        int n = 0;
        for (java.util.Map.Entry<String, Object> e : m.entrySet()) {
            try {
                if (shared && mem.contains(e.getKey())) continue;
                mem.set(e.getKey(), e.getValue());
                if (shared) memorySet.add(new Object[] { mem, e.getKey() });
                n++;
            } catch (Throwable t) { /* that one is left out */ }
        }
        return n;
    }

    /** Put the stand-in fleet somewhere: hyperspace if the host's battle is there, else an empty star system. */
    private static void placeFleet(SectorAPI sector, CampaignFleetAPI f, boolean hyperspace) {
        try {
            LocationAPI at = null;
            if (hyperspace) at = sector.getHyperspace();
            if (at == null) {
                madeSystem = sector.createStarSystem(copy != null && !copy.locationName.isEmpty() ? copy.locationName : "Wingman stand-in");
                at = madeSystem;
            }
            at.addEntity(f);
            f.setLocation(0f, 0f);
            place = at;
        } catch (Throwable t) {
            Global.getLogger(CampaignStandIn.class).warn("Wingman: the stand-in fleet could not be given a place; scripts that ask where "
                    + "the player fleet is may fail", t);
        }
    }

    // ---- the host's campaign fleets in the battle, one stand-in each (Msg.FLEET, see FleetInfo) ----

    private static final java.util.Map<Integer, CampaignFleetAPI> fleets = new java.util.HashMap<Integer, CampaignFleetAPI>();
    private static int fleetsFailed;

    /**
     * A fleet the host's battle has: a stand-in with its faction (if this game has it), name, memory flags and
     * commander (skills and all), so copies of its ships belong to it as on the host. The host's player fleet is
     * the stand-in player fleet, when there is one. Heard again (after a resync), it is made afresh.
     */
    static synchronized void onFleet(wingman.net.FleetInfo f) {
        try {
            SectorAPI sector = Global.getSector();
            if (sector == null) return;
            CampaignFleetAPI fleet;
            if (f.playerFleet && installed && player != null) {
                fleet = player;
            } else {
                String faction = !f.faction.isEmpty() && sector.getFaction(f.faction) != null ? f.faction : f.owner == 0 ? "player" : "independent";
                fleet = Global.getFactory().createEmptyFleet(faction, f.name.isEmpty() ? "Wingman stand-in" : f.name, true);
                if (f.commander != null) fleet.setCommander(toPerson(f.commander, true));
            }
            MemoryAPI mem = fleet.getMemoryWithoutUpdate();
            for (java.util.Map.Entry<String, Object> e : f.memory.entrySet()) {
                if (fleet == player && mem.contains(e.getKey())) continue;
                try { mem.set(e.getKey(), e.getValue()); } catch (Throwable t) { /* that one is left out */ }
            }
            addReserves(fleet, f.reserves);
            fleets.put(f.key, fleet);
        } catch (Throwable t) {
            if (fleetsFailed++ == 0) Global.getLogger(CampaignStandIn.class).warn("Wingman: a stand-in for one of the host's fleets could not be made; its ships use their side's", t);
        }
    }

    private static int reservesAdded, reservesFailed;

    /**
     * A simple copy of each of the fleet's ships that isn't here yet (hull, hull mods, wings, readiness), so that
     * a script looking over the fleet counts the same ships as on the host. A ship that is built later (it is
     * deployed on the host) takes its copy's place (see dropMember).
     */
    private static void addReserves(CampaignFleetAPI fleet, java.util.List<wingman.net.FleetInfo.Reserve> reserves) {
        if (reserves.isEmpty()) return;
        int added = 0;
        for (wingman.net.FleetInfo.Reserve r : reserves) {
            try {
                if (!r.id.isEmpty() && memberWithId(fleet, r.id) != null) continue; // already here
                com.fs.starfarer.api.combat.ShipHullSpecAPI spec = Global.getSettings().getHullSpec(r.hullId);
                if (spec == null) continue;
                com.fs.starfarer.api.combat.ShipVariantAPI v = Global.getSettings().createEmptyVariant(r.hullId + "_wingman_reserve", spec);
                for (String mod : r.permaMods) if (Global.getSettings().getHullModSpec(mod) != null) v.addPermaMod(mod, r.sMods.contains(mod));
                for (String mod : r.hullmods) if (Global.getSettings().getHullModSpec(mod) != null) v.addMod(mod);
                for (int i = 0; i < r.wings.size(); i++) {
                    String w = r.wings.get(i);
                    if (w != null && Global.getSettings().getFighterWingSpec(w) != null) v.setWingId(i, w);
                }
                FleetMemberAPI m = Global.getFactory().createFleetMember(com.fs.starfarer.api.fleet.FleetMemberType.SHIP, v);
                if (!r.id.isEmpty()) m.setId(r.id);
                if (!r.name.isEmpty()) m.setShipName(r.name);
                fleet.getFleetData().addFleetMember(m);
                m.getRepairTracker().setCR(r.cr);
                if (r.mothballed) m.getRepairTracker().setMothballed(true);
                added++;
            } catch (Throwable t) {
                if (reservesFailed++ == 0) Global.getLogger(CampaignStandIn.class).warn("Wingman: a ship of one of the host's fleets could not be copied for its stand-in", t);
            }
        }
        if (added > 0 && reservesAdded++ < 6) Global.getLogger(CampaignStandIn.class).info("Wingman: " + added + " ships of a host fleet ("
                + fleet.getNameWithFaction() + ") copied into its stand-in, for scripts that look over the whole fleet");
    }

    private static FleetMemberAPI memberWithId(CampaignFleetAPI fleet, String id) {
        for (FleetMemberAPI m : fleet.getFleetData().getMembersListCopy()) if (id.equals(m.getId())) return m;
        return null;
    }

    /** Take a ship's earlier copy (or a copy built before, see addReserves) out of its fleet, before it is put in again. */
    static void dropMember(CampaignFleetAPI fleet, String id) {
        if (fleet == null || id == null || id.isEmpty()) return;
        try {
            FleetMemberAPI old;
            while ((old = memberWithId(fleet, id)) != null) fleet.getFleetData().removeFleetMember(old);
        } catch (Throwable t) { /* both stay; only a count is off */ }
    }

    /** The stand-in for the host's fleet with this number, or null. */
    static synchronized CampaignFleetAPI fleetByKey(int key) { return key < 0 ? null : fleets.get(key); }

    /** A person as the host has them: name, face, id, faction, rank, post, personality, AI core, tags, memory (and skills). */
    static PersonAPI toPerson(wingman.net.Person p, boolean withSkills) {
        PersonAPI o = Global.getFactory().createPerson();
        fillPerson(o, p, withSkills);
        return o;
    }

    /** Give a person made here the host's identity for them; each part on its own, so one that fails costs only itself. */
    static void fillPerson(PersonAPI o, wingman.net.Person p, boolean withSkills) {
        try { if (!p.first.isEmpty() || !p.last.isEmpty()) o.setName(new FullName(p.first, p.last, FullName.Gender.ANY)); } catch (Throwable t) { /* nameless */ }
        try { if (!p.id.isEmpty()) o.setId(p.id); } catch (Throwable t) { /* its own id */ }
        try { if (!p.portrait.isEmpty()) o.setPortraitSprite(p.portrait); } catch (Throwable t) { /* default face */ }
        try { if (!p.faction.isEmpty() && Global.getSector() != null && Global.getSector().getFaction(p.faction) != null) o.setFaction(p.faction); } catch (Throwable t) { /* default faction */ }
        try { if (!p.rank.isEmpty()) o.setRankId(p.rank); } catch (Throwable t) { /* none */ }
        try { if (!p.post.isEmpty()) o.setPostId(p.post); } catch (Throwable t) { /* none */ }
        try { if (!p.personality.isEmpty()) o.setPersonality(p.personality); } catch (Throwable t) { /* default */ }
        try { if (!p.aiCore.isEmpty()) o.setAICoreId(p.aiCore); } catch (Throwable t) { /* none */ }
        try { if (p.level > 0) o.getStats().setLevel(p.level); } catch (Throwable t) { /* level 1 */ }
        if (withSkills) {
            for (int i = 0; i < p.skillIds.size(); i++) {
                try {
                    if (Global.getSettings().getSkillSpec(p.skillIds.get(i)) == null) continue;
                    o.getStats().setSkillLevel(p.skillIds.get(i), p.skillLevels.get(i));
                } catch (Throwable t) { /* a skill this game lacks */ }
            }
        }
        for (String tag : p.tags) { try { o.addTag(tag); } catch (Throwable t) { /* that one is left out */ } }
        try {
            MemoryAPI mem = o.getMemoryWithoutUpdate();
            for (java.util.Map.Entry<String, Object> e : p.memory.entrySet()) {
                try { mem.set(e.getKey(), e.getValue()); } catch (Throwable t) { /* that one is left out */ }
            }
        } catch (Throwable t) { /* no memory */ }
    }

    /** The host's larger copy this stand-in was made from, or null. */
    static synchronized CampaignCopy copyInUse() { return copy; }

    /** Whether the stand-in is in place now (the join mission asks, to decide which objectives it can make). */
    static synchronized boolean isInstalled() { return installed; }

    /** The stand-in fleet a copy of a ship on this side belongs to; null if there is none. */
    static synchronized CampaignFleetAPI fleetFor(int owner) {
        if (!installed || broken) return null;
        if (owner == 0) return player;
        if (owner != 1) return null;
        if (enemy == null) {
            try { enemy = makeEnemy(); } catch (Throwable t) { return null; }
        }
        return enemy;
    }

    /** The other side's stand-in fleet: the host's enemy's faction, name, commander and memory, when known. */
    private static CampaignFleetAPI makeEnemy() {
        CampaignCopy c = copy;
        String faction = "independent";
        try { if (c != null && !c.enemyFaction.isEmpty() && Global.getSector().getFaction(c.enemyFaction) != null) faction = c.enemyFaction; } catch (Throwable t) { /* independent */ }
        String name = c != null && !c.enemyFleetName.isEmpty() ? c.enemyFleetName : "Wingman stand-in";
        CampaignFleetAPI f = Global.getFactory().createEmptyFleet(faction, name, true);
        if (c == null) return f;
        try {
            if (!c.enemyCommanderFirst.isEmpty() || !c.enemyCommanderLast.isEmpty()) {
                PersonAPI boss = Global.getFactory().createPerson();
                boss.setFaction(faction);
                boss.setName(new FullName(c.enemyCommanderFirst, c.enemyCommanderLast, FullName.Gender.ANY));
                if (!c.enemyCommanderPortrait.isEmpty()) boss.setPortraitSprite(c.enemyCommanderPortrait);
                if (!c.enemyCommanderRank.isEmpty()) boss.setRankId(c.enemyCommanderRank);
                if (!c.enemyCommanderPost.isEmpty()) boss.setPostId(c.enemyCommanderPost);
                if (c.enemyCommanderLevel > 0) boss.getStats().setLevel(c.enemyCommanderLevel);
                f.setCommander(boss);
                fill(c, "enemyCommander", boss.getMemoryWithoutUpdate(), false);
            }
        } catch (Throwable t) { /* a fleet without a named commander */ }
        fill(c, "enemyFleet", f.getMemoryWithoutUpdate(), false);
        return f;
    }

    /**
     * Give the battle's context (engine.getContext(), which battle scripts read for "the fleets in this battle")
     * the stand-in fleets. Only the fleets change: the goals and every range stay the join mission's own, so
     * the battle itself runs exactly as before. Nothing happens without a stand-in or a context.
     */
    static synchronized void giveContext(com.fs.starfarer.api.combat.CombatEngineAPI engine) {
        if (!installed || broken || player == null || !(engine instanceof com.fs.starfarer.combat.CombatEngine)) return;
        try {
            com.fs.starfarer.api.combat.BattleCreationContext old = engine.getContext();
            if (old == null) return;
            CampaignFleetAPI other = fleetFor(1);
            if (other == null) return;
            com.fs.starfarer.api.combat.BattleCreationContext c = new com.fs.starfarer.api.combat.BattleCreationContext(player, old.getPlayerGoal(), other, old.getOtherGoal());
            c.setPlayerCommandPoints(old.getPlayerCommandPoints());
            c.setPursuitRangeModifier(old.getPursuitRangeModifier());
            c.setInitialDeploymentBurnDuration(old.getInitialDeploymentBurnDuration());
            c.setNormalDeploymentBurnDuration(old.getNormalDeploymentBurnDuration());
            c.setEscapeDeploymentBurnDuration(old.getEscapeDeploymentBurnDuration());
            c.setStandoffRange(old.getStandoffRange());
            c.setInitialEscapeRange(old.getInitialEscapeRange());
            c.setFlankDeploymentDistance(old.getFlankDeploymentDistance());
            c.setInitialStepSize(old.getInitialStepSize());
            c.setInitialNumSteps(old.getInitialNumSteps());
            c.extraEnemyStandoffRange = old.getExtraEnemyStandoffRange();
            c.aiRetreatAllowed = old.aiRetreatAllowed;
            c.objectivesAllowed = old.objectivesAllowed;
            c.forceObjectivesOnMap = old.forceObjectivesOnMap;
            c.enemyDeployAll = old.enemyDeployAll;
            c.fightToTheLast = old.fightToTheLast;
            ((com.fs.starfarer.combat.CombatEngine) engine).setContext(c);
        } catch (Throwable t) {
            Global.getLogger(CampaignStandIn.class).warn("Wingman: the battle's context could not be given the stand-in fleets; scripts see the mission's", t);
        }
    }

    /** Put a copy's fleet member in its side's stand-in fleet, so scripts that ask for its fleet get one. */
    static void enlist(FleetMemberAPI member, int owner) {
        CampaignFleetAPI f = fleetFor(owner);
        if (f == null || member == null) return;
        try {
            if (member.getFleetData() == null) f.getFleetData().addFleetMember(member);
        } catch (Throwable t) { /* without a fleet, as before */ }
    }

    /** On the title screen: if a stand-in is still in place (the battle was left another way), take it away. */
    static void removeIfLeft() {
        // The join mission is set up on the title screen a moment before its battle starts (and the mission list
        // sets it up just to show it): only a stand-in older than that is one left behind.
        if (installed && System.currentTimeMillis() - installedAt > 15000) remove();
    }

    /** Take it all away again (the joiner left the battle). */
    static synchronized void remove() {
        ShipMasteryBridge.uninstall(); // (before the saved data it put in goes)
        SectorAPI sector = Global.getSector();
        try {
            if (sector != null && installed && sector.getPlayerFleet() == player) sector.setPlayerFleet(null);
        } catch (Throwable t) { /* gone with the sector */ }
        try {
            if (sector != null && storyPointsBefore >= 0 && sector.getPlayerStats() != null) sector.getPlayerStats().setStoryPoints(storyPointsBefore);
        } catch (Throwable t) { /* gone with the sector */ }
        try {
            if (sector != null) for (String key : flagsSet) sector.getMemoryWithoutUpdate().unset(key);
        } catch (Throwable t) { /* gone with the sector */ }
        flagsSet.clear();
        try {
            if (sector != null) {
                java.util.Map<String, Object> pd = sector.getPersistentData();
                for (String key : persistentSet) pd.remove(key);
            }
        } catch (Throwable t) { /* gone with the sector */ }
        persistentSet.clear();
        for (Object[] ms : memorySet) {
            try { ((MemoryAPI) ms[0]).unset((String) ms[1]); } catch (Throwable t) { /* gone with the sector */ }
        }
        memorySet.clear();
        try {
            if (sector != null && !relationsBefore.isEmpty()) {
                for (java.util.Map.Entry<String, Float> e : relationsBefore.entrySet()) sector.getPlayerFaction().setRelationship(e.getKey(), e.getValue().floatValue());
            }
        } catch (Throwable t) { /* gone with the sector */ }
        relationsBefore.clear();
        copy = null;
        fleets.clear();
        try {
            if (place != null && player != null) place.removeEntity(player);
        } catch (Throwable t) { /* gone with the sector */ }
        try {
            if (sector != null && madeSystem != null) sector.removeStarSystem(madeSystem);
        } catch (Throwable t) { /* gone with the sector */ }
        place = null;
        madeSystem = null;
        storyPointsBefore = -1;
        player = null;
        enemy = null;
        installed = false;
    }
}

package wingman;

import com.fs.starfarer.api.combat.BattleObjectiveAPI;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.combat.BattleCreationContext;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.CombatNebulaAPI;

import wingman.net.BattleInfo;
import wingman.net.HostSession;
import wingman.net.Msg;

/** Host side of the battlefield itself: map size, backdrop, nebula clouds and asteroids. */
final class HostEnvironment {
    private static final int MAX_ASTEROIDS = 600;

    private final Map<CombatEntityAPI, Integer> asteroidIds = new HashMap<CombatEntityAPI, Integer>();
    private int nextAsteroidId = 1;
    private long lastAsteroidsAt;
    private float lastAsteroidBattleTime;
    private boolean asteroidsBroken;

    /**
     * What a joiner's stand-in campaign needs (see CampaignStandIn): the player fleet's cargo and credits, the
     * player's level, skills and story points, and the campaign's simple flags. Only in a campaign battle.
     */
    private static void campaignFacts(CombatEngineAPI engine, BattleInfo b) {
        if (!engine.isInCampaign()) return;
        com.fs.starfarer.api.campaign.SectorAPI sector = Global.getSector();
        if (sector == null || sector.getPlayerFleet() == null) return;
        com.fs.starfarer.api.campaign.CampaignFleetAPI pf = sector.getPlayerFleet();
        b.hasCampaign = true;
        try {
            b.credits = pf.getCargo().getCredits().get();
            for (com.fs.starfarer.api.campaign.CargoStackAPI st : pf.getCargo().getStacksCopy()) {
                if (b.cargoIds.size() >= BattleInfo.MAX_CARGO) break;
                if (!st.isCommodityStack() || st.getCommodityId() == null) continue;
                b.cargoIds.add(st.getCommodityId());
                b.cargoAmounts.add(st.getSize());
            }
        } catch (Throwable t) { /* no cargo then */ }
        try {
            com.fs.starfarer.api.characters.PersonAPI me = sector.getPlayerPerson();
            if (me != null) {
                if (me.getName() != null) {
                    b.playerFirst = String.valueOf(me.getName().getFirst());
                    b.playerLast = String.valueOf(me.getName().getLast());
                }
                if (me.getPortraitSprite() != null) b.playerPortrait = me.getPortraitSprite();
                b.playerLevel = me.getStats().getLevel();
                for (com.fs.starfarer.api.characters.MutableCharacterStatsAPI.SkillLevelAPI sl : me.getStats().getSkillsCopy()) {
                    if (b.skillIds.size() >= BattleInfo.MAX_SKILLS) break;
                    if (sl.getSkill() == null || sl.getLevel() <= 0) continue;
                    b.skillIds.add(sl.getSkill().getId());
                    b.skillLevels.add(sl.getLevel());
                }
            }
            if (sector.getPlayerStats() != null) b.storyPoints = sector.getPlayerStats().getStoryPoints();
        } catch (Throwable t) { /* no person then */ }
        try {
            com.fs.starfarer.api.campaign.rules.MemoryAPI mem = sector.getMemoryWithoutUpdate();
            for (String key : mem.getKeys()) {
                if (key == null || key.startsWith("$wingman")) continue;
                if (b.flagKeys.size() >= BattleInfo.MAX_FLAGS) break;
                b.addFlag(key, mem.get(key));
            }
        } catch (Throwable t) { /* no flags then */ }
    }

    /** Map size and backdrop. A joiner needs this before their mission starts, to set up the same battlefield. */
    byte[] battleInfo(CombatEngineAPI engine) {
        BattleInfo b = new BattleInfo();
        b.width = engine.getMapWidth();
        b.height = engine.getMapHeight();
        try {
            String bg = engine.getBackgroundSpriteName();
            if (bg != null) b.background = bg;
            Color c = engine.getBackgroundColor();
            if (c != null) { b.hasBackgroundColor = true; b.backgroundColor = c.getRGB(); }
            Color g = engine.getBackgroundGlowColor();
            if (g != null) { b.hasGlowColor = true; b.glowColor = g.getRGB(); }
            b.glowNonAdditive = engine.isBackgroundGlowColorNonAdditive();
            b.renderStarfield = engine.isRenderStarfield();
        } catch (Throwable t) {
            Global.getLogger(HostEnvironment.class).warn("Wingman: could not read the backdrop colours", t);
        }
        try {
            for (BattleObjectiveAPI o : engine.getObjectives()) {
                if (b.objectiveTypes.size() >= BattleInfo.MAX_OBJECTIVES) break;
                b.objectiveTypes.add(o.getType());
                b.objectiveX.add(o.getLocation().x);
                b.objectiveY.add(o.getLocation().y);
            }
        } catch (Throwable t) {
            Global.getLogger(HostEnvironment.class).warn("Wingman: could not list the objectives for joiners", t);
        }
        try {
            campaignFacts(engine, b);
        } catch (Throwable t) {
            Global.getLogger(HostEnvironment.class).warn("Wingman: could not read the campaign for joiners' stand-in", t);
        }
        try {
            campaignBackdrop(engine, b);
        } catch (Throwable t) {
            Global.getLogger(HostEnvironment.class).warn("Wingman: could not work out the backdrop planet", t);
        }
        return b.encode();
    }

    /**
     * A running battle doesn't say which planet is drawn behind it or whether it is in hyperspace. For a campaign
     * battle both follow from where the player's fleet is, so this repeats the game's own rule for picking them
     * (nearest planet within 1000 units, scaled by distance). Battles from the mission list have no such source.
     */
    private void campaignBackdrop(CombatEngineAPI engine, BattleInfo b) {
        if (!engine.isInCampaign() || engine.getContext() == null) return;
        BattleCreationContext context = engine.getContext();
        CampaignFleetAPI fleet = context.getPlayerFleet();
        if (fleet == null || fleet.getContainingLocation() == null) return;
        LocationAPI loc = fleet.getContainingLocation();
        b.hyperspace = loc == Global.getSector().getHyperspace();
        if (!(loc instanceof StarSystemAPI)) return;

        PlanetAPI closest = null;
        float minDist = Float.MAX_VALUE;
        for (PlanetAPI planet : ((StarSystemAPI) loc).getPlanets()) {
            if (planet.isStar()) continue;
            if ("lava".equals(planet.getTypeId()) || "lava_minor".equals(planet.getTypeId())) continue;
            if (planet.getSpec() != null && planet.getSpec().isDoNotShowInCombat()) continue;
            float dx = fleet.getLocation().x - planet.getLocation().x, dy = fleet.getLocation().y - planet.getLocation().y;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (dist < minDist && dist < 1000f) { closest = planet; minDist = dist; }
        }
        if (closest == null) return;

        float dist = Math.max(0f, minDist - closest.getRadius());
        float maxDist = Math.max(1f, 1000f - closest.getRadius());
        float f = (maxDist - dist) / maxDist * 0.65f + 0.35f;
        b.hasPlanet = true;
        b.planetType = closest.getTypeId();
        b.planetRadius = Math.max(100f, Math.min(500f, closest.getRadius() * f * 1.5f));
        b.planetBgWidth = 2048f * f;
        b.planetBgHeight = 2048f * f;
        if (hasStation(context.getPlayerFleet())) b.planetYOffset = -2048f / 2f * 0.5f;
        if (hasStation(context.getOtherFleet())) b.planetYOffset = 2048f / 2f * 0.5f;
    }

    private static boolean hasStation(CampaignFleetAPI fleet) {
        if (fleet == null || fleet.getFleetData() == null) return false;
        for (FleetMemberAPI m : fleet.getFleetData().getMembersListCopy()) if (m.isStation()) return true;
        return false;
    }

    /** Which map tiles have nebula in them, one bit per tile. Null if there is no nebula information. */
    byte[] nebula(CombatEngineAPI engine) {
        try {
            CombatNebulaAPI n = engine.getNebula();
            if (n == null) return null;
            int wide = n.getTilesWide(), high = n.getTilesHigh();
            if (wide <= 0 || high <= 0 || wide * high > 4000000) return null;
            byte[] bits = new byte[(wide * high + 7) / 8];
            boolean any = false;
            for (int y = 0; y < high; y++) {
                for (int x = 0; x < wide; x++) {
                    if (!n.tileHasNebula(x, y)) continue;
                    int i = y * wide + x;
                    bits[i >> 3] |= 1 << (i & 7);
                    any = true;
                }
            }
            Msg.W w = new Msg.W();
            w.out.writeInt(wide);
            w.out.writeInt(high);
            w.out.writeBoolean(any);
            if (any) w.out.write(bits);
            return w.done();
        } catch (Throwable t) {
            Global.getLogger(HostEnvironment.class).warn("Wingman: could not read the nebula, it will not be sent", t);
            return null;
        }
    }

    /** Where joiners were last told an asteroid is, and how fast it was going. */
    private static final class Rock { float x, y, vx, vy; long at; }
    private final Map<Integer, Rock> rocks = new HashMap<Integer, Rock>();
    private int asteroidRound;

    /** The next asteroid message lists every asteroid again (someone just joined). */
    void resendAsteroids() { asteroidRound = 0; }

    /**
     * Asteroids drift in straight lines, so a joiner can move them along by itself. Twice a second the host
     * sends only the ones that were knocked off course, and every five seconds the whole list, which is also
     * how a joiner learns that one is gone.
     */
    void tick(CombatEngineAPI engine, HostSession session) {
        if (asteroidsBroken || session.getPlayers().isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastAsteroidsAt < 500) return;
        lastAsteroidsAt = now;
        // How far a joiner's copy has drifted since last time is a matter of battle time, not clock time: the
        // battle stands still when paused, runs fast or slow with the speed setting, and falls behind the
        // clock whenever the frame rate is low.
        float battleNow = engine.getTotalElapsedTime(false);
        float passed = Math.max(0f, Math.min(2f, battleNow - lastAsteroidBattleTime));
        lastAsteroidBattleTime = battleNow;
        try {
            boolean full = asteroidRound++ % 10 == 0;
            List<CombatEntityAPI> all = engine.getAsteroids();
            Set<CombatEntityAPI> present = new HashSet<CombatEntityAPI>(all);
            for (Iterator<Map.Entry<CombatEntityAPI, Integer>> it = asteroidIds.entrySet().iterator(); it.hasNext();) {
                Map.Entry<CombatEntityAPI, Integer> e = it.next();
                if (present.contains(e.getKey())) continue;
                rocks.remove(e.getValue());
                it.remove();
            }
            List<CombatEntityAPI> send = new ArrayList<CombatEntityAPI>();
            int count = 0;
            for (CombatEntityAPI a : all) {
                if (count++ >= MAX_ASTEROIDS) break;
                Integer id = asteroidIds.get(a);
                if (id == null) { id = nextAsteroidId++; asteroidIds.put(a, id); }
                Rock r = rocks.get(id);
                float x = a.getLocation().x, y = a.getLocation().y, vx = a.getVelocity().x, vy = a.getVelocity().y;
                if (r != null) { // move our idea of the joiner's copy along, as the joiner's game does
                    r.x += r.vx * passed;
                    r.y += r.vy * passed;
                }
                boolean changed = r == null || Math.abs(r.x - x) > 6f || Math.abs(r.y - y) > 6f
                        || Math.abs(r.vx - vx) > 1f || Math.abs(r.vy - vy) > 1f;
                if (!full && !changed) continue;
                if (r == null) { r = new Rock(); rocks.put(id, r); }
                r.x = x; r.y = y; r.vx = vx; r.vy = vy;
                send.add(a);
            }
            if (send.isEmpty() && !full) return;
            Msg.W w = new Msg.W();
            w.out.writeBoolean(full);
            w.out.writeShort(send.size());
            for (CombatEntityAPI a : send) {
                w.out.writeInt(asteroidIds.get(a));
                w.out.writeFloat(a.getLocation().x);
                w.out.writeFloat(a.getLocation().y);
                w.out.writeFloat(a.getVelocity().x);
                w.out.writeFloat(a.getVelocity().y);
                w.out.writeShort(Math.round(Math.min(3000f, a.getCollisionRadius()) * 10f));
            }
            // The full list must arrive (it is how removals are learned); the small in-between ones may be skipped.
            if (full) session.broadcast(Msg.ASTEROIDS, w.done());
            else session.broadcastDroppable(Msg.ASTEROIDS, w.done());
        } catch (Throwable t) {
            asteroidsBroken = true;
            Global.getLogger(HostEnvironment.class).error("Wingman: sending asteroids failed, asteroid sync is off for this battle", t);
        }
    }
}

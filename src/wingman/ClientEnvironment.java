package wingman;

import com.fs.starfarer.api.combat.BattleObjectiveAPI;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.CombatNebulaAPI;

import wingman.net.BattleInfo;
import wingman.net.In;
import wingman.net.Msg;

/** Joiner side of the battlefield: checks the map matches the host's, paints the nebula, mirrors the asteroids. */
final class ClientEnvironment {
    private CombatEngineAPI engine;
    private final Map<Integer, CombatEntityAPI> asteroids = new HashMap<Integer, CombatEntityAPI>();
    private final Map<CombatEntityAPI, Integer> mine = new IdentityHashMap<CombatEntityAPI, Integer>();
    private float[] sizeRadius;
    private boolean asteroidsBroken, asteroidsLogged;
    /** Set when the host's map is a different size from ours; the plugin shows it once. */
    String mapMismatch;
    private boolean sameMap = true;

    void start(CombatEngineAPI engine) { this.engine = engine; }

    void reset() {
        for (CombatEntityAPI a : asteroids.values()) engine.removeEntity(a);
        asteroids.clear();
        mine.clear();
        mapMismatch = null;
    }

    boolean isMine(CombatEntityAPI asteroid) { return mine.containsKey(asteroid); }

    void onBattleInfo(byte[] data) throws Exception {
        BattleInfo b = BattleInfo.decode(data);
        float w = b.width, h = b.height;
        try {
            // Colours and the starfield can be changed while the battle runs; the planet and hyperspace can't.
            if (b.hasBackgroundColor) engine.setBackgroundColor(new Color(b.backgroundColor, true));
            if (b.hasGlowColor) {
                engine.setBackgroundGlowColor(new Color(b.glowColor, true));
                engine.setBackgroundGlowColorNonAdditive(b.glowNonAdditive);
            }
            engine.setRenderStarfield(b.renderStarfield);
        } catch (Throwable t) {
            Global.getLogger(ClientEnvironment.class).warn("Wingman: could not apply the backdrop colours", t);
        }
        Backdrop.apply(engine, b); // the planets' own look, and the background image if it differs
        Dbg.info(ClientEnvironment.class, "Wingman backdrop: host has " + (b.hasPlanet ? "planet " + b.planetType
                : "no planet") + (b.hyperspace ? ", hyperspace" : "") + ", image " + (b.background.isEmpty() ? "default" : b.background));
        sameMap = Math.abs(engine.getMapWidth() - w) < 2f && Math.abs(engine.getMapHeight() - h) < 2f;
        Dbg.info(ClientEnvironment.class, "Wingman map: host " + Math.round(w) + " x " + Math.round(h)
                + ", here " + Math.round(engine.getMapWidth()) + " x " + Math.round(engine.getMapHeight())
                + (sameMap ? " (match)" : " (DIFFERENT)"));
        // The host's objectives must exist here too: those of a mod's own kinds can only be made when the
        // mission is set up, from this same list.
        String missingKind = null;
        try {
            Map<String, Integer> have = new HashMap<String, Integer>();
            for (BattleObjectiveAPI o : engine.getObjectives()) {
                Integer c = have.get(o.getType());
                have.put(o.getType(), c == null ? 1 : c + 1);
            }
            for (String t : b.objectiveTypes) {
                Integer c = have.get(t);
                if (c == null || c <= 0) {
                    if (JoinSetup.standIns.contains(t)) continue; // shown with a stand-in on purpose
                    missingKind = t; break;
                }
                have.put(t, c - 1);
            }
        } catch (Throwable t) { /* no check */ }
        if (sameMap && missingKind != null) {
            sameMap = false;
            Dbg.info(ClientEnvironment.class, "Wingman objectives: the host has a '" + missingKind + "' objective this battle was set up without");
            mapMismatch = "The host's battle has objectives this one was set up without (" + missingKind + ").\n"
                    + "They can only be made when the battle starts, so it needs to be loaded again.";
        }
        if (!sameMap && missingKind == null) {
            mapMismatch = "The host's battlefield is " + Math.round(w) + " x " + Math.round(h) + " but this mission was set up as "
                    + Math.round(engine.getMapWidth()) + " x " + Math.round(engine.getMapHeight()) + ".\n"
                    + "Everything still works, but the map edges and nebula won't match.\n"
                    + "To fix it: leave this mission, click it again in the mission list while the host is in the battle, and start it.";
        }
    }

    void onNebula(byte[] data) {
        try {
            In in = Msg.reader(data);
            int wide = in.readInt(), high = in.readInt();
            if (!in.readBoolean()) return;
            byte[] bits = new byte[(wide * high + 7) / 8];
            in.readFully(bits);
            CombatNebulaAPI n = engine.getNebula();
            if (n == null || n.getTilesWide() != wide || n.getTilesHigh() != high) {
                Dbg.info(ClientEnvironment.class, "Wingman nebula: not shown, the map here is laid out differently ("
                        + (n == null ? "no nebula layer" : n.getTilesWide() + " x " + n.getTilesHigh()) + " tiles here, "
                        + wide + " x " + high + " on the host)");
                return;
            }
            int set = 0;
            for (int y = 0; y < high; y++) {
                for (int x = 0; x < wide; x++) {
                    int i = y * wide + x;
                    if ((bits[i >> 3] & (1 << (i & 7))) == 0 || n.tileHasNebula(x, y)) continue;
                    n.setHasNebula(x, y, 1f);
                    set++;
                }
            }
            Dbg.info(ClientEnvironment.class, "Wingman nebula: " + set + " tiles painted");
        } catch (Throwable t) {
            Global.getLogger(ClientEnvironment.class).warn("Wingman: could not paint the nebula", t);
        }
    }

    /** The game makes asteroids in four sizes; find out how big each is so the host's can be matched by radius. */
    private int sizeFor(float radius) {
        if (sizeRadius == null) {
            sizeRadius = new float[] { 10f, 16f, 24f, 36f };
            for (int s = 0; s < 4; s++) {
                CombatEntityAPI probe = engine.spawnAsteroid(s, -150000f, -140000f - s * 1000f, 0f, 0f);
                if (probe == null) continue;
                sizeRadius[s] = probe.getCollisionRadius();
                engine.removeEntity(probe);
            }
        }
        int best = 0;
        for (int s = 1; s < 4; s++) if (Math.abs(sizeRadius[s] - radius) < Math.abs(sizeRadius[best] - radius)) best = s;
        return best;
    }

    void onAsteroids(byte[] data) {
        if (asteroidsBroken) return;
        try {
            In in = Msg.reader(data);
            boolean full = in.readBoolean(); // the whole list, or only the asteroids that changed course
            int n = in.readShort() & 0xFFFF;
            Set<Integer> seen = new HashSet<Integer>();
            for (int i = 0; i < n; i++) {
                int id = in.readInt();
                float x = in.readFloat(), y = in.readFloat(), vx = in.readFloat(), vy = in.readFloat();
                float radius = (in.readShort() & 0xFFFF) / 10f;
                seen.add(id);
                CombatEntityAPI a = asteroids.get(id);
                if (a != null && !engine.isEntityInPlay(a)) { // broken up on our screen but not on the host's
                    mine.remove(a);
                    a = null;
                }
                if (a == null) {
                    a = engine.spawnAsteroid(sizeFor(radius), x, y, vx, vy);
                    if (a == null) continue;
                    asteroids.put(id, a);
                    mine.put(a, id);
                    continue;
                }
                float dx = x - a.getLocation().x, dy = y - a.getLocation().y;
                if (dx * dx + dy * dy > 25f * 25f) a.getLocation().set(x, y);
                a.getVelocity().set(vx, vy);
            }
            for (Iterator<Map.Entry<Integer, CombatEntityAPI>> it = asteroids.entrySet().iterator(); it.hasNext();) {
                Map.Entry<Integer, CombatEntityAPI> e = it.next();
                if (!full || seen.contains(e.getKey())) continue;
                mine.remove(e.getValue());
                engine.removeEntity(e.getValue());
                it.remove();
            }
            if (!asteroidsLogged && n > 0) {
                asteroidsLogged = true;
                Dbg.info(ClientEnvironment.class, "Wingman asteroids: mirroring " + n + " from the host");
            }
        } catch (Throwable t) {
            asteroidsBroken = true;
            Global.getLogger(ClientEnvironment.class).error("Wingman: showing asteroids failed, asteroid sync is off for this battle", t);
        }
    }
}

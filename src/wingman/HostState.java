package wingman;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.util.Misc;

import wingman.net.Extras;
import wingman.net.HostSession;
import wingman.net.Msg;

/**
 * Host side of three channels for what scripts change on ships and missiles that the position updates don't carry
 * (joiner side: JoinerState):
 *
 * - TARGETS: what each ship aims at. A ship system's script asks a ship that isn't the local player's for its mark
 *   through the ship's AI flags (TARGET_FOR_SHIP_SYSTEM, MANEUVER_TARGET, CARRIER_FIGHTER_TARGET,
 *   SYSTEM_TARGET_COORDS) and its R target; a joiner's copies have an AI that does nothing, so a targeted system
 *   (Entropy Amplifier, Interdictor Array, a mod's) found no mark there and did nothing or picked its own. Sent per
 *   ship when it changes. The host's own ship (no AI) is answered as the game answers its player: the R target, else
 *   the enemy nearest the mouse, and the mouse point.
 * - BODY: collision class, mass, collision radius, shields or system switched off by a script, the shield's arc and
 *   type, and the ship's tags (markers scripts put on it). Sent whole for a ship when any of it changes.
 * - MISSILE_WHO: a missile that changed sides or launching ship after it was launched (a hijack, a hand-over).
 *
 * Each is looked at 15 times a second; everything is sent again every 5 s (a joiner that hadn't built a ship yet
 * when its line came gets it then), and in full to a player who has just been sent the battle. Each part fails by
 * itself with one log line, leaving the rest.
 */
final class HostState {
    static final long LOOK_MS = 66, FULL_MS = 5000, CLOCK_MS = 1000;

    private final Map<ShipAPI, Integer> ids;
    private final Map<MissileAPI, Integer> missileIds;
    private final WingmanConfig cfg;
    private boolean aimsBroken, bodiesBroken, whoBroken;
    private long lastLook, lastFull, lastClock;
    private boolean clocksBroken;
    int clocksSent;
    private final Map<Integer, Extras.Aim> sentAims = new HashMap<Integer, Extras.Aim>();
    private final Map<Integer, Extras.Body> sentBodies = new HashMap<Integer, Extras.Body>();
    /** Each missile's owner and launching ship as first seen (as MISSILE_SPAWN told joiners) or last sent. */
    private final Map<Integer, int[]> missileWho = new HashMap<Integer, int[]>();
    int aimsSent, bodiesSent, whoSent;

    HostState(Map<ShipAPI, Integer> ids, Map<MissileAPI, Integer> missileIds, WingmanConfig cfg) {
        this.ids = ids;
        this.missileIds = missileIds;
        this.cfg = cfg;
    }

    /** A player has just been sent the whole battle: everything here goes out afresh too. */
    void resendAll() {
        sentAims.clear();
        sentBodies.clear();
    }

    void tick(CombatEngineAPI engine, HostSession session, Map<Integer, ShipAPI> byId) {
        if (session.getPlayers().isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastLook < LOOK_MS) return;
        lastLook = now;
        boolean full = now - lastFull >= FULL_MS;
        if (full) lastFull = now;
        if (cfg.syncScriptMemory && !aimsBroken) {
            try {
                sendAims(engine, session, byId, full);
            } catch (Throwable t) {
                aimsBroken = true;
                Global.getLogger(HostState.class).error("Wingman: sending ships' targets failed; they are not copied for this battle", t);
            }
        }
        if (cfg.syncStats && !bodiesBroken) {
            try {
                sendBodies(session, byId, full);
            } catch (Throwable t) {
                bodiesBroken = true;
                Global.getLogger(HostState.class).error("Wingman: sending ships' bodies failed; they are not copied for this battle", t);
            }
        }
        if (cfg.syncStats && !clocksBroken && now - lastClock >= CLOCK_MS) {
            lastClock = now;
            try {
                sendClocks(session, byId);
            } catch (Throwable t) {
                clocksBroken = true;
                Global.getLogger(HostState.class).error("Wingman: sending peak time and carrier rates failed; they are not copied for this battle", t);
            }
        }
        if (cfg.syncStats && !whoBroken) {
            try {
                sendMissileWho(session);
            } catch (Throwable t) {
                whoBroken = true;
                Global.getLogger(HostState.class).error("Wingman: sending missiles' owners failed; they are not copied for this battle", t);
            }
        }
    }

    // ---- targets ----

    private int ref(Object o) {
        if (!(o instanceof ShipAPI)) return -1;
        Integer id = ids.get(o);
        return id == null ? -1 : id;
    }

    private void sendAims(CombatEngineAPI engine, HostSession session, Map<Integer, ShipAPI> byId, boolean full) {
        List<Extras.Aim> out = new ArrayList<Extras.Aim>();
        ShipAPI player = engine.getPlayerShip();
        for (Map.Entry<Integer, ShipAPI> e : byId.entrySet()) {
            ShipAPI s = e.getValue();
            if (s == null || s.isFighter() || s.isHulk() || !s.isAlive()) continue;
            Extras.Aim a = aimOf(e.getKey(), s, s == player);
            Extras.Aim last = sentAims.get(e.getKey());
            if (!full && a.same(last)) continue;
            if (last == null && a.target < 0 && a.system < 0 && a.maneuver < 0 && a.carrier < 0 && !a.hasPoint) {
                sentAims.put(e.getKey(), a); // nothing to say yet
                continue;
            }
            sentAims.put(e.getKey(), a);
            out.add(a);
            if (out.size() >= 200) { session.broadcast(Msg.TARGETS, Extras.encodeAims(out)); aimsSent += out.size(); out = new ArrayList<Extras.Aim>(); }
        }
        if (!out.isEmpty()) { session.broadcast(Msg.TARGETS, Extras.encodeAims(out)); aimsSent += out.size(); }
        if (sentAims.size() > byId.size() + 50) sentAims.keySet().retainAll(byId.keySet());
    }

    private Extras.Aim aimOf(int id, ShipAPI s, boolean hostsOwn) {
        Extras.Aim a = new Extras.Aim();
        a.ship = id;
        ShipAPI target = s.getShipTarget();
        a.target = ref(target);
        if (hostsOwn && s.getShipAI() == null) {
            // The host's own ship: system scripts ask it, as the local player's, for its R target, else the enemy
            // nearest the mouse. A joiner's copy is an AI ship there, asked through the flags: answered the same way.
            Vector2f mouse = s.getMouseTarget();
            ShipAPI marked = target != null && target.isAlive() && !target.isHulk() ? target : null;
            ShipAPI near = marked;
            if (mouse != null) {
                if (marked == null) marked = Misc.findClosestShipEnemyOf(s, mouse, ShipAPI.HullSize.FRIGATE, 4000f, true);
                if (near == null) near = Misc.findClosestShipEnemyOf(s, mouse, ShipAPI.HullSize.FIGHTER, 4000f, true);
                a.hasPoint = true;
                a.px = mouse.x;
                a.py = mouse.y;
            }
            a.system = ref(marked);
            a.maneuver = ref(near);
            return a;
        }
        if (s.getShipAI() == null) return a; // no AI and not ours: nothing asks it (and asking would make it a store)
        ShipwideAIFlags f = s.getAIFlags();
        if (f == null) return a;
        a.system = ref(f.getCustom(ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM));
        a.maneuver = ref(f.getCustom(ShipwideAIFlags.AIFlags.MANEUVER_TARGET));
        a.carrier = ref(f.getCustom(ShipwideAIFlags.AIFlags.CARRIER_FIGHTER_TARGET));
        Object p = f.getCustom(ShipwideAIFlags.AIFlags.SYSTEM_TARGET_COORDS);
        if (p instanceof Vector2f) {
            a.hasPoint = true;
            a.px = ((Vector2f) p).x;
            a.py = ((Vector2f) p).y;
        }
        return a;
    }

    // ---- bodies ----

    private void sendBodies(HostSession session, Map<Integer, ShipAPI> byId, boolean full) {
        List<Extras.Body> out = new ArrayList<Extras.Body>();
        for (Map.Entry<Integer, ShipAPI> e : byId.entrySet()) {
            ShipAPI s = e.getValue();
            if (s == null || s.isFighter() || s.isHulk()) continue; // fighters come and go; wrecks are the joiner's to handle
            Extras.Body b = bodyOf(e.getKey(), s);
            Extras.Body last = sentBodies.get(e.getKey());
            if (!full && b.same(last)) continue;
            sentBodies.put(e.getKey(), b);
            out.add(b);
            if (out.size() >= 100) { session.broadcast(Msg.BODY, Extras.encodeBodies(out)); bodiesSent += out.size(); out = new ArrayList<Extras.Body>(); }
        }
        if (!out.isEmpty()) { session.broadcast(Msg.BODY, Extras.encodeBodies(out)); bodiesSent += out.size(); }
        if (sentBodies.size() > byId.size() + 50) sentBodies.keySet().retainAll(byId.keySet());
    }

    static Extras.Body bodyOf(int id, ShipAPI s) {
        Extras.Body b = new Extras.Body();
        b.ship = id;
        try { b.collision = s.getCollisionClass() == null ? -1 : s.getCollisionClass().ordinal(); } catch (Throwable t) { /* unknown */ }
        b.mass = s.getMass();
        b.radius = s.getCollisionRadius();
        if (s.isDefenseDisabled()) b.switches |= Extras.NO_DEFENSE;
        if (s.isShipSystemDisabled()) b.switches |= Extras.NO_SYSTEM;
        ShieldAPI sh = s.getShield();
        if (sh != null && sh.getType() != null && sh.getType() != ShieldAPI.ShieldType.NONE) {
            b.shieldArc = sh.getArc();
            b.shieldType = sh.getType().ordinal();
        }
        b.tags = tagsOf(s);
        return b;
    }

    /** The ship's own tags, in order, without Wingman's own; at most 40 of at most 80 characters. */
    static List<String> tagsOf(ShipAPI s) {
        java.util.Set<String> all = s.getTags();
        if (all == null || all.isEmpty()) return new ArrayList<String>(0);
        List<String> out = new ArrayList<String>(all.size());
        for (String t : all) if (t != null && t.length() <= 80 && !t.startsWith("wingman")) out.add(t);
        Collections.sort(out);
        return out.size() > 40 ? new ArrayList<String>(out.subList(0, 40)) : out;
    }

    // ---- peak time and carriers' replacement rates ----

    /**
     * Each second, every ship's time deployed (a joiner's copy starts counting when it is built, so one who came
     * in mid-battle saw full peak performance) and its bays' replacement rates (a joiner's bays build nothing, so
     * the rate there never fell).
     */
    private void sendClocks(HostSession session, Map<Integer, ShipAPI> byId) {
        List<Extras.Clock> out = new ArrayList<Extras.Clock>();
        for (Map.Entry<Integer, ShipAPI> e : byId.entrySet()) {
            ShipAPI s = e.getValue();
            if (s == null || s.isFighter() || s.isHulk() || !s.isAlive()) continue;
            Extras.Clock c = new Extras.Clock();
            c.ship = e.getKey();
            c.deployed = s.getTimeDeployedForCRReduction();
            List<com.fs.starfarer.api.combat.FighterLaunchBayAPI> bays = s.getLaunchBaysCopy();
            if (bays != null && !bays.isEmpty()) {
                c.bayRates = new float[Math.min(40, bays.size())];
                for (int i = 0; i < c.bayRates.length; i++) c.bayRates[i] = bays.get(i).getCurrRate();
            }
            out.add(c);
            if (out.size() >= 300) { session.broadcast(Msg.CLOCKS, Extras.encodeClocks(out)); clocksSent += out.size(); out = new ArrayList<Extras.Clock>(); }
        }
        if (!out.isEmpty()) { session.broadcast(Msg.CLOCKS, Extras.encodeClocks(out)); clocksSent += out.size(); }
    }

    // ---- missiles' owners ----

    private void sendMissileWho(HostSession session) {
        List<Extras.MissileWho> out = null;
        java.util.Set<Integer> live = new java.util.HashSet<Integer>();
        for (Map.Entry<MissileAPI, Integer> e : missileIds.entrySet()) {
            int mid = e.getValue();
            if (mid < 0) continue;
            live.add(mid);
            MissileAPI m = e.getKey();
            int owner = m.getOwner(), source = ref(m.getSource());
            int[] was = missileWho.get(mid);
            if (was == null) { missileWho.put(mid, new int[] { owner, source }); continue; } // as its launch said
            if (was[0] == owner && was[1] == source) continue;
            was[0] = owner;
            was[1] = source;
            Extras.MissileWho w = new Extras.MissileWho();
            w.missile = mid;
            w.owner = owner;
            w.source = source;
            if (out == null) out = new ArrayList<Extras.MissileWho>();
            out.add(w);
        }
        if (missileWho.size() > live.size() + 200) missileWho.keySet().retainAll(live);
        if (out == null) return;
        session.broadcast(Msg.MISSILE_WHO, Extras.encodeMissileWho(out));
        whoSent += out.size();
    }
}

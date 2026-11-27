package wingman;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;

import wingman.net.Extras;

/**
 * Joiner side of TARGETS, BODY and MISSILE_WHO (host side: HostState).
 *
 * - Targets go on our copies' R target and AI flags, where ship system scripts look for them on a ship that isn't
 *   the local player's. Never on the ship we fly: its target is ours.
 * - Bodies: collision class, mass, radius, disabled shields or system, shield arc and type, tags. On the ship we
 *   fly, its own scripts run here a round trip ahead of the host's (as its system does), so the host's figures are
 *   taken only once ours have differed for longer than that (round trip + 0.3 s, 0.5 to 1.5 s); a ship whose body
 *   Wingman sets itself (a wreck, a drone, a fighter) keeps its collision class.
 * - A missile that changed sides or launcher on the host does so here.
 *
 * What came last for each ship is kept and put on again twice a second, so a ship built after its line came, or an
 * AI the game gave back, still gets it. Each part switches itself off with one log line if it fails.
 */
final class JoinerState {
    /** What JoinerState needs from the joiner's plugin. */
    interface World {
        /** Our copy of the host's ship with that number, or null. */
        ShipAPI ship(int netId);
        /** Our copy of the host's missile with that number, or null. */
        MissileAPI missile(int id);
        /** The host's number for the ship we fly, -1 for none. */
        int ownId();
        /** Battle clock here (s). */
        float clock();
        /** How long the ship we fly may differ from the host's figures before they are taken (s). */
        float patience();
        /** True for a ship whose collision class Wingman sets itself (a wreck, a drone, a fighter, a stand-in). */
        boolean bodyIsOurs(ShipAPI s);
    }

    static final float AGAIN_EVERY = 0.5f, FLAG_TIME = 1f, ARC_STILL = 0.3f;

    private final World w;
    private final Map<Integer, Extras.Aim> aims = new HashMap<Integer, Extras.Aim>();
    private final Map<Integer, Extras.Body> bodies = new HashMap<Integer, Extras.Body>();
    /** Tags the host's last body for each ship had, so a tag the host drops is dropped here (and only those). */
    private final Map<Integer, List<String>> hostTags = new HashMap<Integer, List<String>>();
    private boolean aimsBroken, bodiesBroken, whoBroken;
    private float again, ownOffSince = -1f;
    int aimsApplied, bodiesApplied, whoApplied, ownBodiesTaken;

    JoinerState(World w) { this.w = w; }

    void reset() {
        aims.clear();
        bodies.clear();
        hostTags.clear();
        ownOffSince = -1f;
    }

    // ---- messages ----

    void onAims(byte[] data) {
        if (aimsBroken) return;
        try {
            for (Extras.Aim a : Extras.decodeAims(data)) {
                aims.put(a.ship, a);
                applyAim(a);
            }
        } catch (Throwable t) {
            aimsBroken = true;
            Global.getLogger(JoinerState.class).error("Wingman: ships' targets from the host couldn't be used; off for this battle", t);
        }
    }

    void onBodies(byte[] data) {
        if (bodiesBroken) return;
        try {
            for (Extras.Body b : Extras.decodeBodies(data)) {
                Extras.Body was = bodies.get(b.ship);
                b.seenAt = was != null && was.same(b) ? was.seenAt : w.clock(); // (the 5-second repeat changes nothing)
                bodies.put(b.ship, b);
                if (b.ship != w.ownId()) applyBody(b, false);
            }
        } catch (Throwable t) {
            bodiesBroken = true;
            Global.getLogger(JoinerState.class).error("Wingman: ships' bodies from the host couldn't be used; off for this battle", t);
        }
    }

    void onMissileWho(byte[] data) {
        if (whoBroken) return;
        try {
            for (Extras.MissileWho m : Extras.decodeMissileWho(data)) {
                MissileAPI missile = w.missile(m.missile);
                if (missile == null) continue;
                if (missile.getOwner() != m.owner) missile.setOwner(m.owner);
                ShipAPI source = m.source < 0 ? null : w.ship(m.source);
                if (source != null && missile.getSource() != source) missile.setSource(source);
                whoApplied++;
            }
        } catch (Throwable t) {
            whoBroken = true;
            Global.getLogger(JoinerState.class).error("Wingman: missiles' owners from the host couldn't be used; off for this battle", t);
        }
    }

    /** Each frame: the ship we fly's body after its patience; twice a second, everything again. */
    void tick(float amount) {
        if (!bodiesBroken) {
            try { ownBody(); } catch (Throwable t) { bodiesBroken = true; Global.getLogger(JoinerState.class).error("Wingman: our ship's body couldn't be kept; off for this battle", t); }
        }
        again += amount;
        if (again < AGAIN_EVERY) return;
        again = 0f;
        if (!aimsBroken) {
            try { for (Extras.Aim a : aims.values()) applyAim(a); }
            catch (Throwable t) { aimsBroken = true; Global.getLogger(JoinerState.class).error("Wingman: ships' targets couldn't be kept; off for this battle", t); }
        }
        if (!bodiesBroken) {
            try { for (Extras.Body b : bodies.values()) if (b.ship != w.ownId()) applyBody(b, false); }
            catch (Throwable t) { bodiesBroken = true; Global.getLogger(JoinerState.class).error("Wingman: ships' bodies couldn't be kept; off for this battle", t); }
        }
    }

    // ---- targets ----

    private void applyAim(Extras.Aim a) {
        if (a.ship == w.ownId()) return;
        ShipAPI s = w.ship(a.ship);
        if (s == null) return;
        ShipAPI target = a.target < 0 ? null : w.ship(a.target);
        if (target == s) target = null;
        if (s.getShipTarget() != target) s.setShipTarget(target);
        ShipwideAIFlags f = s.getAIFlags();
        if (f == null) return;
        flag(f, ShipwideAIFlags.AIFlags.TARGET_FOR_SHIP_SYSTEM, a.system);
        flag(f, ShipwideAIFlags.AIFlags.MANEUVER_TARGET, a.maneuver);
        flag(f, ShipwideAIFlags.AIFlags.CARRIER_FIGHTER_TARGET, a.carrier);
        if (a.hasPoint) {
            Object cur = f.getCustom(ShipwideAIFlags.AIFlags.SYSTEM_TARGET_COORDS);
            Vector2f at = cur instanceof Vector2f ? (Vector2f) cur : new Vector2f();
            at.set(a.px, a.py);
            f.setFlag(ShipwideAIFlags.AIFlags.SYSTEM_TARGET_COORDS, FLAG_TIME, at);
        } else {
            f.unsetFlag(ShipwideAIFlags.AIFlags.SYSTEM_TARGET_COORDS);
        }
        aimsApplied++;
    }

    private void flag(ShipwideAIFlags f, ShipwideAIFlags.AIFlags which, int id) {
        ShipAPI t = id < 0 ? null : w.ship(id);
        if (t != null) f.setFlag(which, FLAG_TIME, t);
        else f.unsetFlag(which);
    }

    // ---- bodies ----

    /** The ship we fly: the host's body is taken once ours has differed from it for longer than the patience. */
    private void ownBody() {
        int own = w.ownId();
        Extras.Body b = own < 0 ? null : bodies.get(own);
        ShipAPI s = b == null ? null : w.ship(own);
        if (s == null) { ownOffSince = -1f; return; }
        Extras.Body here = HostState.bodyOf(own, s);
        boolean hostTagsHere = true; // tags only our own scripts put on are no difference
        for (String t : b.tags) if (!s.hasTag(t)) { hostTagsHere = false; break; }
        if (hostTagsHere) here.tags = b.tags;
        if (b.same(here)) { ownOffSince = -1f; return; }
        float now = w.clock();
        if (ownOffSince < 0f) { ownOffSince = now; return; }
        if (now - ownOffSince < w.patience()) return;
        applyBody(b, true);
        ownBodiesTaken++;
        ownOffSince = -1f;
    }

    private void applyBody(Extras.Body b, boolean own) {
        ShipAPI s = w.ship(b.ship);
        if (s == null || s.isHulk()) return;
        if (b.collision >= 0 && b.collision < CollisionClass.values().length && !w.bodyIsOurs(s)) {
            CollisionClass c = CollisionClass.values()[b.collision];
            if (s.getCollisionClass() != c) s.setCollisionClass(c);
        }
        if (b.mass == b.mass && Math.abs(s.getMass() - b.mass) > Math.max(0.01f, b.mass * 0.005f)) s.setMass(b.mass);
        if (b.radius == b.radius && Math.abs(s.getCollisionRadius() - b.radius) > Math.max(0.01f, b.radius * 0.005f)) s.setCollisionRadius(b.radius);
        boolean noDefense = (b.switches & Extras.NO_DEFENSE) != 0, noSystem = (b.switches & Extras.NO_SYSTEM) != 0;
        if (s.isDefenseDisabled() != noDefense) s.setDefenseDisabled(noDefense);
        if (s.isShipSystemDisabled() != noSystem) s.setShipSystemDisabled(noSystem);
        ShieldAPI sh = s.getShield();
        if (b.shieldType >= 0 && b.shieldType < ShieldAPI.ShieldType.values().length) {
            ShieldAPI.ShieldType type = ShieldAPI.ShieldType.values()[b.shieldType];
            if (sh == null && !own) {
                s.setShield(type, 0f, 1f, b.shieldArc == b.shieldArc ? b.shieldArc : 90f); // a copy's flux comes from the host anyway
            } else if (sh != null) {
                if (sh.getType() != type) sh.setType(type);
                // The arc only once the host's has held still a moment: a system that sweeps it open runs here too,
                // and setting each step of the host's a round trip late would make it stutter.
                if (b.shieldArc == b.shieldArc && Math.abs(sh.getArc() - b.shieldArc) >= 1f && w.clock() - b.seenAt >= ARC_STILL) sh.setArc(b.shieldArc);
            }
        }
        tags(b, s);
        bodiesApplied++;
    }

    /** The host's tags put on; a tag the host had and dropped is dropped here (a tag only our scripts put is left). */
    private void tags(Extras.Body b, ShipAPI s) {
        List<String> before = hostTags.get(b.ship);
        if (before != null) for (String t : before) if (!b.tags.contains(t) && s.hasTag(t)) s.removeTag(t);
        for (String t : b.tags) if (!s.hasTag(t)) s.addTag(t);
        hostTags.put(b.ship, new ArrayList<String>(b.tags));
    }
}

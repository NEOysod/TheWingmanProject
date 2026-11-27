package wingman;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

import wingman.net.Extras;
import wingman.net.HostSession;
import wingman.net.Msg;

/**
 * Host side of SHOT_FATES (joiner side: JoinerShots): what became of ordinary shots, where a joiner's copy of the
 * shot may not do the same by itself.
 *
 * Ordinary shots (not missiles, no on-hit script: those have their own reports) are fired on every PC from the
 * firing updates and left to fly there. Mostly they end alike. They don't where: the host's struck something the
 * copy missed (ships a little apart on the two screens), or a missile (a missile's copy never collides on a joiner,
 * so shots flew through); or a script took the shot off the field (absorbed, eaten by a field) or turned it
 * (reflected, redirected, handed to the other side) on the host. Every frame the host follows each ordinary shot
 * and reports those: a hit with the point in the struck ship's own frame; a shot gone before the end of its range
 * without striking; a sharp turn in one frame (over 12 degrees, a speed change over 40%, or a change of side).
 * Gradual steering is left alone: the script doing it runs on the joiner's copy too.
 *
 * At most 600 reports a second; sent with each frame's batch on the droppable path (a late fate is no use).
 */
final class HostShots {
    static final int MAX_PER_SECOND = 600;
    static final float TURN_COS = (float) Math.cos(Math.toRadians(12));

    private static final class Track {
        boolean skip, hit;
        int frame, source, weapon, owner;
        ShipAPI sourceShip;
        float vx, vy, x, y, age, seenAt, life, turnedAt = -1f;
        boolean fading;
    }

    private static final Track SKIP = new Track();
    static { SKIP.skip = true; }

    private final Map<ShipAPI, Integer> ids;
    private final Map<DamagingProjectileAPI, Track> tracks = new IdentityHashMap<DamagingProjectileAPI, Track>();
    /** Projectile kinds with an on-hit script (reported as SHOT_HIT by the host plugin instead). */
    private final Map<String, Boolean> scripted = new HashMap<String, Boolean>();
    private final List<Extras.ShotFate> batch = new ArrayList<Extras.ShotFate>();
    private int frame, thisSecond;
    /** The host's battle clock (as position updates carry it) this frame. */
    private float now;
    private long second;
    private boolean broken;
    int hits, gone, turned, dropped;

    HostShots(Map<ShipAPI, Integer> ids) { this.ids = ids; }

    void tick(CombatEngineAPI engine, HostSession session, float gameClock) {
        now = gameClock;
        if (broken) return;
        if (session.getPlayers().isEmpty()) { tracks.clear(); return; }
        try {
            follow(engine);
            if (!batch.isEmpty()) {
                for (int i = 0; i < batch.size(); i += 200) {
                    if (!session.broadcastDroppable(Msg.SHOT_FATES, Extras.encodeShotFates(batch.subList(i, Math.min(batch.size(), i + 200))))) dropped++;
                }
                batch.clear();
            }
        } catch (Throwable t) {
            broken = true;
            tracks.clear();
            Global.getLogger(HostShots.class).error("Wingman: following shots failed; what becomes of them is not sent for this battle", t);
        }
    }

    private boolean scriptedKind(DamagingProjectileAPI p, String kind) {
        Boolean s = scripted.get(kind);
        if (s == null) {
            boolean has = false;
            try {
                com.fs.starfarer.api.loading.ProjectileSpecAPI spec = p.getProjectileSpec();
                String cls = spec == null ? null : spec.getOnHitClassName();
                has = cls != null && !cls.trim().isEmpty();
            } catch (Throwable t) { /* ordinary */ }
            scripted.put(kind, s = has);
        }
        return s;
    }

    private void follow(CombatEngineAPI engine) {
        frame++;
        List<DamagingProjectileAPI> all = engine.getProjectiles();
        for (int i = 0, n = all.size(); i < n; i++) {
            DamagingProjectileAPI p = all.get(i);
            if (p instanceof MissileAPI) continue;
            Track t = tracks.get(p);
            if (t == null) {
                t = start(p);
                tracks.put(p, t);
            }
            if (t == SKIP) continue;
            t.frame = frame;
            Vector2f v = p.getVelocity(), at = p.getLocation();
            if (!t.hit && p.didDamage()) {
                t.hit = true;
                hit(p, t, at);
            } else if (!t.hit) {
                float sp0 = (float) Math.sqrt(t.vx * t.vx + t.vy * t.vy), sp1 = (float) Math.sqrt(v.x * v.x + v.y * v.y);
                boolean sharp = false;
                if (sp0 > 1f && sp1 > 1f) {
                    float cos = (t.vx * v.x + t.vy * v.y) / (sp0 * sp1);
                    sharp = cos < TURN_COS || sp1 / sp0 > 1.4f || sp1 / sp0 < 0.6f;
                } else if (sp0 > 1f != sp1 > 1f) sharp = true; // stopped dead, or set going
                ShipAPI src = p.getSource();
                boolean sides = p.getOwner() != t.owner || src != t.sourceShip;
                if ((sharp || sides) && p.getElapsed() - t.turnedAt > 0.15f) {
                    t.turnedAt = p.getElapsed();
                    turned(p, t, at, v);
                }
                t.owner = p.getOwner();
                t.sourceShip = src;
            }
            t.vx = v.x;
            t.vy = v.y;
            t.x = at.x;
            t.y = at.y;
            t.age = p.getElapsed();
            t.seenAt = now;
            t.fading = p.isFading();
        }
        // Gone from the field since last frame.
        for (Iterator<Map.Entry<DamagingProjectileAPI, Track>> it = tracks.entrySet().iterator(); it.hasNext();) {
            Map.Entry<DamagingProjectileAPI, Track> e = it.next();
            Track t = e.getValue();
            if (t == SKIP) {
                if (!engine.isEntityInPlay(e.getKey())) it.remove();
                continue;
            }
            if (t.frame == frame) continue;
            DamagingProjectileAPI p = e.getKey();
            it.remove();
            if (t.hit) continue;
            if (p.didDamage()) { hit(p, t, new Vector2f(t.x, t.y)); continue; } // struck and was gone between two frames
            if (t.fading || t.age >= t.life * 0.85f) continue; // spent: every copy ends there too
            gone(t);
        }
    }

    private Track start(DamagingProjectileAPI p) {
        String kind = p.getProjectileSpecId();
        if (kind == null || scriptedKind(p, kind)) return SKIP;
        ShipAPI src = p.getSource();
        Integer sid = src == null ? null : ids.get(src);
        WeaponAPI w = p.getWeapon();
        if (sid == null || w == null || w.getId() == null) return SKIP;
        Track t = new Track();
        t.source = sid;
        t.sourceShip = src;
        t.weapon = w.getId().hashCode();
        t.owner = p.getOwner();
        float speed = Math.max(1f, p.getMoveSpeed());
        t.life = Math.max(0.2f, w.getRange() / speed);
        t.vx = p.getVelocity().x;
        t.vy = p.getVelocity().y;
        return t;
    }

    private boolean room() {
        long now = System.currentTimeMillis();
        if (now - second >= 1000) { second = now; thisSecond = 0; }
        return thisSecond++ < MAX_PER_SECOND;
    }

    private Extras.ShotFate fate(int kind, Track t, float at, float age, float x, float y) {
        Extras.ShotFate f = new Extras.ShotFate();
        f.at = at;
        f.kind = kind;
        f.source = t.source;
        f.weapon = t.weapon;
        f.age = age;
        f.x = x;
        f.y = y;
        return f;
    }

    private void hit(DamagingProjectileAPI p, Track t, Vector2f at) {
        if (!room()) return;
        Extras.ShotFate f = fate(Extras.SHOT_HIT, t, now, p.getElapsed(), at.x, at.y);
        CombatEntityAPI struck = p.getDamageTarget();
        Integer tid = struck instanceof ShipAPI ? ids.get(struck) : null;
        if (tid != null) {
            ShipAPI s = (ShipAPI) struck;
            f.target = tid;
            float a = (float) Math.toRadians(-s.getFacing()), dx = at.x - s.getLocation().x, dy = at.y - s.getLocation().y;
            float c = (float) Math.cos(a), sn = (float) Math.sin(a);
            f.ox = dx * c - dy * sn;
            f.oy = dx * sn + dy * c;
            ShieldAPI sh = s.getShield();
            f.shield = sh != null && sh.isOn() && sh.isWithinArc(at);
        }
        batch.add(f);
        hits++;
    }

    private void gone(Track t) {
        if (!room()) return;
        batch.add(fate(Extras.SHOT_GONE, t, t.seenAt, t.age, t.x, t.y));
        gone++;
    }

    private void turned(DamagingProjectileAPI p, Track t, Vector2f at, Vector2f v) {
        if (!room()) return;
        // The point it turned at: where it was last frame (it has moved one frame on its new course since).
        Extras.ShotFate f = fate(Extras.SHOT_TURNED, t, t.seenAt, t.age, t.x, t.y);
        f.vx = v.x;
        f.vy = v.y;
        f.facing = p.getFacing();
        f.owner = p.getOwner();
        batch.add(f);
        this.turned++;
    }
}

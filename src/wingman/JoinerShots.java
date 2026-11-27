package wingman;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

import wingman.net.Extras;

/**
 * Joiner side of SHOT_FATES (host side: HostShots): our copy of each of the host's shots is made to end, or turn,
 * as the host's did.
 *
 * Our copy is found among our shots from our copy of the same ship and weapon: the one whose path passes the
 * host's point (within 60 units, plus 2% of its speed) and which reaches it when, on our screen, the host's clock
 * says what it said then (others' shots are shown at the host's time as the freshest update tells it; our own are a
 * round trip ahead): within 0.25 s, the nearest; that tells apart a gun's shots in a row, which all fly the same
 * line at the same speed. (Before the host's clock is known here: by the age it would have there, within 0.5 s.)
 * Then:
 * - struck: if ours has already struck something it is left alone. If it is still on its way, it is steered the
 *   last few degrees at the point (on the struck ship as it stands here), so it strikes by itself and the game
 *   draws and sounds the hit; if it gets there without striking, or has already flown past, it is taken off with
 *   a flash at the point the size of the shot's own hit glow.
 * - taken off: when it gets to the point (at once if past it).
 * - turned: when it gets to the turning point, it carries on from there with the host's velocity, facing and side.
 * - burst (flak and the like): when it gets to the point, taken off, and the explosion it became shown there (the
 *   host's look and sound, doing no damage: collision class none). Not if our copy burst there by itself (an
 *   explosion of ours from that ship's same weapon within 150 units in the last 0.6 s): ours is the same burst.
 *   No copy of ours found and none burst here: shown at once.
 * A copy not yet at its point waits on a list (at most 400, 1.5 s each).
 */
final class JoinerShots {
    interface World {
        ShipAPI ship(int netId);
        CombatEngineAPI engine();
        /** The host's battle clock as shown here now (others' ships and shots are shown at it); NaN if not known yet. */
        float hostNow();
        /** The host's number for the ship we fly (-1: none) and the round trip (s): our own shots are a round trip ahead. */
        int ownId();
        float roundTrip();
    }

    static final float TIME_WINDOW = 0.25f, AGE_WINDOW = 0.5f, PATH_SLACK = 60f, WAIT = 1.5f, STEER_DEG = 8f;

    private static final class Waiting {
        DamagingProjectileAPI shot;
        Extras.ShotFate fate;
        float until;
    }

    private final World w;
    private final List<Waiting> waiting = new ArrayList<Waiting>();
    /** Copies already given a fate (so one copy isn't given two). */
    private final Map<DamagingProjectileAPI, Boolean> taken = new IdentityHashMap<DamagingProjectileAPI, Boolean>();
    private float clock, pruneAt;
    /** Explosions of ours (a copy's fuse went off here): which ship and weapon, where, when; and the ones we made. */
    private final List<float[]> ourBursts = new ArrayList<float[]>();
    private final List<Object> ourBurstFrom = new ArrayList<Object>();
    private final Map<DamagingProjectileAPI, Boolean> explosionsSeen = new IdentityHashMap<DamagingProjectileAPI, Boolean>();
    int burstsShown, burstsOurs;
    private boolean broken;
    int matched, unmatched, struckByThemselves, flashed, removed, turned;

    JoinerShots(World w) { this.w = w; }

    void reset() {
        waiting.clear();
        taken.clear();
        ourBursts.clear();
        ourBurstFrom.clear();
        explosionsSeen.clear();
    }

    void onFates(byte[] data) {
        if (broken) return;
        try {
            List<Extras.ShotFate> fates = Extras.decodeShotFates(data);
            if (fates.isEmpty()) return;
            CombatEngineAPI engine = w.engine();
            if (engine == null) return;
            noteOurBursts(engine); // (bursts of ours since the last frame, to compare with)
            // Our ordinary shots, by the ship that fired them.
            Map<ShipAPI, List<DamagingProjectileAPI>> bySource = new HashMap<ShipAPI, List<DamagingProjectileAPI>>();
            List<DamagingProjectileAPI> all = engine.getProjectiles();
            for (int i = 0, n = all.size(); i < n; i++) {
                DamagingProjectileAPI p = all.get(i);
                if (p instanceof MissileAPI || p.getSource() == null || p.didDamage() || taken.containsKey(p) || explosionsSeen.containsKey(p)) continue;
                boolean boom = false;
                try { boom = p.getExplosionSpecIfExplosion() != null; } catch (Throwable t) { /* a shot */ }
                if (boom) continue; // an explosion is no shot's copy
                List<DamagingProjectileAPI> l = bySource.get(p.getSource());
                if (l == null) bySource.put(p.getSource(), l = new ArrayList<DamagingProjectileAPI>());
                l.add(p);
            }
            for (Extras.ShotFate f : fates) {
                ShipAPI src = w.ship(f.source);
                List<DamagingProjectileAPI> mine = src == null ? null : bySource.get(src);
                DamagingProjectileAPI copy = mine == null ? null : find(mine, f);
                if (copy == null) {
                    if (f.kind == Extras.SHOT_BURST) { // ours may have burst already, or there is none
                        if (burstHere(f)) burstsOurs++;
                        else showBurst(engine, f, src);
                    }
                    unmatched++;
                    continue;
                }
                matched++;
                taken.put(copy, Boolean.TRUE);
                mine.remove(copy);
                Waiting wt = new Waiting();
                wt.shot = copy;
                wt.fate = f;
                wt.until = clock + WAIT;
                if (!settle(engine, wt, 0f) && waiting.size() < 400) waiting.add(wt);
            }
        } catch (Throwable t) {
            broken = true;
            waiting.clear();
            Global.getLogger(JoinerShots.class).error("Wingman: what became of the host's shots couldn't be used; off for this battle", t);
        }
    }

    /** Each frame: copies waiting to reach their point. */
    void tick(float amount) {
        clock += amount;
        if (broken) return;
        try {
            CombatEngineAPI engine = w.engine();
            if (engine == null) return;
            noteOurBursts(engine);
            for (Iterator<Waiting> it = waiting.iterator(); it.hasNext();) {
                Waiting wt = it.next();
                if (!engine.isEntityInPlay(wt.shot) || wt.shot.didDamage()) { // it struck (or ended) by itself
                    if (wt.fate.kind == Extras.SHOT_HIT && wt.shot.didDamage()) struckByThemselves++;
                    if (wt.fate.kind == Extras.SHOT_BURST) {
                        noteOurBursts(engine);
                        if (burstHere(wt.fate)) burstsOurs++;
                        else showBurst(engine, wt.fate, wt.shot.getSource());
                    }
                    it.remove();
                    continue;
                }
                if (clock > wt.until || settle(engine, wt, amount)) it.remove();
            }
            if (clock >= pruneAt) {
                pruneAt = clock + 1f;
                for (Iterator<DamagingProjectileAPI> it = taken.keySet().iterator(); it.hasNext();) if (!engine.isEntityInPlay(it.next())) it.remove();
            }
        } catch (Throwable t) {
            broken = true;
            waiting.clear();
            Global.getLogger(JoinerShots.class).error("Wingman: following the host's shots failed; off for this battle", t);
        }
    }

    // ---- finding our copy ----

    /** Where the fate happens on our screen: on a struck ship, its point there as the ship stands here now. */
    private Vector2f pointOf(Extras.ShotFate f) {
        if (f.kind == Extras.SHOT_HIT && f.target >= 0) {
            ShipAPI t = w.ship(f.target);
            if (t != null) {
                float a = (float) Math.toRadians(t.getFacing()), c = (float) Math.cos(a), s = (float) Math.sin(a);
                return new Vector2f(t.getLocation().x + f.ox * c - f.oy * s, t.getLocation().y + f.ox * s + f.oy * c);
            }
        }
        return new Vector2f(f.x, f.y);
    }

    private DamagingProjectileAPI find(List<DamagingProjectileAPI> mine, Extras.ShotFate f) {
        Vector2f at = pointOf(f);
        float hostNow = w.hostNow(), ahead0 = f.source == w.ownId() ? w.roundTrip() : 0f;
        DamagingProjectileAPI best = null;
        float bestScore = Float.MAX_VALUE;
        for (DamagingProjectileAPI p : mine) {
            WeaponAPI gun = p.getWeapon();
            if (gun == null || gun.getId() == null || gun.getId().hashCode() != f.weapon) continue;
            Vector2f v = p.getVelocity();
            float speed = (float) Math.sqrt(v.x * v.x + v.y * v.y);
            float dx = at.x - p.getLocation().x, dy = at.y - p.getLocation().y;
            float ahead, off;
            if (speed < 1f) { ahead = 0f; off = (float) Math.sqrt(dx * dx + dy * dy); }
            else { ahead = (dx * v.x + dy * v.y) / speed; off = Math.abs(dx * v.y - dy * v.x) / speed; }
            if (off > PATH_SLACK + speed * 0.02f) continue;
            float flyOn = speed < 1f ? 0f : ahead / speed;
            float ageOff = Math.abs(p.getElapsed() + flyOn - f.age);
            if (ageOff > AGE_WINDOW) continue;
            float late;
            if (hostNow == hostNow) {
                late = Math.abs(hostNow + flyOn + ahead0 - f.at);
                if (late > TIME_WINDOW) continue;
            } else late = ageOff;
            float score = late + off / 1000f;
            if (score < bestScore) { bestScore = score; best = p; }
        }
        return best;
    }

    // ---- making it so ----

    /** Carry out the fate if our copy is at (or past) its point; true when done. */
    private boolean settle(CombatEngineAPI engine, Waiting wt, float amount) {
        DamagingProjectileAPI p = wt.shot;
        Extras.ShotFate f = wt.fate;
        Vector2f at = pointOf(f);
        Vector2f v = p.getVelocity();
        float speed = (float) Math.sqrt(v.x * v.x + v.y * v.y);
        float dx = at.x - p.getLocation().x, dy = at.y - p.getLocation().y;
        float ahead = speed < 1f ? 0f : (dx * v.x + dy * v.y) / speed;
        float step = Math.max(speed * Math.max(amount, 1f / 60f) * 1.5f, 6f);
        boolean there = ahead <= step;
        switch (f.kind) {
            case Extras.SHOT_HIT: {
                if (!there) {
                    // Still on its way: aimed the last few degrees at the point, so it strikes by itself.
                    float want = (float) Math.toDegrees(Math.atan2(dy, dx)), now = (float) Math.toDegrees(Math.atan2(v.y, v.x));
                    float turn = ((want - now) % 360f + 540f) % 360f - 180f;
                    if (Math.abs(turn) > 0.2f && Math.abs(turn) <= STEER_DEG) {
                        float r = (float) Math.toRadians(want);
                        v.set((float) Math.cos(r) * speed, (float) Math.sin(r) * speed);
                        p.setFacing(p.getFacing() + turn);
                    }
                    return false;
                }
                flash(engine, p, at, f);
                engine.removeEntity(p);
                flashed++;
                return true;
            }
            case Extras.SHOT_GONE:
                if (!there) return false;
                engine.removeEntity(p);
                removed++;
                return true;
            case Extras.SHOT_BURST:
                if (!there) return false;
                engine.removeEntity(p);
                showBurst(engine, f, p.getSource());
                return true;
            case Extras.SHOT_TURNED: {
                if (!there) return false;
                // From the turning point, on the new course, as far as it has come past it.
                float past = Math.max(0f, -ahead), t = speed < 1f ? 0f : past / speed;
                p.getLocation().set(at.x + f.vx * t, at.y + f.vy * t);
                v.set(f.vx, f.vy);
                p.setFacing(f.facing);
                if (p.getOwner() != f.owner) p.setOwner(f.owner);
                turned++;
                return true;
            }
            default:
                return true;
        }
    }

    /** A hit our copy didn't make by itself: a flash at the point, the size of the shot's own hit glow. */
    private void flash(CombatEngineAPI engine, DamagingProjectileAPI p, Vector2f at, Extras.ShotFate f) {
        ShipAPI struck = f.target >= 0 ? w.ship(f.target) : null;
        Vector2f vel = struck == null ? new Vector2f() : new Vector2f(struck.getVelocity()); // moving with what it struck
        Color c = null;
        float size = 30f;
        try {
            com.fs.starfarer.api.loading.ProjectileSpecAPI spec = p.getProjectileSpec();
            if (spec != null) {
                c = spec.getFringeColor() != null ? spec.getFringeColor() : spec.getGlowColor();
                if (spec.getHitGlowRadius() > 0f) size = spec.getHitGlowRadius();
            }
        } catch (Throwable t) { /* plain */ }
        if (c == null) c = new Color(255, 200, 150, 255);
        engine.addHitParticle(at, vel, size, 1f, 0.12f, c);
        engine.addHitParticle(at, vel, size * 0.5f, 1f, 0.08f, Color.WHITE);
    }

    // ---- bursts ----

    /** Explosions our own copies' fuses made since last looked (not ones we showed for the host). */
    private void noteOurBursts(CombatEngineAPI engine) {
        List<DamagingProjectileAPI> all = engine.getProjectiles();
        for (int i = 0, n = all.size(); i < n; i++) {
            DamagingProjectileAPI p = all.get(i);
            if (p instanceof MissileAPI || explosionsSeen.containsKey(p)) continue;
            boolean boom = false;
            try { boom = p.getExplosionSpecIfExplosion() != null; } catch (Throwable t) { /* not one */ }
            if (!boom) continue;
            explosionsSeen.put(p, Boolean.TRUE);
            WeaponAPI gun = p.getWeapon();
            ourBursts.add(new float[] { gun == null || gun.getId() == null ? 0 : gun.getId().hashCode(), p.getLocation().x, p.getLocation().y, clock });
            ourBurstFrom.add(p.getSource());
        }
        for (int i = ourBursts.size() - 1; i >= 0; i--) {
            if (clock - ourBursts.get(i)[3] > 0.6f) { ourBursts.remove(i); ourBurstFrom.remove(i); }
        }
        if (explosionsSeen.size() > 300) {
            for (Iterator<DamagingProjectileAPI> it = explosionsSeen.keySet().iterator(); it.hasNext();) if (!engine.isEntityInPlay(it.next())) it.remove();
        }
    }

    /** One of ours is this burst (and is used up); see the class comment. */
    private boolean burstHere(Extras.ShotFate f) {
        ShipAPI src = w.ship(f.source);
        for (int i = 0; i < ourBursts.size(); i++) {
            float[] b = ourBursts.get(i);
            if (ourBurstFrom.get(i) != src || (b[0] != 0f && (int) b[0] != f.weapon)) continue;
            float dx = b[1] - f.x, dy = b[2] - f.y;
            if (dx * dx + dy * dy > 150f * 150f) continue;
            ourBursts.remove(i);
            ourBurstFrom.remove(i);
            return true;
        }
        return false;
    }

    /** The host's burst, shown here: its look and sound, no damage (it collides with nothing). */
    private void showBurst(CombatEngineAPI engine, Extras.ShotFate f, ShipAPI source) {
        com.fs.starfarer.api.loading.DamagingExplosionSpec spec = new com.fs.starfarer.api.loading.DamagingExplosionSpec(
                Math.max(0.05f, f.exDuration), f.exRadius, f.exCore, 0f, 0f,
                com.fs.starfarer.api.combat.CollisionClass.NONE, com.fs.starfarer.api.combat.CollisionClass.NONE,
                f.pSizeMin, f.pSizeRange, f.pDuration, f.pCount, new Color(f.pColor, true), new Color(f.exColor, true));
        if (f.sound != null && !f.sound.isEmpty()) spec.setSoundSetId(f.sound);
        spec.setSoundVolume(f.soundVolume);
        spec.setShowGraphic(f.showGraphic);
        spec.setParticleSpawnRadius(f.exSpawnRadius);
        if (f.detailed) {
            spec.setUseDetailedExplosion(true);
            spec.setDetailedExplosionRadius(f.detRadius);
            spec.setDetailedExplosionFlashRadius(f.detFlashRadius);
            spec.setDetailedExplosionFlashDuration(f.detFlashDuration);
            spec.setDetailedExplosionFlashColorFringe(new Color(f.detFringe, true));
            spec.setDetailedExplosionFlashColorCore(new Color(f.detCore, true));
        }
        DamagingProjectileAPI made = engine.spawnDamagingExplosion(spec, source, new Vector2f(f.x, f.y));
        if (made != null) explosionsSeen.put(made, Boolean.TRUE); // not "ours" for matching
        burstsShown++;
    }
}

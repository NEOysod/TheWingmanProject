package wingman;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;

import wingman.net.Extras;

/**
 * Joiner side of VISUALS (host side: HostVisuals): the host's effects drawn by mods' ship AIs and damage listeners.
 *
 * An AI's are drawn as they come (AI doesn't run here). A damage listener's may also be drawn here by our own copy
 * of that listener (one that draws whatever the damage, on a hit our copy saw), so each is first matched with ours:
 * the same sort of effect from the same class within 250 units and 0.6 s. A host one waits up to 0.35 s for ours;
 * matched, it is dropped (ours is the same effect), else drawn. Our own are learned of through the agent too.
 *
 * EMP arcs: while the host says it sends every arc (VIS_ALL_ARCS, said at least every 2 s; 5 s without, and ours are
 * back), ours are drawn see-through and silent (the agent's hideArcs) and the host's drawn instead, as they come,
 * so an arc a script aims at a random ship lands where it did on the host. Arcs of the ship we fly stay ours (they
 * show at once, and the host's of them are passed over).
 */
final class JoinerVisuals {
    interface World {
        ShipAPI ship(int netId);
        CombatEngineAPI engine();
        int ownId();
    }

    static final float MATCH_RANGE = 250f, MATCH_TIME = 0.6f, WAIT = 0.35f;

    private static final class Local {
        int kind, cls;
        float x, y, at;
        boolean used;
    }

    private static final class Pending {
        Extras.Visual v;
        float until;
    }

    private final World w;
    private final List<Local> ours = new ArrayList<Local>();
    private final List<Pending> pending = new ArrayList<Pending>();
    private float clock, hostArcsAt = -100f;
    private boolean broken, hiding;
    int drawn, matched, arcsFromHost;

    JoinerVisuals(World w) { this.w = w; }

    void reset() {
        ours.clear();
        pending.clear();
    }

    /** The battle is over here: our arcs drawn as ever again. */
    void stop() {
        if (hiding) AgentGate.arcs(false, false);
        hiding = false;
    }

    private boolean hostArcs() { return clock - hostArcsAt < 5f; }

    void onVisuals(byte[] data) {
        if (broken) return;
        try {
            int[] flags = new int[1];
            List<Extras.Visual> list = Extras.decodeVisuals(data, flags);
            if ((flags[0] & Extras.VIS_ALL_ARCS) != 0) hostArcsAt = clock;
            for (Extras.Visual v : list) {
                if (v.kind == Extras.FX_ARC && hostArcs()) {
                    int own = w.ownId();
                    if (own >= 0 && (v.src == own || (v.src < 0 && v.aShip == own))) continue; // ours, drawn here already
                    draw(v);
                    arcsFromHost++;
                    continue;
                }
                if (v.frame != Extras.FX_DAMAGE) { draw(v); continue; }
                if (consume(v)) { matched++; continue; }
                if (pending.size() >= 300) { draw(v); continue; }
                Pending p = new Pending();
                p.v = v;
                p.until = clock + WAIT;
                pending.add(p);
            }
        } catch (Throwable t) {
            broken = true;
            pending.clear();
            Global.getLogger(JoinerVisuals.class).error("Wingman: effects from the host couldn't be drawn; off for this battle", t);
        }
    }

    /** Each frame: our own damage-listener effects noted, waiting host ones matched or drawn. */
    void tick(float amount) {
        clock += amount;
        if (broken) return;
        boolean want = hostArcs();
        if (want != hiding) hiding = AgentGate.arcs(false, want) && want;
        try {
            for (Object[] n : AgentGate.takeVisuals()) {
                if (!(n[1] instanceof Integer) || (Integer) n[1] != Extras.FX_DAMAGE) continue;
                float[] nums = (float[]) n[3];
                if (nums == null || nums.length < 2) continue;
                Local l = new Local();
                String k = (String) n[0];
                l.kind = "arc".equals(k) ? Extras.FX_ARC : "explosion".equals(k) ? Extras.FX_EXPLOSION : "hit".equals(k) ? Extras.FX_HIT : Extras.FX_SMOOTH;
                l.cls = n[2] == null ? 0 : n[2].hashCode();
                l.x = nums[0];
                l.y = nums[1];
                l.at = clock;
                if (ours.size() < 600) ours.add(l);
            }
            for (Iterator<Pending> it = pending.iterator(); it.hasNext();) {
                Pending p = it.next();
                if (consume(p.v)) { matched++; it.remove(); continue; }
                if (clock >= p.until) { draw(p.v); it.remove(); }
            }
            for (Iterator<Local> it = ours.iterator(); it.hasNext();) {
                Local l = it.next();
                if (l.used || clock - l.at > MATCH_TIME) it.remove();
            }
        } catch (Throwable t) {
            broken = true;
            pending.clear();
            Global.getLogger(JoinerVisuals.class).error("Wingman: effects from the host couldn't be drawn; off for this battle", t);
        }
    }

    /** One of ours that is this effect (and isn't another's already); true if found (it is then used up). */
    private boolean consume(Extras.Visual v) {
        if (ours.isEmpty()) return false;
        Vector2f at = point(v, true);
        for (Local l : ours) {
            if (l.used || l.kind != v.kind || l.cls != v.cls || Math.abs(clock - l.at) > MATCH_TIME) continue;
            float dx = l.x - at.x, dy = l.y - at.y;
            if (dx * dx + dy * dy > MATCH_RANGE * MATCH_RANGE) continue;
            l.used = true;
            return true;
        }
        return false;
    }

    /** A point of the effect where it lands here: on its ship as that ship stands here, or as sent. */
    private Vector2f point(Extras.Visual v, boolean first) {
        int id = first ? v.aShip : v.bShip;
        float x = first ? v.ax : v.bx, y = first ? v.ay : v.by;
        if (id < 0) return new Vector2f(x, y);
        ShipAPI s = w.ship(id);
        if (s == null) return null;
        float a = (float) Math.toRadians(s.getFacing()), c = (float) Math.cos(a), sn = (float) Math.sin(a);
        return new Vector2f(s.getLocation().x + x * c - y * sn, s.getLocation().y + x * sn + y * c);
    }

    private void draw(Extras.Visual v) {
        CombatEngineAPI engine = w.engine();
        if (engine == null) return;
        Vector2f a = point(v, true);
        if (a == null) return; // its ship isn't here
        Color c1 = new Color(v.c1, true);
        switch (v.kind) {
            case Extras.FX_ARC: {
                Vector2f b = point(v, false);
                if (b == null) return;
                ShipAPI from = v.aShip >= 0 ? w.ship(v.aShip) : null, to = v.bShip >= 0 ? w.ship(v.bShip) : null;
                engine.spawnEmpArcVisual(a, from, b, to, v.thickness, c1, new Color(v.c2, true));
                if (v.sound != null && !v.sound.isEmpty()) {
                    try { Global.getSoundPlayer().playSound(v.sound, 1f, 1f, b, to == null ? new Vector2f() : to.getVelocity()); }
                    catch (Throwable t) { /* a sound this game hasn't: silent */ }
                }
                break;
            }
            case Extras.FX_EXPLOSION:
                engine.spawnExplosion(a, new Vector2f(v.vx, v.vy), c1, v.size, Math.max(0.05f, v.duration));
                break;
            case Extras.FX_HIT:
                if (v.duration < 0f) engine.addHitParticle(a, new Vector2f(v.vx, v.vy), v.size, v.brightness, c1);
                else if (v.ramp >= 0f) engine.addHitParticle(a, new Vector2f(v.vx, v.vy), v.size, v.brightness, v.ramp, v.duration, c1);
                else engine.addHitParticle(a, new Vector2f(v.vx, v.vy), v.size, v.brightness, v.duration, c1);
                break;
            case Extras.FX_SMOOTH:
                if (v.ramp >= 0f) engine.addSmoothParticle(a, new Vector2f(v.vx, v.vy), v.size, v.brightness, v.ramp, Math.max(0.05f, v.duration), c1);
                else engine.addSmoothParticle(a, new Vector2f(v.vx, v.vy), v.size, v.brightness, Math.max(0.05f, v.duration), c1);
                break;
            default:
                return;
        }
        drawn++;
    }
}

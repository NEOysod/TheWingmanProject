package wingman;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAPI;

import wingman.net.Extras;
import wingman.net.HostSession;
import wingman.net.Msg;

/**
 * Host side of VISUALS (joiner side: JoinerVisuals): effects mods' code draws where a joiner's copy of the battle
 * won't draw them itself.
 *
 * With the agent running, mods' code that draws an EMP arc, an explosion or a hit or smooth particle inside a ship
 * AI's or a ship system AI's frame (AI runs only on the host: a joiner's copies have one that does nothing) or a
 * damage listener's (on a joiner's copy, which takes no damage there, a listener hears of next to none, so effects
 * that depend on the damage done never come) is noted by the agent (Hooks.empArc and the rest). Each frame those
 * are taken here and sent, each point held to a ship where there is one: an arc's ends to the entities it was
 * fixed to, other points to the nearest ship within 400 units (the point in that ship's own frame), so they land on
 * the ship as it stands on the joiner's screen. At most 300 a second; droppable (a late effect is no use).
 *
 * EMP arcs from anywhere: scripts often aim an arc at a random ship or point, so each PC's landed elsewhere. Every
 * arc a script draws on the host is noted (the agent's noteAllArcs), sent (at most 500 a second, apart from the
 * rest), and every message says so (VIS_ALL_ARCS; an empty one every 2 s): a joiner then draws its own arcs
 * see-through and silent and the host's instead, except arcs of the ship it flies, which stay its own.
 */
final class HostVisuals {
    static final int MAX_PER_SECOND = 300, MAX_ARCS_PER_SECOND = 500;
    static final float HOLD_RANGE = 400f;

    private final Map<ShipAPI, Integer> ids;
    private final WingmanConfig cfg;
    private boolean broken, allArcs;
    private long second, toldAt;
    private int thisSecond, arcsThisSecond;
    int sent, capped;

    HostVisuals(Map<ShipAPI, Integer> ids, WingmanConfig cfg) {
        this.ids = ids;
        this.cfg = cfg;
    }

    void tick(HostSession session, Map<Integer, ShipAPI> byId) {
        if (broken) return;
        if (!allArcs && cfg.damageReports && AgentGate.linkedNow()) allArcs = AgentGate.arcs(true, false);
        List<Object[]> noted = AgentGate.takeVisuals(); // taken every frame even when unsent, so they don't pile up
        if (!cfg.damageReports || session.getPlayers().isEmpty()) return;
        try {
            long now = System.currentTimeMillis();
            if (now - second >= 1000) { second = now; thisSecond = 0; arcsThisSecond = 0; }
            List<Extras.Visual> out = new ArrayList<Extras.Visual>();
            for (Object[] n : noted) {
                boolean arc = "arc".equals(n[0]);
                if (arc ? arcsThisSecond >= MAX_ARCS_PER_SECOND : thisSecond >= MAX_PER_SECOND) { capped++; continue; }
                Extras.Visual v = visual(n, byId);
                if (v == null) continue;
                out.add(v);
                if (arc) arcsThisSecond++; else thisSecond++;
            }
            int flags = allArcs ? Extras.VIS_ALL_ARCS : 0;
            if (out.isEmpty() && !(allArcs && now - toldAt >= 2000)) return; // (the flag is said again every 2 s)
            toldAt = now;
            session.broadcastDroppable(Msg.VISUALS, Extras.encodeVisuals(out, flags));
            sent += out.size();
        } catch (Throwable t) {
            broken = true;
            Global.getLogger(HostVisuals.class).error("Wingman: sending effects drawn by AIs and damage listeners failed; off for this battle", t);
        }
    }

    /** One noted effect (see Shared.visuals) as a Visual, or null if it can't be. */
    private Extras.Visual visual(Object[] n, Map<Integer, ShipAPI> byId) {
        String kind = (String) n[0];
        float[] nums = (float[]) n[3];
        Extras.Visual v = new Extras.Visual();
        v.kind = "arc".equals(kind) ? Extras.FX_ARC : "explosion".equals(kind) ? Extras.FX_EXPLOSION
                : "hit".equals(kind) ? Extras.FX_HIT : "smooth".equals(kind) ? Extras.FX_SMOOTH : 0;
        if (v.kind == 0 || nums == null) return null;
        v.frame = n[1] instanceof Integer ? (Integer) n[1] : 0;
        v.cls = n[2] == null ? 0 : n[2].hashCode();
        v.c1 = n[6] instanceof Color ? ((Color) n[6]).getRGB() : Color.WHITE.getRGB();
        if (v.kind == Extras.FX_ARC) {
            if (nums.length < 5) return null;
            hold(v, true, nums[0], nums[1], n[4], byId);
            hold(v, false, nums[2], nums[3], n[5], byId);
            v.thickness = nums[4];
            v.c2 = n[7] instanceof Color ? ((Color) n[7]).getRGB() : Color.WHITE.getRGB();
            v.sound = n[8] instanceof String ? (String) n[8] : "";
            Integer src = n.length > 9 && n[9] instanceof ShipAPI ? ids.get(n[9]) : null;
            v.src = src == null ? -1 : src;
        } else {
            if (nums.length < 8) return null;
            hold(v, true, nums[0], nums[1], null, byId);
            v.vx = nums[2];
            v.vy = nums[3];
            v.size = nums[4];
            v.duration = nums[5];
            v.ramp = nums[6];
            v.brightness = nums[7];
        }
        return v;
    }

    /** A point, held to the given entity if it is one of our ships, else to the nearest ship in range, else as it is. */
    private void hold(Extras.Visual v, boolean first, float x, float y, Object anchor, Map<Integer, ShipAPI> byId) {
        ShipAPI ship = anchor instanceof ShipAPI && ids.containsKey(anchor) ? (ShipAPI) anchor : null;
        if (ship == null) {
            float best = HOLD_RANGE * HOLD_RANGE;
            for (ShipAPI s : byId.values()) {
                if (s == null || s.isHulk()) continue;
                float dx = s.getLocation().x - x, dy = s.getLocation().y - y, d = dx * dx + dy * dy - s.getCollisionRadius() * s.getCollisionRadius();
                if (d < best) { best = d; ship = s; }
            }
        }
        Integer id = ship == null ? null : ids.get(ship);
        if (id == null) {
            if (first) { v.aShip = -1; v.ax = x; v.ay = y; } else { v.bShip = -1; v.bx = x; v.by = y; }
            return;
        }
        float a = (float) Math.toRadians(-ship.getFacing()), c = (float) Math.cos(a), s = (float) Math.sin(a);
        float dx = x - ship.getLocation().x, dy = y - ship.getLocation().y;
        float ox = dx * c - dy * s, oy = dx * s + dy * c;
        if (first) { v.aShip = id; v.ax = ox; v.ay = oy; } else { v.bShip = id; v.bx = ox; v.by = oy; }
    }
}

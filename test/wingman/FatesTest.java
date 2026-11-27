package wingman;

import java.awt.Color;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

import wingman.net.Extras;

/**
 * The last channels of protocol 65: peak time and carrier rates, what became of the host's shots (and finding our
 * own copy of a shot among a gun's shots in a row), and effects drawn by the host's AIs and damage listeners.
 */
public class FatesTest {
    static int fails, checks;
    static void check(boolean ok, String what) { checks++; System.out.println((ok ? "ok   " : "FAIL ") + what); if (!ok) fails++; }

    // ---- a small battle: ships, shots, an engine ----

    static final List<Object> field = new ArrayList<Object>();
    static int particles, bursts;
    static com.fs.starfarer.api.loading.DamagingExplosionSpec lastBurst;

    static ShipAPI ship(final float x, final float y, final float facing) {
        final Vector2f at = new Vector2f(x, y), vel = new Vector2f();
        return (ShipAPI) Proxy.newProxyInstance(FatesTest.class.getClassLoader(), new Class<?>[] { ShipAPI.class }, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                String n = m.getName();
                if (n.equals("getLocation")) return at;
                if (n.equals("getVelocity")) return vel;
                if (n.equals("getFacing")) return facing;
                if (n.equals("hashCode")) return System.identityHashCode(p);
                if (n.equals("equals")) return p == a[0];
                return null;
            }
        });
    }

    static final class Shot {
        final Vector2f loc, vel;
        float elapsed, facing;
        int owner;
        boolean boom; // an explosion (a fuse went off)
        boolean struck;
        ShipAPI struckShip;
        DamagingProjectileAPI api;
        Shot(float x, float y, float vx, float vy, float elapsed) { loc = new Vector2f(x, y); vel = new Vector2f(vx, vy); this.elapsed = elapsed; }
    }

    static DamagingProjectileAPI shot(final Shot s, final ShipAPI source, final String gunId) {
        final WeaponAPI gun = (WeaponAPI) Proxy.newProxyInstance(FatesTest.class.getClassLoader(), new Class<?>[] { WeaponAPI.class }, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                if (m.getName().equals("getRange")) return 1000f;
                return m.getName().equals("getId") ? gunId : null;
            }
        });
        s.api = (DamagingProjectileAPI) Proxy.newProxyInstance(FatesTest.class.getClassLoader(), new Class<?>[] { DamagingProjectileAPI.class }, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                String n = m.getName();
                if (n.equals("getLocation")) return s.loc;
                if (n.equals("getVelocity")) return s.vel;
                if (n.equals("getElapsed")) return s.elapsed;
                if (n.equals("getSource")) return source;
                if (n.equals("getWeapon")) return gun;
                if (n.equals("didDamage")) return s.struck;
                if (n.equals("getDamageTarget")) return s.struckShip;
                if (n.equals("getProjectileSpecId")) return "test_shot";
                if (n.equals("getMoveSpeed")) return s.vel.length();
                if (n.equals("isFading")) return false;
                if (n.equals("getOwner")) return s.owner;
                if (n.equals("setOwner")) { s.owner = (Integer) a[0]; return null; }
                if (n.equals("getFacing")) return s.facing;
                if (n.equals("getExplosionSpecIfExplosion")) return s.boom ? new com.fs.starfarer.api.loading.DamagingExplosionSpec(0.1f, 50f, 35f, 0f, 0f,
                        com.fs.starfarer.api.combat.CollisionClass.NONE, com.fs.starfarer.api.combat.CollisionClass.NONE, 3f, 3f, 1f, 100, Color.RED, Color.RED) : null;
                if (n.equals("setFacing")) { s.facing = (Float) a[0]; return null; }
                if (n.equals("hashCode")) return System.identityHashCode(p);
                if (n.equals("equals")) return p == a[0];
                return null;
            }
        });
        field.add(s.api);
        return s.api;
    }

    static final CombatEngineAPI engine = (CombatEngineAPI) Proxy.newProxyInstance(FatesTest.class.getClassLoader(), new Class<?>[] { CombatEngineAPI.class }, new InvocationHandler() {
        public Object invoke(Object p, Method m, Object[] a) {
            String n = m.getName();
            if (n.equals("getProjectiles")) { List<DamagingProjectileAPI> l = new ArrayList<DamagingProjectileAPI>(); for (Object o : field) l.add((DamagingProjectileAPI) o); return l; }
            if (n.equals("isEntityInPlay")) return field.contains(a[0]);
            if (n.equals("removeEntity")) { field.remove(a[0]); return null; }
            if (n.equals("addHitParticle")) { particles++; return null; }
            if (n.equals("spawnDamagingExplosion")) { bursts++; lastBurst = (com.fs.starfarer.api.loading.DamagingExplosionSpec) a[0]; return null; }
            return null;
        }
    });

    static float hostNow = 10f;
    static int own = -1;
    static final Map<Integer, ShipAPI> ships = new HashMap<Integer, ShipAPI>();

    static JoinerShots follower() {
        return new JoinerShots(new JoinerShots.World() {
            public ShipAPI ship(int netId) { return ships.get(netId); }
            public CombatEngineAPI engine() { return engine; }
            public float hostNow() { return hostNow; }
            public int ownId() { return own; }
            public float roundTrip() { return 0.2f; }
        });
    }

    static Extras.ShotFate fate(int kind, int source, String gun, float at, float age, float x, float y) {
        Extras.ShotFate f = new Extras.ShotFate();
        f.kind = kind; f.source = source; f.weapon = gun.hashCode(); f.at = at; f.age = age; f.x = x; f.y = y;
        return f;
    }

    public static void main(String[] args) throws Exception {
        // ---- codecs ----
        Extras.Clock c = new Extras.Clock();
        c.ship = 9; c.deployed = 312.5f; c.bayRates = new float[] { 0.72f, 1f };
        Extras.Clock cb = Extras.decodeClocks(Extras.encodeClocks(Arrays.asList(c))).get(0);
        check(cb.ship == 9 && cb.deployed == 312.5f && cb.bayRates.length == 2 && cb.bayRates[0] == 0.72f, "a ship's time deployed and bay rates arrive as sent");

        Extras.ShotFate hit = fate(Extras.SHOT_HIT, 5, "gun", 9.95f, 0.5f, 500f, 0f);
        hit.target = 12; hit.ox = 30f; hit.oy = -4f; hit.shield = true;
        Extras.ShotFate gone = fate(Extras.SHOT_GONE, 5, "gun", 9f, 0.3f, 1f, 2f);
        Extras.ShotFate turn = fate(Extras.SHOT_TURNED, 6, "laser", 11f, 0.25f, 300f, 0f);
        turn.vx = -900f; turn.vy = 100f; turn.facing = 173.6f; turn.owner = 1;
        List<Extras.ShotFate> fb = Extras.decodeShotFates(Extras.encodeShotFates(Arrays.asList(hit, gone, turn)));
        check(fb.size() == 3 && fb.get(0).target == 12 && fb.get(0).ox == 30f && fb.get(0).shield && fb.get(0).at == 9.95f
                && fb.get(1).kind == Extras.SHOT_GONE && fb.get(2).vx == -900f && fb.get(2).owner == 1 && fb.get(2).weapon == "laser".hashCode(),
                "shot fates (struck, taken off, turned) arrive as sent");

        Extras.Visual arc = new Extras.Visual();
        arc.kind = Extras.FX_ARC; arc.frame = Extras.FX_DAMAGE; arc.cls = "mod.Listener".hashCode(); arc.aShip = 3; arc.ax = 10f; arc.ay = 5f;
        arc.bShip = -1; arc.bx = 900f; arc.by = -50f; arc.thickness = 12f; arc.c1 = Color.CYAN.getRGB(); arc.c2 = Color.WHITE.getRGB(); arc.sound = "tachyon_lance_emp_impact";
        Extras.Visual boom = new Extras.Visual();
        boom.kind = Extras.FX_EXPLOSION; boom.frame = Extras.FX_AI; boom.ax = 1f; boom.ay = 2f; boom.vx = 3f; boom.size = 80f; boom.duration = 0.5f; boom.c1 = Color.ORANGE.getRGB();
        Extras.Visual spark = new Extras.Visual();
        spark.kind = Extras.FX_HIT; spark.frame = Extras.FX_DAMAGE; spark.size = 40f; spark.duration = -1f; spark.brightness = 0.8f; spark.c1 = Color.RED.getRGB();
        List<Extras.Visual> vb = Extras.decodeVisuals(Extras.encodeVisuals(Arrays.asList(arc, boom, spark)));
        check(vb.size() == 3 && vb.get(0).aShip == 3 && vb.get(0).bx == 900f && vb.get(0).thickness == 12f && vb.get(0).sound.equals("tachyon_lance_emp_impact")
                && vb.get(0).c1 == Color.CYAN.getRGB() && vb.get(1).size == 80f && vb.get(1).frame == Extras.FX_AI && vb.get(2).duration == -1f && vb.get(2).brightness == 0.8f,
                "effects (an arc with its sound, an explosion, a short particle) arrive as sent");

        // ---- finding our copy among a gun's shots in a row ----
        // A gun at the origin firing along +x at 1000 units/s, a shot every 0.1 s. All its shots fly the same line at
        // the same speed, so only the time tells them apart. The host's shot struck at x = 500, host clock 9.95; our
        // screen shows the host's clock at 10.00, so our copy of it is 50 units past.
        ShipAPI gunShip = ship(0f, 0f, 0f);
        ships.put(5, gunShip);
        Shot[] row = new Shot[5];
        float[] ages = { 0.35f, 0.45f, 0.55f, 0.65f, 0.75f };
        for (int i = 0; i < row.length; i++) { row[i] = new Shot(1000f * ages[i], 0f, 1000f, 0f, ages[i]); shot(row[i], gunShip, "gun"); }
        JoinerShots js = follower();
        js.onFates(Extras.encodeShotFates(Arrays.asList(fate(Extras.SHOT_HIT, 5, "gun", 9.95f, 0.5f, 500f, 0f))));
        check(js.matched == 1 && !field.contains(row[2].api) && field.contains(row[1].api) && field.contains(row[3].api) && particles == 2,
                "of five shots in a row, the one the host's hit was is found (50 units past the point), taken off with a flash");
        // a fate for a weapon of another kind on the same ship finds nothing
        js.onFates(Extras.encodeShotFates(Arrays.asList(fate(Extras.SHOT_HIT, 5, "other_gun", 9.95f, 0.5f, 500f, 0f))));
        check(js.unmatched == 1 && field.size() == 4, "a fate for another weapon's shot finds none of these");
        // a shot taken off by a script on the host ahead of where ours is now: ours goes when it gets there
        js.onFates(Extras.encodeShotFates(Arrays.asList(fate(Extras.SHOT_GONE, 5, "gun", 10.15f, 0.6f, 600f, 0f)))); // the 0.45 s one, fired at 9.55
        check(js.matched == 2 && field.contains(row[1].api), "a shot taken off ahead of ours: ours waits to get there (0.45 s old, at 450)");
        row[1].loc.x = 595f;
        js.tick(0.016f);
        check(!field.contains(row[1].api) && js.removed == 1, "... and is taken off on getting there");
        // turned (reflected) ahead of ours: on getting there, it carries on along the new course from that point
        Shot r = new Shot(200f, 0f, 1000f, 0f, 0.2f);
        shot(r, gunShip, "gun");
        Extras.ShotFate back = fate(Extras.SHOT_TURNED, 5, "gun", 10.1f, 0.3f, 300f, 0f);
        back.vx = -1000f; back.vy = 0f; back.facing = 180f; back.owner = 1;
        js.onFates(Extras.encodeShotFates(Arrays.asList(back)));
        r.loc.x = 305f;
        js.tick(0.016f);
        check(js.turned == 1 && r.vel.x == -1000f && Math.abs(r.loc.x - 295f) < 0.01f && r.owner == 1 && r.facing == 180f,
                "a reflected shot: from the turning point, back the way it came, on the other side (x " + r.loc.x + ")");
        // our own shots are a round trip ahead: the host's event at 10.2 is ours at 10.0
        field.clear();
        own = 5;
        Shot mine1 = new Shot(450f, 0f, 1000f, 0f, 0.45f), mine2 = new Shot(350f, 0f, 1000f, 0f, 0.35f);
        shot(mine1, gunShip, "gun");
        shot(mine2, gunShip, "gun");
        js.onFates(Extras.encodeShotFates(Arrays.asList(fate(Extras.SHOT_HIT, 5, "gun", 10.2f, 0.5f, 500f, 0f))));
        // mine1 reaches 500 in 0.05 s (host clock 10.05 + round trip 0.2 = 10.25), mine2 in 0.15 s (10.35): mine1 is it
        Shot m1 = mine1;
        m1.loc.x = 499f; // getting there this frame
        js.tick(0.016f);
        check(!field.contains(mine1.api) && field.contains(mine2.api), "our own shot is matched a round trip ahead (the nearer of two in a row)");
        own = -1;

        // ---- flak: a shell whose fuse went off on the host ----
        Extras.ShotFate fb1 = fate(Extras.SHOT_BURST, 5, "flak", 10.05f, 0.3f, 300f, 0f);
        fb1.exDuration = 0.1f; fb1.exRadius = 50f; fb1.exCore = 35f; fb1.pSizeMin = 3f; fb1.pSizeRange = 3f; fb1.pDuration = 1f; fb1.pCount = 100;
        fb1.pColor = new Color(255, 155, 155).getRGB(); fb1.exColor = Color.RED.getRGB(); fb1.sound = "explosion_flak"; fb1.soundVolume = 0.8f;
        Extras.ShotFate fbb = Extras.decodeShotFates(Extras.encodeShotFates(Arrays.asList(fb1))).get(0);
        check(fbb.kind == Extras.SHOT_BURST && fbb.exRadius == 50f && fbb.pCount == 100 && fbb.sound.equals("explosion_flak") && fbb.soundVolume == 0.8f
                && fbb.showGraphic && !fbb.detailed, "a burst arrives with the explosion's look and sound");
        field.clear();
        Shot shell = new Shot(250f, 0f, 1000f, 0f, 0.25f); // ours: 50 units short of where the host's went off
        shot(shell, gunShip, "flak");
        js.onFates(Extras.encodeShotFates(Arrays.asList(fb1)));
        check(field.contains(shell.api) && bursts == 0, "our shell is found and goes on to the point");
        shell.loc.x = 299f;
        js.tick(0.016f);
        check(!field.contains(shell.api) && bursts == 1 && lastBurst.getMaxDamage() == 0f
                && lastBurst.getCollisionClass() == com.fs.starfarer.api.combat.CollisionClass.NONE && "explosion_flak".equals(lastBurst.getSoundSetId()),
                "there it is taken off and the burst shown: the host's look and sound, no damage, colliding with nothing");
        // ours went off by itself nearby: the host's burst is that one, not shown again
        Shot ownBoom = new Shot(320f, 10f, 0f, 0f, 0f);
        ownBoom.boom = true;
        shot(ownBoom, gunShip, "flak");
        js.tick(0.016f);
        js.onFates(Extras.encodeShotFates(Arrays.asList(fb1)));
        check(bursts == 1 && js.burstsOurs == 1, "our shell burst by itself there: the host's burst isn't shown twice");
        // no copy and no burst of ours: shown at once
        js.onFates(Extras.encodeShotFates(Arrays.asList(fate(Extras.SHOT_BURST, 5, "flak", 10.05f, 0.3f, 900f, 900f))));
        check(bursts == 2, "a burst with no shell of ours anywhere is shown at once");

        // ---- the host's side: what it sees become of its shots, frame by frame ----
        field.clear();
        Map<ShipAPI, Integer> hostIds = new HashMap<ShipAPI, Integer>();
        ShipAPI hostGun = ship(0f, 0f, 0f), hostTarget = ship(500f, 0f, 90f);
        hostIds.put(hostGun, 5);
        hostIds.put(hostTarget, 12);
        HostShots hs = new HostShots(hostIds);
        Shot flak = new Shot(100f, 0f, 1000f, 0f, 0.1f), plain = new Shot(100f, 50f, 1000f, 0f, 0.1f), bounce = new Shot(100f, -50f, 1000f, 0f, 0.1f),
                bullet = new Shot(480f, 0f, 1000f, 0f, 0.48f);
        shot(flak, hostGun, "flak"); shot(plain, hostGun, "gun"); shot(bounce, hostGun, "gun"); shot(bullet, hostGun, "gun");
        hs.follow(engine);
        check(hs.batch().isEmpty(), "host, first frame: nothing to tell");
        field.remove(flak.api); // its fuse went off: an explosion in its place
        Shot ex = new Shot(110f, 0f, 0f, 0f, 0f);
        ex.boom = true;
        shot(ex, hostGun, "flak");
        field.remove(plain.api); // a script took it off, nothing in its place
        bounce.vel.set(-1000f, 0f); // reflected
        bullet.struck = true; bullet.struckShip = hostTarget; bullet.loc.set(495f, 10f);
        hs.follow(engine);
        List<Extras.ShotFate> told = new ArrayList<Extras.ShotFate>(hs.batch());
        hs.batch().clear();
        boolean sawBurst = false, sawTurn = false, sawHit = false, sawGone = false;
        for (Extras.ShotFate t : told) {
            if (t.kind == Extras.SHOT_BURST && t.x == 110f && t.exRadius == 50f && t.pCount == 100) sawBurst = true;
            if (t.kind == Extras.SHOT_TURNED && t.vx == -1000f) sawTurn = true;
            // the target faces +y (90): the point (-5, +10) from its centre is 10 ahead, 5 to its left (ox 10, oy 5)
            if (t.kind == Extras.SHOT_HIT && t.target == 12 && Math.abs(t.ox - 10f) < 0.01f && Math.abs(t.oy - 5f) < 0.01f) sawHit = true;
            if (t.kind == Extras.SHOT_GONE) sawGone = true;
        }
        if (!(sawBurst && sawTurn && sawHit && !sawGone)) for (Extras.ShotFate t : told) System.out.println("  told: kind " + t.kind + " at " + t.x + "," + t.y + " ox " + t.ox + " oy " + t.oy + " vx " + t.vx);
        check(sawBurst && sawTurn && sawHit && !sawGone, "host: a shell that became an explosion is a burst, a reflected one turned, a struck one a hit "
                + "(point in the struck ship's own frame); the one taken off waits a frame for an explosion");
        hs.follow(engine);
        check(hs.batch().size() == 1 && hs.batch().get(0).kind == Extras.SHOT_GONE && hs.batch().get(0).x == 100f && hs.batch().get(0).y == 50f,
                "a frame later, no explosion: it was taken off where it last was");
        hs.batch().clear();

        // ---- effects: the all-arcs flag and an arc's source travel with them ----
        Extras.Visual arc2 = new Extras.Visual();
        arc2.kind = Extras.FX_ARC; arc2.src = 7; arc2.aShip = 7; arc2.bShip = -1; arc2.thickness = 5f;
        int[] flagsOut = new int[1];
        List<Extras.Visual> vf = Extras.decodeVisuals(Extras.encodeVisuals(Arrays.asList(arc2), Extras.VIS_ALL_ARCS), flagsOut);
        check(flagsOut[0] == Extras.VIS_ALL_ARCS && vf.get(0).src == 7 && vf.get(0).frame == 0, "the host's 'every arc' flag and an arc's source arrive");
        check(Extras.decodeVisuals(Extras.encodeVisuals(new ArrayList<Extras.Visual>(), Extras.VIS_ALL_ARCS), flagsOut).isEmpty() && flagsOut[0] == 1,
                "an empty message still carries the flag (said every 2 s)");

        // ---- peak time and carriers' replacement rates, effects: handled where they arrive (JoinerState, JoinerVisuals) ----
        System.out.println(fails == 0 ? "FATES PASSED (" + checks + " checks)" : "FATES FAILED: " + fails + " of " + checks);
        if (fails > 0) System.exit(1);
    }
}

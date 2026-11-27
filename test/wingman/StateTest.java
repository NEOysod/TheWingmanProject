package wingman;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.combat.StatBonus;
import com.fs.starfarer.api.util.DynamicStatsAPI;

import wingman.net.Extras;

/**
 * The channels for what scripts change that position updates don't carry (protocol 65): ships' targets, bodies and
 * switches, missiles' owners, notes holding ships, missiles and points, and the wider and named stats.
 */
public class StateTest {
    static int fails, checks;
    static void check(boolean ok, String what) { checks++; System.out.println((ok ? "ok   " : "FAIL ") + what); if (!ok) fails++; }

    /** A ship's stats with two getters and a store of named stats, as the game's are. */
    static MutableShipStatsAPI stats(final MutableStat shieldTaken, final StatBonus armor, final Map<String, MutableStat> named, final Map<String, StatBonus> bonuses) {
        final DynamicStatsAPI dyn = (DynamicStatsAPI) Proxy.newProxyInstance(StateTest.class.getClassLoader(), new Class<?>[] { DynamicStatsAPI.class }, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                if (m.getName().equals("getStats")) return named;
                if (m.getName().equals("getMods")) return bonuses;
                if (m.getName().equals("getStat")) { MutableStat s = named.get(a[0]); if (s == null) { s = new MutableStat(1f); named.put((String) a[0], s); } return s; }
                if (m.getName().equals("getMod")) { StatBonus b = bonuses.get(a[0]); if (b == null) { b = new StatBonus(); bonuses.put((String) a[0], b); } return b; }
                return null;
            }
        });
        return (MutableShipStatsAPI) Proxy.newProxyInstance(StateTest.class.getClassLoader(), new Class<?>[] { MutableShipStatsAPI.class }, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                if (m.getName().equals("getShieldDamageTakenMult")) return shieldTaken;
                if (m.getName().equals("getArmorBonus")) return armor;
                if (m.getName().equals("getDynamic")) return dyn;
                return null;
            }
        });
    }

    public static void main(String[] a) throws Exception {
        // ---- targets ----
        Extras.Aim aim = new Extras.Aim();
        aim.ship = 7; aim.target = 12; aim.system = 12; aim.maneuver = -1; aim.carrier = 30; aim.hasPoint = true; aim.px = -1500.5f; aim.py = 320.25f;
        Extras.Aim none = new Extras.Aim();
        none.ship = 8;
        List<Extras.Aim> aims = Extras.decodeAims(Extras.encodeAims(Arrays.asList(aim, none)));
        Extras.Aim back = aims.get(0);
        check(aims.size() == 2 && back.ship == 7 && back.target == 12 && back.system == 12 && back.maneuver == -1 && back.carrier == 30
                && back.hasPoint && back.px == -1500.5f && back.py == 320.25f, "a ship's targets and system point arrive as sent");
        check(aims.get(1).target == -1 && aims.get(1).system == -1 && !aims.get(1).hasPoint, "a ship aiming at nothing arrives as nothing");
        check(back.same(aim) && !back.same(none), "the same targets count as the same; different ones don't");
        Extras.Aim moved = Extras.decodeAims(Extras.encodeAims(Arrays.asList(aim))).get(0);
        moved.px += 2f;
        check(moved.same(aim), "a system point that moved under 4 units isn't sent again");
        moved.px += 10f;
        check(!moved.same(aim), "one that moved further is");

        // ---- bodies ----
        Extras.Body b = new Extras.Body();
        b.ship = 3; b.collision = 2; b.mass = 4000f; b.radius = 120f; b.switches = Extras.NO_DEFENSE | Extras.NO_SYSTEM;
        b.shieldArc = 270f; b.shieldType = 1; b.tags.add("mod_marked"); b.tags.add("uaf_stance_2");
        Extras.Body bare = new Extras.Body();
        bare.ship = 4;
        List<Extras.Body> bodies = Extras.decodeBodies(Extras.encodeBodies(Arrays.asList(b, bare)));
        Extras.Body bb = bodies.get(0);
        check(bb.ship == 3 && bb.collision == 2 && bb.mass == 4000f && bb.radius == 120f && bb.switches == 3 && bb.shieldArc == 270f
                && bb.shieldType == 1 && bb.tags.equals(b.tags), "a body arrives as sent: collision, mass, radius, switches, shield, tags");
        Extras.Body bareBack = bodies.get(1);
        check(bareBack.collision == -1 && bareBack.mass != bareBack.mass && bareBack.shieldArc != bareBack.shieldArc && bareBack.shieldType == -1
                && bareBack.tags.isEmpty(), "a body with nothing known arrives as nothing known (no shield, no tags)");
        check(bb.same(b), "the same body counts as the same");
        Extras.Body heavier = Extras.decodeBodies(Extras.encodeBodies(Arrays.asList(b))).get(0);
        heavier.mass = 4010f;
        check(heavier.same(b), "a mass 0.25% off isn't news");
        heavier.mass = 4100f;
        check(!heavier.same(b), "one 2.5% off is");
        Extras.Body retagged = Extras.decodeBodies(Extras.encodeBodies(Arrays.asList(b))).get(0);
        retagged.tags.remove("mod_marked");
        check(!retagged.same(b), "a tag taken off is news");
        Extras.Body many = new Extras.Body();
        for (int i = 0; i < 60; i++) many.tags.add("t" + i);
        check(Extras.decodeBodies(Extras.encodeBodies(Arrays.asList(many))).get(0).tags.size() == 40, "at most 40 tags go");
        boolean refused = false;
        try {
            byte[] raw = Extras.encodeBodies(Arrays.asList(b));
            raw[17] = (byte) 41; // the tag count (after count, ship, class, mass, radius, switches, arc, type), made impossible
            Extras.decodeBodies(raw);
        } catch (java.io.IOException e) { refused = true; }
        check(refused, "a body claiming over 40 tags is refused");

        // ---- missiles' owners ----
        Extras.MissileWho who = new Extras.MissileWho();
        who.missile = 4431; who.owner = 0; who.source = 17;
        Extras.MissileWho lost = new Extras.MissileWho();
        lost.missile = 9; lost.owner = 1; lost.source = -1;
        List<Extras.MissileWho> whos = Extras.decodeMissileWho(Extras.encodeMissileWho(Arrays.asList(who, lost)));
        check(whos.get(0).missile == 4431 && whos.get(0).owner == 0 && whos.get(0).source == 17 && whos.get(1).source == -1 && whos.get(1).owner == 1,
                "a missile's new side and launcher arrive as sent");

        // ---- notes holding ships, missiles, points, and missiles' own notes ----
        List<Extras.Note> notes = new ArrayList<Extras.Note>();
        notes.add(Extras.refNote(5, "mymod_mark", Extras.SHIP_REF, 22));
        notes.add(Extras.refNote(-1, "mymod_lastMissile", Extras.MISSILE_REF, 901));
        notes.add(Extras.pointNote(5, "mymod_dest", 1234.5f, -678.25f));
        Extras.Note onMissile = Extras.noteOf(901, "mymod_stage", 2);
        onMissile.onMissile = true;
        notes.add(onMissile);
        Extras.Note gone = Extras.noteOf(901, "mymod_target", null);
        gone.onMissile = true;
        notes.add(gone);
        check(Extras.pointNote(5, "far", Float.NaN, 0f) == null, "a point that isn't one is never made into a note");
        List<Extras.Note> nb = Extras.decodeNotes(Extras.encodeNotes(notes));
        check(nb.size() == 5, "five notes arrive");
        check(nb.get(0).kind == Extras.SHIP_REF && nb.get(0).whole == 22 && nb.get(0).ship == 5 && !nb.get(0).onMissile, "a note holding a ship arrives as that ship's number");
        check(nb.get(1).kind == Extras.MISSILE_REF && nb.get(1).whole == 901 && nb.get(1).ship == -1, "a battle note holding a missile arrives as its number");
        check(nb.get(2).kind == Extras.POINT && nb.get(2).number == 1234.5 && nb.get(2).y == -678.25f, "a note holding a point arrives as x and y");
        check(nb.get(3).onMissile && nb.get(3).ship == 901 && nb.get(3).kind == 2 && ((Integer) nb.get(3).value(false)) == 2, "a missile's own value arrives on that missile");
        check(nb.get(4).onMissile && nb.get(4).kind == 0, "a missile's value gone arrives as gone, on the missile");
        check(nb.get(0).value(false) == null, "a ship note has no simple value (the joiner turns it into its own copy)");

        // ---- stat names and the wider stats ----
        Extras.StatName sn = new Extras.StatName();
        sn.index = 70; sn.name = "d:uaf_overdrive";
        check(Extras.decodeStatNames(Extras.encodeStatNames(Arrays.asList(sn))).get(0).name.equals("d:uaf_overdrive"), "a stat's name arrives with its number");
        Extras.MoreStats ms = new Extras.MoreStats();
        ms.ship = 11; ms.index = new int[] { 0, 70, 3 }; ms.values = new float[] { 0.5f, 3f, Float.NaN };
        Extras.MoreStats msb = Extras.decodeMoreStats(Extras.encodeMoreStats(Arrays.asList(ms))).get(0);
        check(msb.ship == 11 && msb.index[1] == 70 && msb.values[1] == 3f && msb.values[2] != msb.values[2], "named stats arrive by number, a gone one as NaN");

        check(MoreStats.COUNT > 60 && !Arrays.asList(MoreStats.GETTERS).contains("getHullDamageTakenMult")
                && !Arrays.asList(MoreStats.GETTERS).contains("getFluxCapacity") && !Arrays.asList(MoreStats.GETTERS).contains("getSuppliesPerMonth")
                && Arrays.asList(MoreStats.GETTERS).contains("getShieldDamageTakenMult") && Arrays.asList(MoreStats.GETTERS).contains("getAllowZeroFluxAtAnyLevel"),
                "the wider list has the battle's stats (" + MoreStats.COUNT + "), not the campaign's, ShipStats' or the ones Wingman keeps a copy unhurt with");

        // the host's ship: shield damage taken x0.8 (a hullmod), armor +100 flat, a mod's stat at 3 and a mod's bonus +50%
        Map<String, MutableStat> hn = new HashMap<String, MutableStat>();
        Map<String, StatBonus> hb = new HashMap<String, StatBonus>();
        MutableStat hShield = new MutableStat(1f);
        hShield.modifyMult("hullmod", 0.8f);
        StatBonus hArmor = new StatBonus();
        hArmor.modifyFlat("skill", 100f);
        MutableShipStatsAPI host = stats(hShield, hArmor, hn, hb);
        host.getDynamic().getStat("uaf_overdrive").modifyFlat("script", 2f);
        host.getDynamic().getMod("uaf_reach").modifyPercent("script", 50f);
        // the joiner's copy: none of that
        Map<String, MutableStat> jn = new HashMap<String, MutableStat>();
        Map<String, StatBonus> jb = new HashMap<String, StatBonus>();
        MutableStat jShield = new MutableStat(1f);
        StatBonus jArmor = new StatBonus();
        MutableShipStatsAPI copy = stats(jShield, jArmor, jn, jb);
        String[] names = { "m:getShieldDamageTakenMult", "m:getArmorBonus", "d:uaf_overdrive", "b:uaf_reach" };
        for (String n : names) {
            Object hs = MoreStats.resolve(host, n);
            float v = MoreStats.value(hs), at0 = hs instanceof StatBonus ? ((StatBonus) hs).computeEffective(0f) : Float.NaN;
            MoreStats.force(MoreStats.resolve(copy, n), v, at0);
        }
        check(Math.abs(jShield.getModifiedValue() - 0.8f) < 1e-4f, "a stat beyond the main list is made the host's: shield damage taken x0.8");
        check(Math.abs(jArmor.computeEffective(500f) - 600f) < 0.5f && Math.abs(jArmor.computeEffective(2000f) - 2100f) < 0.5f,
                "a bonus is made the host's at every base, not just the one compared at (armor +100 flat: 500 -> 600, 2000 -> 2100)");
        check(jn.containsKey("uaf_overdrive") && Math.abs(jn.get("uaf_overdrive").getModifiedValue() - 3f) < 1e-4f, "a mod's named stat this game hadn't made is made, at the host's value (3)");
        check(jb.containsKey("uaf_reach") && Math.abs(jb.get("uaf_reach").computeEffective(ShipStats.BASE) - 1.5f * ShipStats.BASE) < 0.5f, "a mod's named bonus is the host's (+50%)");
        // the host's script lets go of its stat: NaN, ours goes back to this game's own
        MoreStats.force(MoreStats.resolve(copy, "d:uaf_overdrive"), Float.NaN);
        check(Math.abs(jn.get("uaf_overdrive").getModifiedValue() - 1f) < 1e-4f, "a stat the host let go of is let go of here");
        // our own script changes it afterwards: corrected from where it is now, not stacked on our last correction
        jShield.modifyMult("local", 0.5f);
        MoreStats.force(MoreStats.resolve(copy, "m:getShieldDamageTakenMult"), 0.8f);
        check(Math.abs(jShield.getModifiedValue() - 0.8f) < 1e-4f, "a later local change is corrected to the host's again, not stacked");
        // bonuses exactly, whatever each side has: host +20% x1.5 +50 flat; ours +10% x0.8 and its own +30 flat
        StatBonus hb2 = new StatBonus();
        hb2.modifyPercent("a", 20f); hb2.modifyMult("b", 1.5f); hb2.modifyFlat("c", 50f);
        StatBonus jb2 = new StatBonus();
        jb2.modifyPercent("x", 10f); jb2.modifyMult("y", 0.8f); jb2.modifyFlat("z", 30f);
        ShipStats.forceBonus(jb2, hb2.computeEffective(ShipStats.BASE), hb2.computeEffective(0f));
        boolean everyBase = true;
        for (float base : new float[] { 0f, 1f, 150f, 700f, 1000f, 5000f }) if (Math.abs(jb2.computeEffective(base) - hb2.computeEffective(base)) > 0.05f) everyBase = false;
        check(everyBase, "a bonus with percent, mult and flat on both sides is the host's at every base (0 to 5000)");
        StatBonus jb3 = new StatBonus();
        jb3.modifyFlat("z", 30f);
        ShipStats.forceBonus(jb3, hb2.computeEffective(ShipStats.BASE));
        check(Math.abs(jb3.computeEffective(ShipStats.BASE) - hb2.computeEffective(ShipStats.BASE)) < 0.05f, "without the base-0 value it is right at BASE, as before");
        StatBonus jb4 = new StatBonus();
        jb4.modifyMult("dead", 0f); // nothing to scale: falls back to BASE
        ShipStats.forceBonus(jb4, hb2.computeEffective(ShipStats.BASE), hb2.computeEffective(0f));
        float dead = jb4.computeEffective(ShipStats.BASE);
        check(dead == dead && !Float.isInfinite(dead), "a bonus multiplied by 0 here doesn't break (no NaN or infinity: " + dead + ")");
        check(MoreStats.resolve(copy, "m:getNoSuchStat") == null && MoreStats.resolve(copy, "x:nonsense") == null && MoreStats.resolve(copy, null) == null,
                "a name this game doesn't know is passed over");

        System.out.println(fails == 0 ? "STATE PASSED (" + checks + " checks)" : "STATE FAILED: " + fails + " of " + checks);
        if (fails > 0) System.exit(1);
    }
}

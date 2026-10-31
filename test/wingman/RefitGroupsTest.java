package wingman;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import wingman.net.RefitData;
import wingman.net.RefitPlan;
import wingman.net.RefitRequest;

/** Weapon groups ride the refit data and request as optional tails, and the plan sees a change only when they differ. */
public final class RefitGroupsTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        codec();
        plan();
        System.out.println("RefitGroupsTest: " + checks + " checks passed");
    }

    private static void codec() throws Exception {
        RefitData d = ship();
        RefitData back = RefitData.decode(d.encode());
        check(back.hasGroups, "groups sent");
        check(back.groups.equals(d.groups), "groups round-trip: " + back.groups.size());

        RefitData old = ship();
        old.hasGroups = false;
        old.groups.clear();
        back = RefitData.decode(old.encode());
        check(!back.hasGroups && back.groups.isEmpty(), "no tail: an older host");

        RefitRequest r = new RefitRequest();
        r.memberId = "m1";
        RefitRequest rb = RefitRequest.decode(r.encode());
        check(rb.groups == null, "no groups wanted: unchanged");

        r.groups = Arrays.asList(group(true, false, "a", "b"), group(false, true, "c"));
        rb = RefitRequest.decode(r.encode());
        check(r.groups.equals(rb.groups), "request groups round-trip");

        r.groups = Arrays.asList(new RefitData.Group());
        byte[] b = r.encode();
        b[b.length - 2] = 4; // the flags: an unknown bit
        boolean refused = false;
        try { RefitRequest.decode(b); } catch (IOException e) { refused = true; }
        check(refused, "unknown group flag refused");
    }

    private static void plan() {
        RefitData d = ship();
        RefitRequest r = request();
        check(new RefitPlan(d, r).isEmpty(), "nothing asked: empty");

        r.groups = Arrays.asList(group(false, false, "a", "b"), group(true, true, "c"), new RefitData.Group());
        RefitPlan p = new RefitPlan(d, r);
        check(!p.groupsChanged() && p.isEmpty(), "same groups, padded: no change");

        r.groups = Arrays.asList(group(false, false, "a"), group(true, true, "c", "b"));
        p = new RefitPlan(d, r);
        check(p.groupsChanged() && !p.isEmpty() && p.problems.isEmpty(), "moved weapon: a change, allowed " + p.problems);

        r.groups = Arrays.asList(group(false, false, "a", "a", "zz"), group(true, true, "c"), group(false, false, "b"));
        List<RefitData.Group> n = RefitPlan.normalize(r.groups, new LinkedHashSet<String>(Arrays.asList("a", "b", "c")));
        check(n.size() == 3 && n.get(0).slots.equals(Arrays.asList("a")), "normalize drops repeats and unarmed slots: " + n.get(0).slots);

        r.groups = Arrays.asList(group(false, false, "a"), group(false, false, "a", "b"));
        check(!new RefitPlan(d, r).problems.isEmpty(), "weapon in two groups refused");

        RefitData many = ship();
        many.groups.clear();
        RefitRequest m = request();
        m.groups = new java.util.ArrayList<RefitData.Group>();
        for (int i = 0; i < 8; i++) {
            String s = "s" + i;
            many.fitSlots.add(s);
            many.fitWeapons.add("lightmg");
            m.groups.add(group(false, false, s));
        }
        check(!new RefitPlan(many, m).problems.isEmpty(), "eight groups refused");

        RefitRequest emptied = request();
        RefitRequest.Change c = new RefitRequest.Change();
        c.place = "c";
        c.item = "";
        emptied.changes.add(c);
        emptied.groups = Arrays.asList(group(false, false, "a", "b"), group(true, true, "c"));
        check(new RefitPlan(d, emptied).armedSlots().equals(new LinkedHashSet<String>(Arrays.asList("a", "b"))), "emptied slot leaves the groups");
        check(!new RefitPlan(d, emptied).groupsChanged(), "groups of an emptied slot: no change of their own");
    }

    private static RefitData ship() {
        RefitData d = new RefitData();
        d.memberId = "m1";
        d.hullId = "lasher";
        d.hullSize = "FRIGATE";
        d.fitSlots.addAll(Arrays.asList("a", "b", "c"));
        d.fitWeapons.addAll(Arrays.asList("lightmg", "lightmg", "harpoon"));
        d.hasGroups = true;
        d.groups.add(group(false, false, "a", "b"));
        d.groups.add(group(true, true, "c"));
        return d;
    }

    private static RefitRequest request() {
        RefitRequest r = new RefitRequest();
        r.memberId = "m1";
        return r;
    }

    private static RefitData.Group group(boolean alt, boolean auto, String... slots) {
        RefitData.Group g = new RefitData.Group(alt, auto);
        g.slots.addAll(Arrays.asList(slots));
        return g;
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
}

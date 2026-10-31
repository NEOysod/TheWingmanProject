package wingman;

import java.util.Arrays;

import wingman.net.RefitData;
import wingman.net.RefitPlan;
import wingman.net.RefitRequest;

/** A refit that only takes off another mod's loadout selectors is a free switch: sent at once, done without approval. */
public final class LoadoutSwitchTest {
    private static final String KIT = "diableavionics_virtuous_scicle_L", HEAD = "diableavionics_virtuous_headA";
    private static int checks;

    public static void main(String[] args) {
        RefitData d = ship();

        RefitRequest r = request();
        check(!new RefitPlan(d, r).onlyTakesModsOff() && !FriendRefit.loadoutSwitch(new RefitPlan(d, r)), "nothing asked: no switch");

        r.removeMods.add(KIT);
        RefitPlan p = new RefitPlan(d, r);
        check(p.onlyTakesModsOff() && !p.isEmpty() && FriendRefit.loadoutSwitch(p), "one selector off: a switch");

        r.removeMods.add(HEAD);
        check(FriendRefit.loadoutSwitch(new RefitPlan(d, r)), "two selectors off: a switch");

        r.removeMods.add("hardenedshieldemitter");
        check(new RefitPlan(d, r).onlyTakesModsOff() && !FriendRefit.loadoutSwitch(new RefitPlan(d, r)), "an ordinary hull mod off: needs approval");

        RefitRequest vents = request();
        vents.removeMods.add(KIT);
        vents.vents = d.vents + 1;
        check(!FriendRefit.loadoutSwitch(new RefitPlan(d, vents)), "selector with vents: needs approval");

        RefitRequest added = request();
        added.removeMods.add(KIT);
        added.addMods.add("hardenedshieldemitter");
        check(!FriendRefit.loadoutSwitch(new RefitPlan(d, added)), "selector with a hull mod added: needs approval");

        RefitRequest weapon = request();
        weapon.removeMods.add(KIT);
        RefitRequest.Change c = new RefitRequest.Change();
        c.place = "a";
        c.item = "";
        weapon.changes.add(c);
        check(!FriendRefit.loadoutSwitch(new RefitPlan(d, weapon)), "selector with a weapon taken off: needs approval");

        System.out.println("LoadoutSwitchTest: " + checks + " checks passed");
    }

    private static RefitData ship() {
        RefitData d = new RefitData();
        d.memberId = "m1";
        d.hullId = "diableavionics_virtuous_skirmisher";
        d.hullSize = "CRUISER";
        d.fitSlots.addAll(Arrays.asList("a"));
        d.fitWeapons.addAll(Arrays.asList("lightmg"));
        d.hullmods.addAll(Arrays.asList(KIT, HEAD, "hardenedshieldemitter"));
        return d;
    }

    private static RefitRequest request() {
        RefitRequest r = new RefitRequest();
        r.memberId = "m1";
        return r;
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
}

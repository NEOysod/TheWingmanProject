package wingman.compat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import wingman.compat.Bionics.Anatomy;
import wingman.compat.Bionics.ShipMsg;
import wingman.compat.Bionics.Step;

/** The Bionic Alteration messages and the change between two looks at a captain: plain data, no game. */
public final class BionicsTest {
    private static int checks;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    private static boolean rejects(int which, byte[] b) {
        try {
            switch (which) {
                case 0: Bionics.decodeAsk(b); break;
                case 1: Bionics.decodeShip(b); break;
                case 2: Bionics.decodeSet(b); break;
                default: Bionics.decodeResult(b);
            }
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private static ShipMsg ship() {
        ShipMsg m = new ShipMsg();
        m.officerId = "o1";
        m.first = "Ada";
        m.last = "Reyes";
        m.portrait = "graphics/portraits/p.png";
        m.faction = "player";
        m.personality = "steady";
        m.variant = "GENERIC_HUMAN";
        m.level = 7;
        m.brmTier = 3;
        m.baseConsciousness = 1f;
        m.baseBrmLimit = 18f;
        m.skillIds.add("helmsmanship");
        m.skillLevels.add(Float.valueOf(2f));
        m.anatomy.add(new Anatomy("heart", "ba_zen_core", ""));
        m.anatomy.add(new Anatomy("brain", "", ""));
        m.anatomy.add(new Anatomy("hand_right", "ba_exo_hand", "ba_woobly_psyche"));
        m.stacks.add(new Bionics.Stack("ba_zen_core", "", 2f));
        m.stacks.add(new Bionics.Stack("ba_exo_hand", "ba_woobly_psyche", 1f));
        m.revision = Bionics.revision(m.anatomy, m.brmTier);
        return m;
    }

    private static List<Anatomy> anatomy(String... limbBionicOverclock) {
        List<Anatomy> l = new ArrayList<Anatomy>();
        for (int i = 0; i + 2 < limbBionicOverclock.length; i += 3)
            l.add(new Anatomy(limbBionicOverclock[i], limbBionicOverclock[i + 1], limbBionicOverclock[i + 2]));
        return l;
    }

    /** Steps from kind, limb, bionic, overclock in turn. */
    private static List<Step> steps(Object... kindLimbBionicOverclock) {
        List<Step> l = new ArrayList<Step>();
        for (int i = 0; i + 3 < kindLimbBionicOverclock.length; i += 4)
            l.add(new Step(((Integer) kindLimbBionicOverclock[i]).intValue(), (String) kindLimbBionicOverclock[i + 1],
                    (String) kindLimbBionicOverclock[i + 2], (String) kindLimbBionicOverclock[i + 3]));
        return l;
    }

    public static void main(String[] args) throws Exception {
        // ask says which captain of which ship
        String[] ask = Bionics.decodeAsk(Bionics.encodeAsk("m1", "o1"));
        check("m1".equals(ask[0]) && "o1".equals(ask[1]), "ask round trip");
        byte[] bad = Bionics.encodeAsk("m1", "o1");
        bad[0] = 2;
        check(rejects(0, bad), "unknown ask version refused");

        // result
        Bionics.ResultMsg r = Bionics.decodeResult(Bionics.encodeResult(new Bionics.ResultMsg("o1", true, "2 change(s).")));
        check(r.ok && "2 change(s).".equals(r.text) && "o1".equals(r.officerId), "result round trip");

        // the captain message, everything on it
        ShipMsg m = ship();
        ShipMsg back = Bionics.decodeShip(Bionics.encodeShip(m));
        check(back.officerId.equals(m.officerId) && back.first.equals(m.first) && back.last.equals(m.last), "captain identity");
        check(back.brmTier == 3 && back.baseBrmLimit == 18f && back.baseConsciousness == 1f, "base values");
        check(back.revision == m.revision, "revision travels");
        check(back.anatomy.size() == 3 && back.anatomy.get(2).overclock.equals("ba_woobly_psyche"), "anatomy incl. overclock");
        check(back.stacks.size() == 2 && back.stacks.get(1).data.equals("ba_woobly_psyche") && back.stacks.get(1).qty == 1f, "container");
        check(back.skillIds.size() == 1 && back.skillLevels.get(0).floatValue() == 2f, "skills");
        check(rejects(1, new byte[] { 9 }), "short captain message refused");
        byte[] otherVersion = Bionics.encodeShip(ship());
        otherVersion[0] = 7;
        check(rejects(1, otherVersion), "captain message of another version refused");
        ShipMsg crowded = ship();
        for (int i = 0; i <= Bionics.MAX_ANATOMY; i++) crowded.anatomy.add(new Anatomy("limb" + i, "", ""));
        boolean crowdRefused = false;
        try { Bionics.encodeShip(crowded); } catch (IOException e) { crowdRefused = true; }
        check(crowdRefused, "more limbs than a message takes refused");
        ShipMsg none = ship();
        none.stacks.add(new Bionics.Stack("ba_zen_core", "", 0f));
        check(rejects(1, Bionics.encodeShip(none)), "a container stack of nothing refused");
        StringBuilder longKey = new StringBuilder();
        for (int i = 0; i <= Bionics.MAX_KEY; i++) longKey.append('k');
        boolean keyRefused = false;
        try { Bionics.encodeAsk("m1", longKey.toString()); } catch (IOException e) { keyRefused = true; }
        check(keyRefused, "an id longer than a key refused");

        // a limb that is not there is just not listed
        ShipMsg empty = Bionics.decodeShip(Bionics.encodeShip(new ShipMsg()));
        check(empty.anatomy.isEmpty() && empty.stacks.isEmpty(), "empty captain");

        // changes
        Bionics.SetMsg set = new Bionics.SetMsg();
        set.officerId = "o1";
        set.revision = m.revision;
        set.steps.add(new Step(Bionics.INSTALL, "heart", "ba_zen_core", ""));
        set.steps.add(new Step(Bionics.REMOVE, "brain", "ba_false_visage", ""));
        Bionics.SetMsg setBack = Bionics.decodeSet(Bionics.encodeSet(set));
        check(setBack.revision == m.revision && setBack.steps.size() == 2, "set round trip");
        check(setBack.steps.get(0).kind == Bionics.INSTALL && setBack.steps.get(1).kind == Bionics.REMOVE, "step kinds");
        Bionics.SetMsg big = new Bionics.SetMsg();
        for (int i = 0; i <= Bionics.MAX_STEPS; i++) big.steps.add(new Step(Bionics.INSTALL, "limb" + i, "ba_a", ""));
        boolean bigRefused = false;
        try { Bionics.encodeSet(big); } catch (IOException e) { bigRefused = true; }
        check(bigRefused, "more steps than a message takes refused on the way out");
        wingman.net.Out counted = new wingman.net.Out();
        counted.writeByte(Bionics.VERSION);
        counted.writeUTF("o1");
        counted.writeLong(m.revision);
        counted.writeVarInt(Bionics.MAX_STEPS + 1);
        for (int i = 0; i <= Bionics.MAX_STEPS; i++) { counted.writeByte(Bionics.INSTALL); counted.writeUTF("l"); counted.writeUTF("b"); counted.writeUTF(""); }
        check(rejects(2, counted.toByteArray()), "a step count over the limit refused on the way in");
        Bionics.SetMsg one = new Bionics.SetMsg();
        one.steps.add(new Step(7, "heart", "x", ""));
        boolean refused = false;
        try { Bionics.decodeSet(Bionics.encodeSet(one)); } catch (IOException e) { refused = true; }
        check(refused, "unknown step kind refused");

        // what changed between two looks
        List<Anatomy> before = anatomy("heart", "ba_zen_core", "", "brain", "", "", "legs", "ba_leg", "");
        List<Anatomy> after = anatomy("heart", "ba_zen_core", "", "brain", "ba_false_visage", "", "legs", "", "");
        List<Step> steps = Bionics.diff(before, after);
        check(steps.size() == 2, "one install and one removal: " + steps);
        check(steps.get(0).kind == Bionics.REMOVE && steps.get(0).bionic.equals("ba_leg"), "removals first");
        check(steps.get(1).kind == Bionics.INSTALL && steps.get(1).limb.equals("brain"), "then installs");
        check(Bionics.diff(before, before).isEmpty(), "nothing changed, nothing sent");
        // a swap on one limb: off first, on second
        List<Step> swap = Bionics.diff(anatomy("heart", "ba_a", ""), anatomy("heart", "ba_b", ""));
        check(swap.size() == 2 && swap.get(0).kind == Bionics.REMOVE && swap.get(0).bionic.equals("ba_a")
                && swap.get(1).kind == Bionics.INSTALL && swap.get(1).bionic.equals("ba_b"), "a swap is remove then install");
        // an overclock change on the same bionic is a swap too (the item with the new overclock must be in the hold)
        List<Step> oc = Bionics.diff(anatomy("heart", "ba_a", ""), anatomy("heart", "ba_a", "ba_woobly_psyche"));
        check(oc.size() == 2 && oc.get(1).overclock.equals("ba_woobly_psyche"), "overclock change travels");

        // the revision covers what the host will check
        long a = Bionics.revision(before, 1), b = Bionics.revision(after, 1), c = Bionics.revision(before, 2);
        check(a != b, "different bionics, different revision");
        check(a != c, "different tier, different revision");
        check(a == Bionics.revision(new ArrayList<Anatomy>(before), 1), "same look, same revision");

        // the battle copy's text: what the host writes on a captain and the joiner reads back (they have to agree,
        // or a joiner's ships never get the bionics)
        List<Anatomy> staged = anatomy("heart", "ba_zen_core", "", "brain", "", "", "legs", "ba_leg", "ba_woobly_psyche");
        Object[] battle = Bionics.battleBionics(Bionics.battleText(3, "GENERIC_HUMAN", 0.5f, staged));
        check(battle != null && ((Integer) battle[0]).intValue() == 3 && "GENERIC_HUMAN".equals(battle[1]), "battle text: tier and variant");
        check(battle != null && ((Float) battle[2]).floatValue() == 0.5f, "battle text: consciousness");
        List<?> limbs = (List<?>) battle[3];
        check(limbs.size() == 2 && "ba_leg".equals(((Anatomy) limbs.get(1)).bionic)
                && "ba_woobly_psyche".equals(((Anatomy) limbs.get(1)).overclock), "battle text: the bionics and their overclock");
        check("".equals(Bionics.battleBionics(Bionics.battleText(1, null, 1f, staged))[1]), "no variant stays empty");
        check(Bionics.battleBionics("2|1|x|1|") == null, "battle text of another version refused");
        check(Bionics.battleBionics("1|1|x|1") == null, "battle text without its bionic field refused");
        check(Bionics.battleBionics("1|one|x|1|heart:ba_a") == null, "battle text with a bad tier refused");
        check(Bionics.battleBionics("1|1|x|nope|heart:ba_a") == null, "battle text with a bad consciousness refused");
        check(Bionics.battleBionics(null) == null, "no battle text, no bionics");
        check(((List<?>) Bionics.battleBionics("1|1|x|1|;junk;brain:ba_a")[3]).size() == 1, "a bad entry in the battle text is skipped");

        // more changes than one message takes: the whole plan is kept and marked, never cut to its removals
        List<Anatomy> manyBefore = new ArrayList<Anatomy>(), manyAfter = new ArrayList<Anatomy>();
        for (int i = 0; i < 40; i++) {
            manyBefore.add(new Anatomy("limb" + i, "ba_a" + i, ""));
            manyAfter.add(new Anatomy("limb" + i, "ba_b" + i, ""));
        }
        List<Step> many = Bionics.diff(manyBefore, manyAfter);
        check(many.size() == 80 && many.get(39).kind == Bionics.REMOVE && many.get(40).kind == Bionics.INSTALL, "a long change is whole: " + many.size());
        check(new Bionics.Closed("o1", many, 0L).full, "a change over one message is marked full");
        check(!new Bionics.Closed("o1", many.subList(0, Bionics.MAX_STEPS), 0L).full, "a change of one message is not");

        // the host checks a plan whole before its first step
        List<Anatomy> cap = anatomy("heart", "ba_a", "", "brain", "", "", "legs", "ba_leg", "ba_oc");
        List<Bionics.Stack> hold = new ArrayList<Bionics.Stack>();
        hold.add(new Bionics.Stack("ba_b", "", 1f));
        check(Bionics.planProblem(cap, steps(Bionics.INSTALL, "brain", "ba_b", ""), hold) == null, "an install on a free limb with the item passes");
        check(Bionics.planProblem(cap, steps(Bionics.INSTALL, "tail", "ba_b", ""), hold) != null, "an install on a limb the captain lacks refused");
        check(Bionics.planProblem(cap, steps(Bionics.INSTALL, "heart", "ba_b", ""), hold) != null, "an install on a taken limb refused");
        check(Bionics.planProblem(cap, steps(Bionics.INSTALL, "brain", "ba_c", ""), hold) != null, "an install of an item not in the container refused");
        check(Bionics.planProblem(cap, steps(Bionics.INSTALL, "brain", "ba_b", "ba_oc"), hold) != null, "an install of the wrong overclock refused");
        check(Bionics.planProblem(cap, steps(Bionics.REMOVE, "brain", "ba_a", ""), hold) != null, "a removal from an empty limb refused");
        check(Bionics.planProblem(cap, steps(Bionics.REMOVE, "heart", "ba_x", ""), hold) != null, "a removal of a bionic not there refused");
        check(Bionics.planProblem(cap, steps(Bionics.REMOVE, "heart", "ba_a", "", Bionics.REMOVE, "heart", "ba_a", ""), hold) != null,
                "the same removal twice refused");
        check(Bionics.planProblem(cap, steps(Bionics.INSTALL, "heart", "ba_b", "", Bionics.REMOVE, "heart", "ba_a", ""), hold) == null,
                "a swap passes: removals are counted first whatever the order sent");
        check(Bionics.planProblem(cap, steps(Bionics.REMOVE, "heart", "ba_a", "", Bionics.INSTALL, "heart", "ba_c", ""), hold) != null,
                "a swap whose new item is gone refused before its removal");
        check(Bionics.planProblem(cap, steps(Bionics.REMOVE, "legs", "ba_leg", "", Bionics.INSTALL, "brain", "ba_leg", "ba_oc"), hold) == null,
                "a moved bionic keeps its overclock through the container");
        check(Bionics.planProblem(cap, steps(Bionics.INSTALL, "brain", "ba_b", "", Bionics.REMOVE, "heart", "ba_a", "",
                Bionics.INSTALL, "heart", "ba_b", ""), hold) != null, "one item can't go on two limbs");

        System.out.println("BionicsTest: " + checks + " checks passed");
    }
}
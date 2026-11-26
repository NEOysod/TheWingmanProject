import wingman.net.Motion;

/**
 * wingman.net.Motion: telling bursts and jumps from ordinary flying, whatever causes them.
 * Run: java -cp build/classes:build/test MotionTest
 */
public class MotionTest {
    static int checks, failed;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) failed++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    /** The jump test used before: a margin that grew with speed. */
    static boolean oldJumped(float x0, float y0, float vx0, float vy0, float x1, float y1, float vx1, float vy1, float dt) {
        float jx = x1 - (x0 + vx0 * dt), jy = y1 - (y0 + vy0 * dt);
        float pace2 = Math.abs(vx1) + Math.abs(vy1) + Math.abs(vx0) + Math.abs(vy0);
        float reach = 150f + pace2 * 0.3f;
        return jx * jx + jy * jy > reach * reach;
    }

    public static void main(String[] a) {
        float tick = 1f / 30f;
        // bursts: a destroyer, top speed 90 with a 50 zero-flux boost, engines 60 units/s²
        float top = 140f, acc = 60f;
        check(!Motion.burst(140f, 0f, 0f, 0f, top, acc), "flying at its top speed (zero-flux boost included): no burst");
        check(!Motion.burst(170f, 40f, 0f, 0f, top, acc), "a little over it (a nav buoy, a skill): no burst");
        check(Motion.burst(400f, 0f, 0f, 0f, top, acc), "burn drive speed: a burst");
        check(!Motion.burst(100f, 0f, 120f, 60f, top, acc), "speeding up as its engines do: no burst");
        check(Motion.burst(100f, 0f, 1500f, 0f, top, acc), "speeding up far faster than its engines (plasma jets, a dash, a knock): a burst");
        check(Motion.burst(0f, 0f, -3000f, 0f, top, acc), "stopped dead in an instant: a burst");

        // jumps
        check(!Motion.jumped(0f, 0f, 120f, 0f, 4f, 0f, 120f, 0f, tick), "flying on between two updates: no jump");
        check(Motion.jumped(0f, 0f, 120f, 0f, 304f, 0f, 120f, 0f, tick), "Phase Skimmer (300 units) on a destroyer: a jump");
        check(Motion.jumped(0f, 0f, 424f, 424f, 314f, 226f, 424f, 424f, tick), "Phase Skimmer on a ship going 600: a jump");
        check(!oldJumped(0f, 0f, 424f, 424f, 314f, 226f, 424f, 424f, tick), "(the test used before missed that one)");
        check(Motion.jumped(0f, 0f, 50f, 0f, 122f, 0f, 50f, 0f, tick), "a short blink (120 units) of a slow ship: a jump");
        check(!Motion.jumped(0f, 0f, 200f, 0f, 0f, 0f, -200f, 0f, tick), "bouncing off another ship (speed reversed): no jump");
        check(!Motion.jumped(0f, 0f, 0f, 0f, 250f, 0f, 500f, 0f, 1f), "a second of hard acceleration from rest, no update between: no jump");
        // turning at 300 units/s through a quarter circle in a second, no update between
        float r = 300f / (float) (Math.PI / 2);
        check(!Motion.jumped(0f, 0f, 300f, 0f, r, r, 0f, 300f, 1f), "a quarter turn at speed with no update between: no jump");
        check(!Motion.jumped(0f, 0f, 120f, 0f, 120f * 0.5f, 0f, 120f, 0f, 0.5f), "half a second of updates left out (guessed right): no jump");

        // a watch holds a burst a moment, and counts bursts once each
        Motion.Watch w = new Motion.Watch();
        w.burst(1f); w.burst(1.03f); w.burst(1.06f);
        check(w.surging(1.3f) && !w.surging(1.06f + Motion.HOLD + 0.01f), "a burst is held for " + Motion.HOLD + " s after it was last seen");
        check(w.bursts == 1, "one burst counted once, however many updates show it");
        w.jump(2f);
        check(w.jumpedWithin(2.2f, 0.3f) && !w.jumpedWithin(2.5f, 0.3f), "a jump is remembered for as long as asked");

        // steps (a shot's flux): comparisons right by one are left out, others made
        wingman.net.Steps st = new wingman.net.Steps();
        st.note(3.0f);
        check(st.near(3.04f, 0.06f) && st.near(2.97f, 0.06f), "a comparison right by a step is left out");
        check(!st.near(3.1f, 0.06f) && !st.near(2.9f, 0.06f), "one a little further off is made");
        check(st.skipped == 2, "and the ones left out are counted");
        for (int i = 0; i < 8; i++) st.note(10f + i);
        check(!st.near(3.0f, 0.06f), "only the last few steps are kept");

        // spins: a cruiser, top turn 15°/s, turn acceleration 20°/s²
        check(!Motion.spin(15f, 10f, 15f, 20f), "turning at its top turn rate: no spin");
        check(Motion.spin(60f, 0f, 15f, 20f), "four times that (maneuvering jets, a mod's spin): a spin");
        check(Motion.spin(10f, 400f, 15f, 20f), "its turn rate changing far faster than it can: a spin (knocked round by a hit)");
        Motion.Watch sw = new Motion.Watch();
        sw.spin(5f);
        check(sw.spinning(5.3f) && !sw.spinning(5.5f) && sw.spins == 1, "a spin is held for " + Motion.HOLD + " s, counted once");

        // missiles: a burst is a change of speed along its way, not a homing turn
        float mTop = 500f, mAcc = 400f;
        check(!Motion.burstAlong(500f, 0f, 0f, 1700f, mTop, mAcc), "a homing missile turning hard at full speed: no burst");
        check(!Motion.burstAlong(300f, 0f, 400f, 0f, mTop, mAcc), "speeding up as its engine does: no burst");
        check(Motion.burstAlong(300f, 0f, 3000f, 0f, mTop, mAcc), "a dash (far faster than its engine): a burst");
        check(Motion.burstAlong(900f, 0f, 0f, 0f, mTop, mAcc), "beyond its top speed (a split's kick): a burst");
        check(Motion.burst(500f, 0f, 0f, 1700f, mTop, mAcc), "(counting the turn too would have called that turn a burst)");

        // others' shots: how far behind they start, and that they catch up exactly
        check(Math.abs(wingman.net.ShotLead.lead(0f, 1f / 30f) - 1f / 60f) < 1e-4f, "on time, 30 updates a second: half an update behind (17 ms)");
        check(Math.abs(wingman.net.ShotLead.lead(0.02f, 1f / 30f) - (0.02f + 1f / 60f)) < 1e-4f, "an update 20 ms late: that much more");
        check(wingman.net.ShotLead.lead(0.1f, 0.5f) == wingman.net.ShotLead.MAX, "never more than " + Math.round(1000 * wingman.net.ShotLead.MAX) + " ms of flight");
        {
            float left = 30f, time = wingman.net.ShotLead.OVER, moved = 0f;
            float[] frames = { 1 / 60f, 1 / 45f, 1 / 120f, 1 / 30f, 1 / 60f, 1 / 60f, 1 / 50f, 1 / 60f };
            for (int i = 0; i < 40 && time > 0f; i++) {
                float amount = frames[i % frames.length];
                float f = wingman.net.ShotLead.share(time, amount);
                moved += left * f; left -= left * f; time -= amount;
            }
            check(Math.abs(moved - 30f) < 1e-3f, "over uneven frames the whole distance is made up, no more (" + moved + " of 30)");
        }

        System.out.println(failed == 0 ? "MOTION PASSED (" + checks + " checks)" : "MOTION FAILED (" + failed + " of " + checks + ")");
        if (failed > 0) System.exit(1);
    }
}

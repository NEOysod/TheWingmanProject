package wingman;

/**
 * OwnJumps against a simulated Phase Skimmer: a ship flying at 120 units/s that jumps 300 units, here and (a moment
 * apart, or not at all) on the host. The comparisons reconcile() would make are replayed: host's position for our
 * moment "when" against ours, recorded each frame and read between records as reconcile() does.
 * Run: java -cp build/classes:build/test wingman.JumpTest
 */
public class JumpTest {
    static int checks, failed;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) failed++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    static final float SPEED = 120f, JUMP = 300f, TJ = 2.0f;

    /** Where our copy is at time t: flying, and 300 units on from TJ (if it jumps here at all). */
    static float ours(float t, boolean jumpsHere) { return SPEED * t + (jumpsHere && t >= TJ ? JUMP : 0f); }

    /** Where the real ship was at our moment w: jumped 'late' seconds after ours, or never (late < 0). */
    static float host(float w, float late) { return SPEED * w + (late >= 0f && w >= TJ + late ? JUMP : 0f); }

    /**
     * Replays a battle second by second. Returns {largest difference that would have been pulled on (with OwnJumps,
     * or without it when 'plain'), largest difference seen while a jump was recent (what gets put right at once)}.
     */
    static float[] run(float late, boolean jumpsHere, boolean hostSeen, float rtt, boolean plain) {
        OwnJumps j = new OwnJumps();
        int n = 600;
        float[] pt = new float[n], px = new float[n];
        int count = 0;
        float frame = 1f / 60f, nextAck = 0.5f, worstPulled = 0f, worstRecent = 0f, offset = 0f;
        boolean hostJumpTold = false;
        for (int f = 0; f < n; f++) {
            float now = f * frame + (f % 3 == 0 ? 0.002f : 0f); // frames not quite even
            float x = ours(now, jumpsHere) + offset;
            if (count > 0) j.noteMove(pt[count - 1], px[count - 1], 0f, now, x, 0f, SPEED);
            pt[count] = now; px[count] = x; count++;
            if (now < nextAck) continue;
            nextAck += 1f / 30f;
            float when = now - rtt;
            if (when < pt[0]) continue;
            // the host's update for that moment; its jump is seen here when the first update after it arrives
            if (hostSeen && late >= 0f && !hostJumpTold && when >= TJ + late) { j.hostJumped(now); hostJumpTold = true; }
            // our copy at that moment, read between records as reconcile() does
            float mine = px[count - 1];
            for (int k = count - 1; k > 0; k--) {
                if (when > pt[k]) break;
                if (when < pt[k - 1]) continue;
                float span = pt[k] - pt[k - 1], a = span <= 0f ? 1f : (when - pt[k - 1]) / span;
                mine = px[k - 1] + (px[k] - px[k - 1]) * a;
                break;
            }
            float err = Math.abs(host(when, late) - mine);
            float signed = host(when, late) - mine;
            boolean used = plain || j.comparable(when, now, rtt, err);
            if (!used) continue;
            if (!plain && j.recent(now) && err > 120f) {
                // put right at once, as apply() does: our copy and its record move to the real one
                worstRecent = Math.max(worstRecent, err);
                offset += signed;
                for (int k = 0; k < count; k++) px[k] += signed;
                continue;
            }
            worstPulled = Math.max(worstPulled, err);
            if (j.recent(now)) worstRecent = Math.max(worstRecent, err);
        }
        return new float[] { worstPulled, worstRecent };
    }

    public static void main(String[] a) {
        float rtt = 0.06f;
        float[] r;
        r = run(1f / 60f, true, true, rtt, true);
        check(r[0] > 150f, "without it: a skim a frame apart on the two PCs is pulled on by up to " + Math.round(r[0]) + " units (the jitter)");
        r = run(0f, true, true, rtt, false);
        check(r[0] < 20f && r[1] < 20f, "same moment on both sides: nothing big pulled on or put right (" + Math.round(r[0]) + ", " + Math.round(r[1]) + ")");
        r = run(1f / 60f, true, true, rtt, false);
        check(r[0] < 20f && r[1] < 20f, "host a frame later: the same (" + Math.round(r[0]) + ", " + Math.round(r[1]) + ")");
        r = run(-1f / 60f + 1f / 60f, true, true, 0.15f, false);
        check(r[0] < 20f && r[1] < 20f, "a slower link (150 ms): the same (" + Math.round(r[0]) + ", " + Math.round(r[1]) + ")");
        r = run(0.15f, true, true, rtt, false);
        check(r[0] < 20f && r[1] < 20f, "host 150 ms later: waited for, nothing big pulled on (" + Math.round(r[0]) + ", " + Math.round(r[1]) + ")");
        r = run(0.15f, true, false, rtt, false);
        check(r[0] < 20f && r[1] < 20f, "host's jump not seen as one (fast ship): the two meeting again ends the wait (" + Math.round(r[0]) + ", " + Math.round(r[1]) + ")");
        r = run(-1f, true, true, rtt, false);
        check(r[0] < 20f && r[1] > 250f, "host's ship never jumped (out of charges there): put straight back, not slid (" + Math.round(r[1]) + " put right at once)");
        r = run(0f, false, true, rtt, false);
        check(r[0] < 20f && r[1] > 250f, "only the host's ship jumped: put straight there (" + Math.round(r[1]) + " put right at once)");
        r = run(-1f, false, false, rtt, false);
        check(r[0] < 1f && r[1] < 1f, "no jumps at all: every comparison made, nothing to put right");
        OwnJumps j = new OwnJumps();
        check(!j.noteMove(0f, 0f, 0f, 1f / 60f, 600f / 60f, 0f, 600f), "a fast ship's normal frame is not a jump");
        check(j.noteMove(0f, 0f, 0f, 1f / 60f, 300f, 0f, 120f), "300 units in a frame is");
        System.out.println(failed == 0 ? "JUMPS PASSED (" + checks + " checks)" : "JUMPS FAILED (" + failed + " of " + checks + ")");
        if (failed > 0) System.exit(1);
    }
}

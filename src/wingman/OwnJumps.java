package wingman;

/**
 * Jumps of the ship in a joiner's own hands: a teleporting system (vanilla Phase Skimmer and Phase Teleporter, mods'
 * blinks) or a script moving it. That ship runs here ahead of the real one on the host, and is kept with it by
 * comparing the host's position with where ours was at the same moment (see WingmanClientPlugin.reconcile). Around a
 * jump that comparison is worthless: a frame's difference in timing is the whole jump (300 units for a Phase
 * Skimmer), and a moment between two records is half of it. Pulling on that dragged the ship back toward where it
 * jumped from, then forward again: the jitter after every skim. So:
 * <ul>
 * <li>a comparison for a moment close to one of our jumps is left out;</li>
 * <li>after a jump here, comparisons wait for the host's ship to jump too (it does so a round trip later), or for
 *     long enough to be sure it didn't;</li>
 * <li>for a second after a jump on either side, a large difference is put right at once (the ship appears where the
 *     real one is, as a jump looks anyway) rather than slid across.</li>
 * </ul>
 * No game classes: checked by test/JumpTest.java.
 */
final class OwnJumps {
    /** Comparisons for a moment within this of one of our jumps are left out (seconds). */
    static final float WINDOW = 0.15f;
    /** After a jump here, how long past the round trip to wait for the host's (seconds). */
    static final float AWAIT = 0.5f;
    /** How long after a jump on either side a large difference is put right at once (seconds). */
    static final float AFTER = 1f;

    private final float[] local = new float[4];
    private int next;
    private float lastLocal, lastHost;
    int jumpsHere, jumpsThere, skipped;

    OwnJumps() { reset(); }

    /** A different ship in our hands: nothing known. */
    void reset() {
        for (int i = 0; i < local.length; i++) local[i] = -100f;
        lastLocal = -100f;
        lastHost = -100f;
    }

    /**
     * Our copy was at (px, py) at time pt and is at (x, y) at time t, going at speed sp: more than it could have
     * moved? Then it jumped, and that is noted (at the middle of the two). Returns whether it did.
     */
    boolean noteMove(float pt, float px, float py, float t, float x, float y, float sp) {
        float dt = Math.max(0f, t - pt);
        float reach = 100f + sp * dt * 3f;
        float dx = x - px, dy = y - py;
        if (dx * dx + dy * dy <= reach * reach) return false;
        float at = pt + dt * 0.5f;
        local[next] = at;
        next = (next + 1) % local.length;
        lastLocal = Math.max(lastLocal, at);
        jumpsHere++;
        return true;
    }

    /** The host's ship has just been seen to jump (now: when its update arrived here). */
    void hostJumped(float now) {
        lastHost = now;
        jumpsThere++;
    }

    /** A difference this small, after one of our jumps, means the host's ship has made the same jump. */
    static final float SAME = 75f;

    /**
     * May the host's position for our moment {@code when} be compared with ours now? Not if that moment is close
     * to one of our jumps, nor while we have jumped and the host's ship has not yet (for up to the round trip and
     * AWAIT). It has, if it was seen to jump, or if a moment after ours the two are close again ({@code err}, the
     * difference this comparison would give). rtt: the round trip in seconds (below 0: not known yet).
     */
    boolean comparable(float when, float now, float rtt, float err) {
        for (float j : local) {
            if (Math.abs(when - j) < WINDOW) { skipped++; return false; }
        }
        if (lastLocal > lastHost && now - lastLocal < Math.max(0f, rtt) + AWAIT) {
            if (when > lastLocal + WINDOW && err < SAME) { lastHost = now; return true; } // the real ship made the same jump
            skipped++;
            return false;
        }
        return true;
    }

    /** A jump on either side within AFTER of now: a large difference is put right at once. */
    boolean recent(float now) {
        return now - Math.max(lastLocal, lastHost) < AFTER;
    }

    /** A jump on either side within this many seconds of now. */
    boolean within(float now, float seconds) {
        return now - Math.max(lastLocal, lastHost) < seconds;
    }
}

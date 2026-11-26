package wingman.net;

/**
 * Moments when a value jumped in a single frame (a shot's flux, a vent ending), so comparisons for a moment close
 * to one can be left out: there a frame's difference in timing between two PCs is the whole step, and a moment
 * between two records is half of it, as with a ship's jump (see wingman.OwnJumps). Keeps the last few. Plain
 * arithmetic: test/MotionTest.
 */
public final class Steps {
    private final float[] at = new float[8];
    private int next;
    /** Comparisons left out (for the log). */
    public int skipped;

    public Steps() { reset(); }

    public void reset() { for (int i = 0; i < at.length; i++) at[i] = -100f; }

    /** A step happened at this moment. */
    public void note(float when) {
        at[next] = when;
        next = (next + 1) % at.length;
    }

    /** Is this moment within 'window' seconds of a step? Counted as a comparison left out if so. */
    public boolean near(float when, float window) {
        for (float a : at) if (Math.abs(when - a) < window) { skipped++; return true; }
        return false;
    }
}

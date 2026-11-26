package wingman.net;

/**
 * Fast movement, told apart from ordinary flying, whatever causes it. Two kinds:
 * <ul>
 * <li>a <b>burst</b>: a ship going, or changing speed, faster than its own engines can: a burn drive, plasma jets,
 *     a phase ship in phase, a mod's dash, a shove from a collision or an explosion;</li>
 * <li>a <b>jump</b>: a ship somewhere no flying could have taken it: Phase Skimmer, Phase Teleporter, a mod's
 *     blink, a script putting it somewhere.</li>
 * </ul>
 * Copies of ships are kept with the host's differently during both (see WingmanClientPlugin.apply, OwnJumps), and
 * which ship systems cause them is learned (see wingman.Movers). Judged from how the ship moves, not from which
 * system it has: a mod's system nobody has described still counts. Plain arithmetic, no game classes:
 * test/MotionTest checks it.
 */
public final class Motion {
    private Motion() {}

    /** Beyond this share of a hull's own top speed, plus FAST_PLUS, a ship is in a burst. */
    public static final float FAST = 1.2f, FAST_PLUS = 20f;
    /** Changing speed beyond this many times its engines' acceleration, plus SHARP_PLUS units/s², is a burst too. */
    public static final float SHARP = 3f, SHARP_PLUS = 150f;
    /** A burst counts as one for this long after it was last seen (seconds): the speed it left behind settles. */
    public static final float HOLD = 0.4f;
    /** Moving this much further than flying explains is a jump (units). */
    public static final float JUMP_MARGIN = 100f;

    /**
     * Is a ship going this fast (vx, vy), or changing speed this sharply (ax, ay), beyond what it does by itself?
     * normalTop: its top speed with nothing helping but the zero-flux boost; engineAcc: its engines' acceleration
     * (or deceleration, whichever is more).
     */
    public static boolean burst(float vx, float vy, float ax, float ay, float normalTop, float engineAcc) {
        float top = Math.max(0f, normalTop) * FAST + FAST_PLUS;
        if (vx * vx + vy * vy > top * top) return true;
        float cap = Math.max(0f, engineAcc) * SHARP + SHARP_PLUS;
        return ax * ax + ay * ay > cap * cap;
    }

    /**
     * From one state to the next, dt seconds later: is the ship further on than flying could take it? Its speed
     * may have changed anywhere between the two, so it is allowed what the average of the two speeds covers, half
     * their difference either way, and JUMP_MARGIN (positions are rounded, and a collision can shove a little).
     * Unlike a margin that grows with speed, this still sees a short jump of a fast ship.
     */
    public static boolean jumped(float x0, float y0, float vx0, float vy0, float x1, float y1, float vx1, float vy1, float dt) {
        dt = Math.max(0f, Math.min(1f, dt));
        float ex = x1 - (x0 + 0.5f * (vx0 + vx1) * dt), ey = y1 - (y0 + 0.5f * (vy0 + vy1) * dt);
        float dvx = vx1 - vx0, dvy = vy1 - vy0;
        float reach = JUMP_MARGIN + 0.5f * (float) Math.sqrt(dvx * dvx + dvy * dvy) * dt;
        return ex * ex + ey * ey > reach * reach;
    }

    /** One ship's recent fast movement: when a burst and a jump were last seen. */
    public static final class Watch {
        private float burstSeen = -100f, jumpSeen = -100f;
        /** How many bursts and jumps began (for the log). */
        public int bursts, jumps;

        public void burst(float now) {
            if (now - burstSeen >= HOLD) bursts++;
            burstSeen = now;
        }

        public void jump(float now) {
            jumpSeen = now;
            jumps++;
        }

        /** In a burst, or within HOLD of one. */
        public boolean surging(float now) { return now - burstSeen < HOLD; }

        /** A jump within this many seconds. */
        public boolean jumpedWithin(float now, float seconds) { return now - jumpSeen < seconds; }

        public void reset() { burstSeen = -100f; jumpSeen = -100f; }
    }
}

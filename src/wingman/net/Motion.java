package wingman.net;

/**
 * Fast movement, told apart from ordinary flying, whatever causes it. Three kinds:
 * <ul>
 * <li>a <b>burst</b>: a ship going, or changing speed, faster than its own engines can: a burn drive, plasma jets,
 *     a phase ship in phase, a mod's dash, a shove from a collision or an explosion;</li>
 * <li>a <b>jump</b>: a ship somewhere no flying could have taken it: Phase Skimmer, Phase Teleporter, a mod's
 *     blink, a script putting it somewhere;</li>
 * <li>a <b>spin</b>: turning, or changing how fast it turns, faster than it can by itself: maneuvering jets, a mod's
 *     spin move, a ship knocked round by a hit.</li>
 * </ul>
 * The same for missiles (their own top speed, acceleration and turning), where a burst is a change of speed along
 * the way it flies (a homing missile's turn is not one): a MIRV or a Sabot splitting, a mod's dashing missile.
 * Copies of ships and missiles are kept with the host's differently during all three (see WingmanClientPlugin.apply, OwnJumps), and
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
    /** Turning beyond this share of its top turn rate plus SPIN_PLUS degrees/s, or its turn rate changing beyond
     *  SPIN_SHARP times its turn acceleration plus SPIN_SHARP_PLUS degrees/s², is a spin. */
    public static final float SPIN = 1.2f, SPIN_PLUS = 10f, SPIN_SHARP = 3f, SPIN_SHARP_PLUS = 60f;
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
     * Speeding up or slowing down along its way faster than its engine can, or going faster than its top speed: a
     * missile's burst. Only the part of (ax, ay) along its flight counts: turning bends its path without being one.
     */
    public static boolean burstAlong(float vx, float vy, float ax, float ay, float normalTop, float engineAcc) {
        float v2 = vx * vx + vy * vy;
        float top = Math.max(0f, normalTop) * FAST + FAST_PLUS;
        if (v2 > top * top) return true;
        float cap = Math.max(0f, engineAcc) * SHARP + SHARP_PLUS;
        if (v2 < 1f) return ax * ax + ay * ay > cap * cap;
        float along = (vx * ax + vy * ay) / (float) Math.sqrt(v2);
        return Math.abs(along) > cap;
    }

    /** Turning (angVel, degrees/s) or changing its turn rate (angAcc) beyond what it can by itself: a spin. */
    public static boolean spin(float angVel, float angAcc, float maxTurn, float turnAcc) {
        if (Math.abs(angVel) > Math.max(0f, maxTurn) * SPIN + SPIN_PLUS) return true;
        return Math.abs(angAcc) > Math.max(0f, turnAcc) * SPIN_SHARP + SPIN_SHARP_PLUS;
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

    /** A copy moved by this game's own physics beyond this (units), plus what its engines could add, was pushed. */
    public static final float SHOVE = 1.5f;

    /**
     * A copy of someone else's ship was put at (setX, setY) going (setVx, setVy) dt seconds ago, and is now at
     * (x, y): did this game push it there (a collision with another copy, our ship or an asteroid, the knock of
     * an explosion here, its speed clamped by this game)? Those happen on the host too, where they decide where the
     * real ship goes, and that arrives with the next update; one made here only fights it, so ships touching shook.
     * Its engines can account for a hair (engineAcc dt²).
     */
    public static boolean shoved(float setX, float setY, float setVx, float setVy, float dt, float x, float y, float engineAcc) {
        if (!(dt > 0f)) return false;
        float ex = x - (setX + setVx * dt), ey = y - (setY + setVy * dt);
        float tol = SHOVE + Math.max(0f, engineAcc) * dt * dt;
        return ex * ex + ey * ey > tol * tol;
    }

    /**
     * Our own ship's speed changed by (dvx, dvy) in dt seconds by this game's doing: more than its engines can,
     * i.e. it was knocked (a collision, an explosion here)? Then the host's knock comes a moment later, and maybe
     * not quite the same: like a burst, small differences are left alone until both agree.
     */
    public static boolean knocked(float dvx, float dvy, float dt, float engineAcc) {
        if (!(dt > 0f)) return false;
        float cap = (Math.max(0f, engineAcc) * SHARP + SHARP_PLUS) * dt;
        return dvx * dvx + dvy * dvy > cap * cap;
    }

    /** One ship's recent fast movement: when a burst and a jump were last seen. */
    public static final class Watch {
        private float burstSeen = -100f, jumpSeen = -100f, spinSeen = -100f;
        /** How many bursts, jumps and spins began (for the log). */
        public int bursts, jumps, spins;

        public void burst(float now) {
            if (now - burstSeen >= HOLD) bursts++;
            burstSeen = now;
        }

        public void jump(float now) {
            jumpSeen = now;
            jumps++;
        }

        public void spin(float now) {
            if (now - spinSeen >= HOLD) spins++;
            spinSeen = now;
        }

        /** In a burst, or within HOLD of one. */
        public boolean surging(float now) { return now - burstSeen < HOLD; }

        /** In a spin, or within HOLD of one. */
        public boolean spinning(float now) { return now - spinSeen < HOLD; }

        /** A jump within this many seconds. */
        public boolean jumpedWithin(float now, float seconds) { return now - jumpSeen < seconds; }

        public void reset() { burstSeen = -100f; jumpSeen = -100f; spinSeen = -100f; }
    }
}

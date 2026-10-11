package wingman.net;

/**
 * How a joiner moves a copy of a ship it doesn't fly, in the two optional modes (joiner settings; the plain mode,
 * carried along and corrected beyond a tolerance, is in WingmanClientPlugin.apply). Plain arithmetic, no game
 * classes, so test/SyncBench measures exactly this.
 *
 * <b>Smooth corrections</b> ({@code smoothCorrections}): what the joiner believes the host's ship is doing (the
 * <i>model</i>: the host's last state carried forward) is kept apart from where the ship is <i>shown</i>. The
 * ship is moved by the game at the model's speed, so while the model moves smoothly the two move together. When
 * an update moves the model (it arrived late, or the ship turned since the last one), or something shoves the
 * ship, the difference is an <i>error offset</i>: shown = model + offset, and the offset fades by a fixed share
 * every frame (faster for a large one). Every correction is then the same smooth curve, whatever the timing of
 * the update that caused it, and the ship always ends exactly on the model: no tolerance band it may sit off by.
 *
 * <b>Jitter buffer</b> ({@code jitterBuffer}, with smooth corrections): copies are shown a little in the past,
 * at host time {@code now - delay}, carried on from the update just before that moment, never further than to
 * the next one, which has already arrived. How unevenly updates arrive then makes no difference: each one is
 * used at the same moment after the host's clock. (Curves drawn through the updates themselves were tried and
 * measured worse: their positions are rounded, and a curve through rounded points wobbles.) The delay is what
 * the link needs, measured: the latest an update has been lately, plus the gap between updates, plus a frame;
 * at most 0.25 s; it changes by at most 3% of the time passing. When no update after the moment shown has come in,
 * the model carries on from the newest as without the buffer. Shots, hits and the joiner's own ship are not
 * delayed.
 */
public final class Smoother {
    private Smoother() {}

    /** Most delay the jitter buffer adds, seconds. */
    public static final float MAX_DELAY = 0.25f;
    /** An offset bigger than this is not faded but dropped (the ship is put straight on the model). */
    public static final float SNAP = 500f;

    /** One copy's recent host states and its error offset. */
    public static final class Track {
        static final int N = 10;
        // ring of host states: time, x, y, vx, vy, ax, ay, facing, turn rate, turn acceleration
        private final float[] t = new float[N], x = new float[N], y = new float[N], vx = new float[N], vy = new float[N],
                ax = new float[N], ay = new float[N], f = new float[N], w = new float[N], wa = new float[N];
        private int head = -1, count;
        /** Error offset: shown minus model. */
        public float ox, oy, of;
        /** The model, after sample(). */
        public float mx, my, mvx, mvy, mf, mw;
        /** sample() found an update at or before the moment asked for, with a newer one also here (jitter buffer). */
        public boolean between;
        /** The host's ship leapt (a teleport) at this host time; NaN: no leap pending. */
        private float jumpAt = Float.NaN;
        /** sample() has reached the leap: put the copy straight there. */
        public boolean jumpedNow;

        /** The update with this host time is a leap no engine could make: snap to it once it is shown. */
        public void markJump(float hostTime) { jumpAt = hostTime; }

        public boolean empty() { return count == 0; }
        public float newestTime() { return count == 0 ? 0f : t[head]; }

        /** An update's state for this copy. Older than the newest held (reordered): ignored. */
        public void push(float hostTime, float px, float py, float pvx, float pvy, float pax, float pay,
                float facing, float angVel, float angAcc) {
            if (count > 0 && hostTime <= t[head]) {
                if (hostTime < t[head]) return;
                count--; head = (head + N - 1) % N; // the same moment again: the newer figures win
            }
            head = (head + 1) % N;
            if (count < N) count++;
            t[head] = hostTime; x[head] = px; y[head] = py; vx[head] = pvx; vy[head] = pvy;
            ax[head] = pax; ay[head] = pay; f[head] = facing; w[head] = angVel; wa[head] = angAcc;
        }

        /** Forget the past (a new ship in the same slot, a teleport): the next update starts it afresh. */
        public void reset() { count = 0; head = -1; ox = oy = of = 0f; jumpAt = Float.NaN; }

        /**
         * The model at host time r. With {@code interpolate} (the jitter buffer), carried on from the newest update
         * at or before r, so never further than to the next one; before the oldest held, carried back from that
         * one. Otherwise carried on from the newest update. Age capped at {@code maxAge}; acceleration used for at
         * most {@code accReach} seconds, not at all if {@code noAcc}.
         */
        public void sample(float r, boolean interpolate, float maxAge, float accReach, boolean noAcc) {
            between = false;
            if (count == 0) return;
            int n = head;
            if (interpolate && r < t[head]) {
                n = (head - count + 1 + N) % N; // the oldest, unless one at or before r is found
                for (int k = 1; k < count; k++) {
                    int a = (head - k + N) % N;
                    if (t[a] <= r) { n = a; between = true; break; }
                }
            }
            jumpedNow = !Float.isNaN(jumpAt) && t[n] >= jumpAt;
            if (jumpedNow) jumpAt = Float.NaN;
            float age = Math.max(interpolate ? -maxAge : 0f, Math.min(maxAge, r - t[n]));
            float aa = noAcc || age < 0f ? 0f : Math.min(age, accReach);
            mx = x[n] + (vx[n] + 0.5f * ax[n] * aa) * age;
            my = y[n] + (vy[n] + 0.5f * ay[n] * aa) * age;
            mvx = vx[n] + ax[n] * aa;
            mvy = vy[n] + ay[n] * aa;
            mf = f[n] + (w[n] + 0.5f * wa[n] * aa) * age;
            mw = w[n] + wa[n] * aa;
        }

        /** Where to show the copy this frame, after follow(). */
        public float sx, sy, sf;

        /**
         * Given where the copy is now (moved since last frame by the game, at the model's speed, and by anything
         * that shoved it), work out where to show it: the offset from the model is what it is now, faded by one
         * frame's share. A continuously moving model is followed exactly; a jump in the model (an update that
         * changed the guess) becomes an offset and fades. Returns true if the copy is put straight onto the
         * model instead (offset beyond SNAP, or {@code snap}).
         */
        public boolean follow(float locX, float locY, float facing, float amount, boolean snap) {
            ox = locX - mx; oy = locY - my; of = turn(mf, facing);
            boolean jump = snap || ox * ox + oy * oy > SNAP * SNAP;
            if (jump) { ox = 0f; oy = 0f; }
            if (snap || Math.abs(of) > 90f) of = 0f;
            fade(amount);
            sx = mx + ox; sy = my + oy; sf = mf + of;
            return jump;
        }

        /** Fade the offset over {@code amount} seconds: about 10% a frame at 60 frames a second, up to 20% for a large one. */
        public void fade(float amount) {
            if (amount <= 0f) return;
            float e = (float) Math.sqrt(ox * ox + oy * oy);
            float perFrame = e <= 20f ? 0.90f : e >= 100f ? 0.80f : 0.90f - 0.10f * (e - 20f) / 80f;
            float k = (float) Math.pow(perFrame, amount * 60f);
            ox *= k; oy *= k;
            if (e * k < 0.05f) { ox = 0f; oy = 0f; }
            of *= (float) Math.pow(0.88f, amount * 60f);
            if (Math.abs(of) < 0.01f) of = 0f;
        }
    }

    /** The short way round from angle a to angle b, degrees. */
    public static float turn(float a, float b) {
        float d = (b - a) % 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    /**
     * The jitter buffer's clock: which host moment to show now. {@code base} is the usual gap between the host's
     * clock in an update and ours when it arrives (the promptest arrivals of the last few seconds); {@code delay}
     * is what the link needs on top: the latest an update has been lately, plus the gap between updates, plus a
     * frame, at most MAX_DELAY. Together they move by at most 3% of the time passing, so the copies never jump
     * back or forward, only run up to 3% slow or fast for a moment. Show host time {@link #showing(float)}.
     */
    public static final class Delay {
        private float windowStart = -1f, baseNow = Float.MAX_VALUE, basePrev = Float.MAX_VALUE, maxLateNow, maxLatePrev,
                lastHostTime = -1f, spacing = 1f / 30f, lastClock = -1f, wantBase;
        public float delay = 0.1f, base = Float.NaN;
        /** What the delay is moving toward. */
        public float want = 0.1f;

        /** An update with this host time arrived at this clock (ours). */
        public void update(float clock, float hostTime) {
            float gap = clock - hostTime;
            if (!Float.isNaN(base) && Math.abs(gap - base) > 1.5f) { // the clocks jumped apart (a pause, a new battle): start over
                base = Float.NaN; baseNow = basePrev = Float.MAX_VALUE; maxLateNow = maxLatePrev = 0f; windowStart = -1f;
            }
            if (windowStart < 0f || clock - windowStart > 3f) {
                basePrev = baseNow; maxLatePrev = maxLateNow; baseNow = Float.MAX_VALUE; maxLateNow = 0f; windowStart = clock;
            }
            if (gap < baseNow) baseNow = gap;
            wantBase = Math.min(baseNow, basePrev);
            if (Float.isNaN(base)) base = wantBase;
            float late = Math.max(0f, Math.min(MAX_DELAY, gap - wantBase));
            if (late > maxLateNow) maxLateNow = late;
            if (lastHostTime >= 0f && hostTime > lastHostTime) {
                float between = Math.min(0.5f, hostTime - lastHostTime);
                spacing += (between - spacing) * 0.1f;
            }
            if (hostTime > lastHostTime || Float.isNaN(base)) lastHostTime = hostTime;
            want = Math.min(MAX_DELAY, Math.max(maxLateNow, maxLatePrev) + spacing + 1f / 60f);
        }

        /** What we show lags our clock by: base + delay, moved as one. */
        private float lag = Float.NaN;
        private boolean moving;

        /**
         * Once a frame. The lag (base + delay) moves toward what is wanted at 3% of the time passing, and only
         * once it is off by more than a few milliseconds (more when it would shrink), so it changes seldom and
         * gently: every change of pace is a small bump in every copy's speed.
         */
        public void advance(float clock) {
            float dt = lastClock < 0f ? 0f : Math.max(0f, Math.min(0.1f, clock - lastClock));
            lastClock = clock;
            if (Float.isNaN(base)) return;
            float target = wantBase + want;
            if (Float.isNaN(lag)) lag = target;
            float off = target - lag;
            if (!moving && (off > 0.006f || off < -0.02f)) moving = true;
            if (moving) {
                float step = 0.03f * dt;
                if (Math.abs(off) <= step) { lag = target; moving = false; } else lag += off > 0f ? step : -step;
            }
            delay = lag - wantBase;
            base = wantBase;
        }

        /** The host moment to show at this clock. */
        public float showing(float clock) { return Float.isNaN(lag) ? clock - (Float.isNaN(base) ? 0f : base) - delay : clock - lag; }
    }
}

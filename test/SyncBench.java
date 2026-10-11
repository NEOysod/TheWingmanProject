import java.util.*;
import java.util.zip.Deflater;
import wingman.net.*;

/**
 * Bandwidth and smoothness of position updates, measured on a simulated battle. No game needed.
 *
 * A host battle (ships, fighters, missiles that accelerate, turn and fire) runs at 60 frames a second. At the
 * chosen update rate its state goes through the real SnapshotCodec, as to one joiner over TCP (compressed the
 * way Conn does) and over UDP (raw, in pieces). The updates reach the joiner after a delay with jitter; the
 * joiner moves its copies the way WingmanClientPlugin does (carried at the host's speed, corrected beyond a
 * tolerance). Reported:
 *   - bandwidth per joiner: TCP after compression, UDP with packet headers, and raw bytes by kind of thing;
 *   - how far the joiner's copies are from where the host had them one delay ago (what a perfect link would
 *     show): average and 95th percentile, in units;
 *   - jitter: how unevenly the copies move compared with the real thing (RMS of the difference in acceleration,
 *     units/s^2). This is what shows as shaking.
 *
 * Usage: java -cp jars/Wingman.jar:test SyncBench [rate=60] [seconds=60] [delayMs=50] [jitterMs=20]
 *        [ships=40] [fighters=80] [missiles=200] [seed=1] [accel=1: joiner carries copies along their acceleration]
 *        [mode=carry|smooth|buffer: the joiner's way of moving copies; see wingman.net.Smoother]
 *        [limit=0: the host's bandwidth limit, KB/s before compression (see wingman.net.Budget); the player looks at 0,0]
 *        [near=0: measure only what is within this distance of 0,0 (0: everything)]
 * In buffer mode copies of ships and fighters (not missiles, as on the joiner) are deliberately shown later (the jitter buffer's delay), so they are compared with where
 * the host had them that much earlier; the delay is reported.
 */
public class SyncBench {
    static final float DT = 1f / 60f;

    // ---- The host's battle ----

    static final class Body {
        int id;
        int kind; // 0 ship, 1 fighter, 2 missile
        float x, y, vx, vy, facing, angVel;
        float maxSpeed, accel, turn, turnAccel;
        float wantVx, wantVy, wantFacing, nextChoice;
        float flux, hardFlux, maxFlux, hull = 1f, cr = 0.7f, shieldFacing, flight, life;
        boolean shield, guided;
        long firing;
        int weapons;
        byte[] angles, totals;
        float tx, ty; // a missile's target point
        // what the joiner shows
        Snapshot.ShipState st;
        Snapshot.MissileState ms;
        float targetAt = -1f;
        float jx, jy, jvx, jvy, jf;
        boolean shown;
        float px1, py1, px2, py2, rx1, ry1, rx2, ry2; // last two shown / reference positions, for jitter
        int frames;
        Smoother.Track tr = new Smoother.Track();
        float[] hx = new float[256], hy = new float[256]; // host positions by frame
    }

    static Random rnd;
    /** Joiner carries copies on along their acceleration (accel=1), or in a straight line (accel=0). */
    static boolean useAcc;
    /** 0 carry (the plain mode), 1 smooth corrections, 2 smooth corrections + jitter buffer. */
    static int mode;
    static float hostNowGap, bufDelay, bufShowing;
    static int nextId = 1;
    static List<Body> bodies = new ArrayList<Body>();

    static float range(float a, float b) { return a + rnd.nextFloat() * (b - a); }

    static Body ship(int kind) {
        Body b = new Body();
        b.id = nextId++;
        b.kind = kind;
        b.x = range(-6000, 6000); b.y = range(-6000, 6000);
        b.facing = range(0, 360);
        if (kind == 0) {
            b.maxSpeed = range(50, 180); b.accel = b.maxSpeed * range(0.6f, 1.4f); b.turn = range(12, 45); b.turnAccel = b.turn * 2f;
            b.maxFlux = range(3000, 20000); b.weapons = 6 + rnd.nextInt(14);
        } else {
            b.maxSpeed = range(220, 360); b.accel = range(300, 500); b.turn = range(90, 160); b.turnAccel = b.turn * 4f;
            b.maxFlux = range(200, 600); b.weapons = 1 + rnd.nextInt(3);
        }
        b.angles = new byte[b.weapons];
        b.totals = new byte[b.weapons];
        return b;
    }

    static Body missile() {
        Body b = new Body();
        b.id = nextId++;
        b.kind = 2;
        b.x = range(-6000, 6000); b.y = range(-6000, 6000);
        b.facing = range(0, 360);
        float s = range(50, 150);
        b.vx = (float) Math.cos(Math.toRadians(b.facing)) * s; b.vy = (float) Math.sin(Math.toRadians(b.facing)) * s;
        b.maxSpeed = range(350, 800); b.accel = range(400, 1000); b.turn = range(60, 180); b.turnAccel = b.turn * 6f;
        b.guided = rnd.nextFloat() < 0.65f;
        b.life = range(2.5f, 7f);
        b.tx = b.x + range(-2000, 2000); b.ty = b.y + range(-2000, 2000);
        return b;
    }

    static float turnTo(float from, float to) {
        float d = (to - from) % 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    static void hostStep(Body b, float t) {
        if (b.kind == 2) {
            b.flight += DT;
            float want = b.guided ? (float) Math.toDegrees(Math.atan2(b.ty - b.y, b.tx - b.x)) : b.facing;
            if (b.guided) { b.tx += (float) Math.sin(t * 0.7 + b.id) * 120f * DT; b.ty += (float) Math.cos(t * 0.5 + b.id) * 120f * DT; }
            steer(b, want);
            float c = (float) Math.cos(Math.toRadians(b.facing)), s = (float) Math.sin(Math.toRadians(b.facing));
            b.vx += c * b.accel * DT; b.vy += s * b.accel * DT;
            float sp = (float) Math.hypot(b.vx, b.vy);
            if (sp > b.maxSpeed) { b.vx *= b.maxSpeed / sp; b.vy *= b.maxSpeed / sp; }
        } else {
            if (t >= b.nextChoice) {
                b.nextChoice = t + (b.kind == 0 ? range(1f, 4f) : range(0.3f, 1.2f));
                float dir = range(0, 360), sp = b.maxSpeed * (rnd.nextFloat() < 0.15f ? 0f : range(0.4f, 1f));
                b.wantVx = (float) Math.cos(Math.toRadians(dir)) * sp; b.wantVy = (float) Math.sin(Math.toRadians(dir)) * sp;
                b.wantFacing = b.kind == 1 ? dir : dir + range(-60, 60);
                b.shield = b.kind == 0 && rnd.nextFloat() < 0.6f;
            }
            float dvx = b.wantVx - b.vx, dvy = b.wantVy - b.vy, d = (float) Math.hypot(dvx, dvy), a = b.accel * DT;
            if (d > a) { dvx *= a / d; dvy *= a / d; }
            b.vx += dvx; b.vy += dvy;
            steer(b, b.wantFacing);
            // flux, shield, weapons
            float gain = (b.firing != 0 ? 0.04f : -0.03f) * b.maxFlux * DT * 4f;
            b.flux = Math.max(0f, Math.min(b.maxFlux, b.flux + gain + (rnd.nextFloat() - 0.5f) * 0.01f * b.maxFlux));
            b.hardFlux = Math.min(b.flux, b.hardFlux + (b.shield && rnd.nextFloat() < 0.02f ? 0.02f * b.maxFlux : -0.005f * b.maxFlux));
            if (b.hardFlux < 0f) b.hardFlux = 0f;
            if (b.shield) b.shieldFacing += turnTo(b.shieldFacing, b.facing + 30f) * Math.min(1f, DT * 3f);
            for (int i = 0; i < b.weapons && i < 64; i++) {
                if (rnd.nextFloat() < 0.01f) b.firing ^= 1L << i;
                if ((b.firing & (1L << i)) != 0 && rnd.nextFloat() < 0.05f) b.totals[i]++;
                if (rnd.nextFloat() < 0.05f) b.angles[i] += (byte) (rnd.nextBoolean() ? 1 : -1);
            }
            if (rnd.nextFloat() < 0.003f) b.hull = Math.max(0.05f, b.hull - 0.01f);
        }
        b.x += b.vx * DT; b.y += b.vy * DT;
        b.facing += b.angVel * DT;
    }

    static void steer(Body b, float want) {
        float d = turnTo(b.facing, want);
        float wantW = Math.max(-b.turn, Math.min(b.turn, d * 3f));
        float dw = wantW - b.angVel, a = b.turnAccel * DT;
        b.angVel += Math.max(-a, Math.min(a, dw));
    }

    static Snapshot capture(int tick, float time, int kinds) {
        Snapshot s = new Snapshot();
        s.tick = tick;
        s.hostTime = time;
        s.piloted = new int[0];
        for (Body b : bodies) {
            if ((kinds & (1 << b.kind)) == 0) continue;
            if (b.kind == 2) {
                Snapshot.MissileState m = new Snapshot.MissileState();
                m.id = b.id; m.x = b.x; m.y = b.y; m.vx = b.vx; m.vy = b.vy; m.facing = b.facing; m.angVel = b.angVel; m.flightTime = b.flight;
                s.missiles.add(m);
            } else {
                Snapshot.ShipState st = new Snapshot.ShipState();
                st.netId = b.id; st.x = b.x; st.y = b.y; st.vx = b.vx; st.vy = b.vy; st.facing = b.facing; st.angVel = b.angVel;
                st.hull = b.hull; st.cr = b.cr; st.flux = b.flux; st.hardFlux = b.hardFlux;
                st.flags = b.shield ? Snapshot.F_DEFENSE : 0; st.shieldFacing = b.shieldFacing; st.firing = b.firing;
                st.weaponAngles = b.angles.clone(); st.shotTotals = b.totals.clone();
                st.fluxStep = Math.max(5f, b.maxFlux * 0.01f);
                s.ships.add(st);
            }
        }
        return s;
    }

    // ---- The joiner ----

    static float closing(float err, float tol, float amount, float rate) {
        if (err <= 0.001f) return 0f;
        float fade = Math.min(1f, amount);
        if (err <= tol) return fade;
        return Math.min(1f, fade + Math.min(1f, amount * rate) * ((err - tol) / err));
    }

    static void joinerStep(Body b, float clock) {
        if (b.kind == 2 ? b.ms == null : b.st == null) return;
        float amount = DT;
        if (mode > 0) {
            if (!b.shown) { b.jx = b.kind == 2 ? b.ms.x : b.st.x; b.jy = b.kind == 2 ? b.ms.y : b.st.y; b.shown = true; }
            b.jx += b.jvx * DT; b.jy += b.jvy * DT; // the game moves it by its speed this frame
            boolean buffered = mode == 2 && b.kind != 2; // as on the joiner: missiles are never delayed
            float r = buffered ? bufShowing : clock - hostNowGap;
            b.tr.sample(r, buffered, b.kind == 2 ? 2f : 1f, useAcc ? 0.25f : 0f, false);
            b.tr.follow(b.jx, b.jy, b.tr.mf, amount, false);
            b.jx = b.tr.sx; b.jy = b.tr.sy; b.jvx = b.tr.mvx; b.jvy = b.tr.mvy;
            return;
        }
        if (b.kind == 2) {
            Snapshot.MissileState t = b.ms;
            float age = Math.max(0f, Math.min(2f, clock - b.targetAt));
            if (!b.shown) { b.jx = t.x; b.jy = t.y; b.shown = true; }
            b.jx += b.jvx * DT; b.jy += b.jvy * DT; // the game moves it by its speed this frame
            float aa = useAcc ? Math.min(age, 0.25f) : 0f;
            float tx = t.x + (t.vx + 0.5f * t.ax * aa) * age, ty = t.y + (t.vy + 0.5f * t.ay * aa) * age;
            float dx = tx - b.jx, dy = ty - b.jy, err = (float) Math.hypot(dx, dy), speed = (float) Math.hypot(t.vx, t.vy);
            float k = err > 300f ? 1f : closing(err, 6f + speed * 0.06f, amount, 12f);
            b.jx += dx * k; b.jy += dy * k;
            b.jvx = t.vx + t.ax * aa; b.jvy = t.vy + t.ay * aa;
        } else {
            Snapshot.ShipState t = b.st;
            float age = Math.max(0f, Math.min(1f, clock - b.targetAt));
            if (!b.shown) { b.jx = t.x; b.jy = t.y; b.jvx = t.vx; b.jvy = t.vy; b.shown = true; }
            b.jx += b.jvx * DT; b.jy += b.jvy * DT;
            float aa = useAcc ? Math.min(age, 0.25f) : 0f;
            float tx = t.x + (t.vx + 0.5f * t.ax * aa) * age, ty = t.y + (t.vy + 0.5f * t.ay * aa) * age;
            float dx = tx - b.jx, dy = ty - b.jy, err = (float) Math.hypot(dx, dy), speed = (float) Math.hypot(t.vx, t.vy);
            float tol = b.kind == 1 ? 5f + speed * 0.06f : 3f + speed * 0.05f;
            float k = err > 500f ? 1f : closing(err, tol, amount, b.kind == 1 ? 14f : 10f);
            b.jx += dx * k; b.jy += dy * k;
            float vb = Math.min(1f, amount * 25f);
            b.jvx += (t.vx + t.ax * aa - b.jvx) * vb; b.jvy += (t.vy + t.ay * aa - b.jvy) * vb;
        }
    }

    // ---- Measuring ----

    static final class Stat {
        double errSum, jerkSq; long n, jn; float[] errs = new float[1 << 20]; int en;
        void err(float e) { errSum += e; n++; if (en < errs.length) errs[en++] = e; }
        String line(String name) {
            float[] a = Arrays.copyOf(errs, en); Arrays.sort(a);
            float p95 = a.length == 0 ? 0f : a[(int) (a.length * 0.95)];
            return String.format("%-9s off by %5.1f avg, %6.1f p95 units   jitter %6.0f u/s^2", name, n == 0 ? 0 : errSum / n, p95,
                    jn == 0 ? 0 : Math.sqrt(jerkSq / jn));
        }
    }

    public static void main(String[] a) throws Exception {
        Map<String, String> o = new HashMap<String, String>();
        for (String s : a) { int i = s.indexOf('='); if (i > 0) o.put(s.substring(0, i), s.substring(i + 1)); }
        int rate = Integer.parseInt(o.getOrDefault("rate", "60"));
        float seconds = Float.parseFloat(o.getOrDefault("seconds", "60"));
        float delay = Float.parseFloat(o.getOrDefault("delayMs", "50")) / 1000f;
        float jitter = Float.parseFloat(o.getOrDefault("jitterMs", "20")) / 1000f;
        int nShips = Integer.parseInt(o.getOrDefault("ships", "40")), nFighters = Integer.parseInt(o.getOrDefault("fighters", "80"));
        int nMissiles = Integer.parseInt(o.getOrDefault("missiles", "200"));
        useAcc = !"0".equals(o.getOrDefault("accel", "1"));
        String modeName = o.getOrDefault("mode", "carry");
        mode = modeName.equals("buffer") ? 2 : modeName.equals("smooth") ? 1 : 0;
        Smoother.Delay delayCalc = new Smoother.Delay();
        Budget.bytesPerSecond = Integer.parseInt(o.getOrDefault("limit", "0")) * 1024;
        Budget budget = new Budget();
        budget.hasFocus = true;
        float near = Float.parseFloat(o.getOrDefault("near", "0"));
        double delaySum = 0; long delayN = 0;
        rnd = new Random(Long.parseLong(o.getOrDefault("seed", "1")));
        for (int i = 0; i < nShips; i++) bodies.add(ship(0));
        for (int i = 0; i < nFighters; i++) bodies.add(ship(1));
        for (int i = 0; i < nMissiles; i++) { Body m = missile(); m.flight = range(0, m.life); bodies.add(m); }

        SnapshotCodec.Encoder enc = new SnapshotCodec.Encoder();
        SnapshotCodec.Decoder dec = new SnapshotCodec.Decoder();
        SnapshotCodec.Encoder[] byKind = { new SnapshotCodec.Encoder(), new SnapshotCodec.Encoder(), new SnapshotCodec.Encoder() };
        Deflater zip = new Deflater(Deflater.BEST_SPEED, true);
        byte[] zout = new byte[1 << 20];
        long raw = 0, tcp = 0, udp = 0; long[] rawKind = new long[3];
        int updates = 0, lastTick = -1;
        int[] lastKindTick = { -1, -1, -1 };
        // in flight to the joiner: arrival time, update
        ArrayDeque<Object[]> wire = new ArrayDeque<Object[]>(); // in order, as TCP delivers
        float lastArrival = 0f;
        float[] recentLate = new float[8]; int lateN = 0;
        Stat[] stats = { new Stat(), new Stat(), new Stat() };
        Map<Integer, Body> byId = new HashMap<Integer, Body>();
        for (Body b : bodies) byId.put(b.id, b);

        int frames = Math.round(seconds * 60f), tick = 0;
        float nextUpdate = 0f, warm = 2f;
        for (int f = 0; f < frames; f++) {
            float t = f * DT;
            // host frame
            for (int i = 0; i < bodies.size(); i++) {
                Body b = bodies.get(i);
                hostStep(b, t);
                b.hx[f & 255] = b.x; b.hy[f & 255] = b.y;
                if (b.kind == 2 && b.flight > b.life) { // a missile ends; a new one is launched
                    Body m = missile(); bodies.set(i, m); byId.remove(b.id); byId.put(m.id, m);
                    m.hx[f & 255] = m.x; m.hy[f & 255] = m.y; m.frames = 0;
                }
                bodies.get(i).frames++;
            }
            if (t + 1e-6f >= nextUpdate) {
                nextUpdate += 1f / rate;
                Snapshot s = capture(tick, t, 7);
                byte[] data = budget.fit(s, enc.encode(s, lastTick), enc, lastTick, 1f / rate);
                lastTick = tick;
                for (int k = 0; k < 3; k++) {
                    byte[] part = byKind[k].encode(capture(tick, t, 1 << k), lastKindTick[k]);
                    lastKindTick[k] = tick;
                    if (t >= warm) rawKind[k] += part.length;
                }
                tick++;
                if (t >= warm) {
                    updates++;
                    raw += data.length;
                    int pieces = Math.max(1, (data.length + 1099) / 1100);
                    udp += data.length + pieces * (9 + 6 + 28);
                    zip.setInput(data);
                    int n = zip.deflate(zout, 0, zout.length, Deflater.SYNC_FLUSH);
                    tcp += n + 5 + 0; // frame header before compression is tiny; counted roughly
                }
                float arrive = t + delay + rnd.nextFloat() * jitter;
                if (arrive < lastArrival) arrive = lastArrival; // TCP: in order
                lastArrival = arrive;
                wire.add(new Object[] { arrive, data });
            }
            // joiner frame (half a frame out of step with the host)
            float jt = t + DT * 0.5f;
            while (!wire.isEmpty() && (Float) wire.peekFirst()[0] <= jt) {
                Object[] e = wire.pollFirst();
                Snapshot s = dec.decode((byte[]) e[1]);
                if (s == null) { System.out.println("update not read: " + dec.result); continue; }
                // lateness against the quickest of the last eight, as the joiner works it out
                float late = (Float) e[0] - s.hostTime;
                recentLate[lateN++ & 7] = late;
                float quickest = Float.MAX_VALUE;
                for (int i = 0; i < Math.min(lateN, 8); i++) quickest = Math.min(quickest, recentLate[i]);
                float stamp = jt - Math.min(0.1f, late - quickest);
                hostNowGap = quickest;
                delayCalc.update(jt, s.hostTime);
                for (Snapshot.ShipState st : s.ships) { Body b = byId.get(st.netId); if (b != null) { b.st = st; b.targetAt = stamp;
                    b.tr.push(s.hostTime, st.x, st.y, st.vx, st.vy, st.ax, st.ay, st.facing, st.angVel, st.angAcc); } }
                for (Snapshot.MissileState ms : s.missiles) { Body b = byId.get(ms.id); if (b != null) { b.ms = ms; b.targetAt = stamp;
                    b.tr.push(s.hostTime, ms.x, ms.y, ms.vx, ms.vy, ms.ax, ms.ay, ms.facing, ms.angVel, 0f); } }
            }
            delayCalc.advance(jt);
            bufDelay = delayCalc.delay;
            bufShowing = delayCalc.showing(jt);
            if (t >= warm) { delaySum += bufDelay; delayN++; }
            for (Body b : bodies) {
                joinerStep(b, jt);
                if (!b.shown || t < warm || b.frames < 12 + (delay + bufDelay) / DT) continue; // (the host's past must be its own)
                if (near > 0f && Math.hypot(b.x, b.y) > near) continue;
                // where the host had it one (shortest) delay ago: what a perfect link shows
                // (buffer mode: exactly the host moment the joiner means to show)
                float back = mode == 2 && b.kind != 2 ? (t - bufShowing) / DT : (delay - DT * 0.5f) / DT;
                int whole = (int) Math.floor(back);
                float fr = back - whole;
                int i0 = (f - whole) & 255, i1 = (f - whole - 1) & 255;
                float rx = b.hx[i0] + (b.hx[i1] - b.hx[i0]) * fr, ry = b.hy[i0] + (b.hy[i1] - b.hy[i0]) * fr;
                Stat st = stats[b.kind];
                st.err((float) Math.hypot(b.jx - rx, b.jy - ry));
                if (b.frames > 15) {
                    float ax = (b.jx - 2 * b.px1 + b.px2) - (rx - 2 * b.rx1 + b.rx2), ay = (b.jy - 2 * b.py1 + b.py2) - (ry - 2 * b.ry1 + b.ry2);
                    st.jerkSq += (ax * ax + ay * ay) / (DT * DT * DT * DT);
                    st.jn++;
                }
                b.px2 = b.px1; b.py2 = b.py1; b.px1 = b.jx; b.py1 = b.jy;
                b.rx2 = b.rx1; b.ry2 = b.ry1; b.rx1 = rx; b.ry1 = ry;
            }
        }
        float secs = seconds - warm;
        System.out.println(String.format("rate %d/s, %d ships, %d fighters, %d missiles, delay %.0f ms + up to %.0f ms jitter",
                rate, nShips, nFighters, nMissiles, delay * 1000, jitter * 1000));
        System.out.println(String.format("bandwidth per joiner: TCP %.1f KB/s compressed, UDP %.1f KB/s (raw %.1f KB/s; %.0f bytes an update)",
                tcp / 1024f / secs, udp / 1024f / secs, raw / 1024f / secs, raw / (float) updates));
        System.out.println(String.format("raw by kind: ships %.1f, fighters %.1f, missiles %.1f KB/s",
                rawKind[0] / 1024f / secs, rawKind[1] / 1024f / secs, rawKind[2] / 1024f / secs));
        if (Budget.bytesPerSecond > 0) System.out.println("limit " + Budget.bytesPerSecond / 1024 + " KB/s: " + budget.cut + " updates cut down, " + budget.leftOut + " states left out");
        System.out.println("joiner mode: " + modeName + (mode == 2 ? String.format(" (copies shown %.0f ms late on average, on top of the link's delay)", 1000 * delaySum / Math.max(1, delayN)) : ""));
        System.out.println(stats[0].line("ships"));
        System.out.println(stats[1].line("fighters"));
        System.out.println(stats[2].line("missiles"));
    }
}

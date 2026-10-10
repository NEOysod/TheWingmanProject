package wingman.net;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Position updates, sent as what has changed since the update before.
 *
 * A ship or missile changes little from one update to the next: a few units of position, a degree of heading,
 * nothing at all of most of the rest. So each value is turned into a whole number (position in sixteenths of
 * a unit, the others in the steps they always travelled in) and only its difference from the previous
 * update's number is sent, in as few bytes as that difference needs; values that have not changed are not
 * sent at all. Both ends keep the previous update's numbers and do the same arithmetic on them, so nothing
 * drifts: what the joiner rebuilds is exactly the host's number.
 *
 * Better still, most of what does change could have been guessed: a missile flying straight is where its speed
 * says it would be, a ship turning steadily has turned that much, a flight clock has run on. Both ends make
 * that same guess from the previous update's numbers, and only what differs from the guess is sent. The guess
 * goes on at the rate the speeds were changing (both ends work that out from the speeds they hold), so a
 * missile speeding up or a fighter in a steady turn is guessed right too. The host also lets a value sit a hair
 * off the truth (half a unit of position, a hundredth of a flux bar) rather than send it; it keeps count of what
 * the joiner actually holds, so those hairs never add up. Something whose every value is where the guess puts
 * it is left out of the update altogether, and both ends carry it on by the guess (for at most CARRY updates
 * in a row). The joiner's game is handed every ship and missile it holds, sent or carried.
 *
 * An update is built on an earlier one, and can only be read by someone who holds that one. Both ends keep the
 * last second's worth. Over a link that delivers everything in order (TCP) each update is built on the one
 * before. Over one that may lose updates (UDP) the joiner keeps saying which update it last read, and the
 * host builds each new one on that: a lost update then costs nothing but itself. There is one packer per
 * player, since each may be building on something different. A "key" update is measured against nothing
 * (every value in full) and can be read by anyone: sent first, and whenever a player has nothing to build on.
 *
 * Things that happen once (a weapon firing a shot) would be lost with a lost update, so they travel as running
 * totals: the joiner takes the difference from the last update it actually read.
 */
public final class SnapshotCodec {
    private SnapshotCodec() {}

    private static final int SHIP_FIELDS = 17, MISSILE_FIELDS = 8;
    /**
     * Past the sent fields, each remembered state also holds how fast its speeds were changing (acceleration and
     * turn acceleration, in the speed field's steps per second). Never sent: both ends work it out from the
     * speeds they hold, the same way, so a thing that keeps speeding up or turning is guessed right and costs
     * nothing until it changes what it is doing.
     */
    private static final int ACC = 3;
    /**
     * And how many updates in a row it has been left out (QUIET). Something whose every value is where the guess
     * puts it is not sent at all: both ends carry it on by the guess. At most CARRY updates in a row; after that
     * it is sent again, and anything not sent for longer (gone, or out of sight) is forgotten by both.
     */
    private static final int SHIP_Q = SHIP_FIELDS + ACC, MISSILE_Q = MISSILE_FIELDS + ACC;
    private static final int SHIP_ALL = SHIP_Q + 1, MISSILE_ALL = MISSILE_Q + 1;
    static final int CARRY = 30;
    /** More ships (or missiles) than any battle has, counting those carried on: the update is refused. */
    private static final int MAX_HELD = 30000;
    private static final int[] SHIP_SPEEDS = { 4, 5, 6 }, MISSILE_SPEEDS = { 3, 4, 6 };
    /** Updates further apart than this (UDP, built on an old one) are guessed without acceleration. */
    private static final float ACC_SPAN = 0.25f;
    private static final int ACC_MAX = 1 << 20;
    private static final int[] ZERO_SHIP = new int[SHIP_ALL], ZERO_MISSILE = new int[MISSILE_ALL];
    /** Fields that are sets of bits: their difference is which bits flipped, not a subtraction. */
    private static final int SHIP_BITS = (1 << 0) | (1 << 10) | (1 << 11) | (1 << 14), MISSILE_BITS = 1;
    private static final float POS = 16f;

    private static int zig(int v) { return (v << 1) ^ (v >> 31); }
    private static int zag(int v) { return (v >>> 1) ^ -(v & 1); }

    /** A state with impossible coordinates is left out; the joiner's copy keeps its last good state. */
    private static boolean ok(float x, float y) { return !Snapshot.bad(x) && !Snapshot.bad(y); }

    private static int pos(float v) {
        if (v != v) return 0;
        return Math.round(Math.max(-1.0e7f, Math.min(1.0e7f, v)) * POS);
    }

    private static void shipFields(Snapshot.ShipState s, int[] f) {
        f[0] = s.flags & 0x1F;
        f[1] = pos(s.x);
        f[2] = pos(s.y);
        f[3] = Snapshot.packFacing(s.facing);
        f[4] = Snapshot.packSpeed(s.vx, Snapshot.SPEED);
        f[5] = Snapshot.packSpeed(s.vy, Snapshot.SPEED);
        f[6] = Snapshot.packSpeed(s.angVel, Snapshot.TURN);
        f[7] = Math.round(Snapshot.clamp(s.flux, 0f, 1.0e7f));
        f[8] = Math.round(Snapshot.clamp(s.hardFlux, 0f, 1.0e7f));
        f[9] = (s.flags & Snapshot.F_DEFENSE) != 0 ? Snapshot.packAngle(s.shieldFacing) & 0xFF : 0;
        f[10] = (int) s.firing;
        f[11] = (int) (s.firing >>> 32);
        f[12] = Math.round(Snapshot.clamp(s.hull, 0f, 1f) * 65535f);
        f[13] = Math.round(Snapshot.clamp(s.cr, 0f, 1f) * 200f);
        f[14] = (s.system & 7) | ((s.cloak & 7) << 3);
        f[15] = Math.round(Snapshot.clamp(s.systemLevel, 0f, 1f) * 255f);
        f[16] = Math.round(Snapshot.clamp(s.cloakLevel, 0f, 1f) * 255f);
    }

    private static void shipFrom(int[] f, Snapshot.ShipState s) {
        s.flags = f[0] & 0x1F;
        s.x = f[1] / POS;
        s.y = f[2] / POS;
        s.facing = Snapshot.unpackFacing(f[3]);
        s.vx = f[4] / Snapshot.SPEED;
        s.vy = f[5] / Snapshot.SPEED;
        s.angVel = f[6] / Snapshot.TURN;
        s.flux = Snapshot.clamp(f[7], 0f, 1.0e7f);
        s.hardFlux = Snapshot.clamp(f[8], 0f, s.flux);
        s.shieldFacing = Snapshot.unpackAngle((byte) f[9]);
        s.firing = (f[10] & 0xFFFFFFFFL) | ((long) f[11] << 32);
        s.hull = Snapshot.clamp(f[12] / 65535f, 0f, 1f);
        s.cr = Snapshot.clamp(f[13] / 200f, 0f, 1f);
        s.system = Math.min(4, f[14] & 7);
        s.cloak = Math.min(4, (f[14] >> 3) & 7);
        s.systemLevel = Snapshot.clamp(f[15] / 255f, 0f, 1f);
        s.cloakLevel = Snapshot.clamp(f[16] / 255f, 0f, 1f);
        if (f.length >= SHIP_ALL) {
            s.ax = f[SHIP_FIELDS] / Snapshot.SPEED;
            s.ay = f[SHIP_FIELDS + 1] / Snapshot.SPEED;
            s.angAcc = f[SHIP_FIELDS + 2] / Snapshot.TURN;
        }
    }

    private static void missileFields(Snapshot.MissileState m, int[] f) {
        f[0] = m.flags & Snapshot.M_FIZZLING;
        f[1] = pos(m.x);
        f[2] = pos(m.y);
        f[3] = Snapshot.packSpeed(m.vx, Snapshot.SPEED);
        f[4] = Snapshot.packSpeed(m.vy, Snapshot.SPEED);
        f[5] = Snapshot.packAngle(m.facing) & 0xFF;
        f[6] = Snapshot.packSpeed(m.angVel, Snapshot.TURN);
        f[7] = Math.round(Snapshot.clamp(m.flightTime, 0f, 1300f) * 50f);
    }

    private static void missileFrom(int[] f, Snapshot.MissileState m) {
        m.flags = f[0] & Snapshot.M_FIZZLING;
        m.x = f[1] / POS;
        m.y = f[2] / POS;
        m.vx = f[3] / Snapshot.SPEED;
        m.vy = f[4] / Snapshot.SPEED;
        m.facing = Snapshot.unpackAngle((byte) f[5]);
        m.angVel = f[6] / Snapshot.TURN;
        m.flightTime = (f[7] & 0xFFFF) / 50f;
        if (f.length >= MISSILE_ALL) {
            m.ax = f[MISSILE_FIELDS] / Snapshot.SPEED;
            m.ay = f[MISSILE_FIELDS + 1] / Snapshot.SPEED;
            m.angAcc = f[MISSILE_FIELDS + 2] / Snapshot.TURN;
        }
    }

    /** Fields that go round in a circle: heading in 65536 steps (ships), in 256 (missiles). */
    private static final int SHIP_WRAP = 3, SHIP_WRAP_MASK = 0xFFFF, MISSILE_WRAP = 5, MISSILE_WRAP_MASK = 0xFF;
    /** How far a value may sit from the truth before it is worth sending (in each field's own steps). Flux has its own rule. */
    private static final int[] SHIP_BAND = { 0, 8, 8, 16, 4, 4, 3, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0 };
    private static final int[] MISSILE_BAND = { 0, 8, 8, 4, 4, 1, 3, 2 };

    /** The shortest way round from one heading to another, for a field that wraps. */
    private static int around(int diff, int wrapMask) {
        return wrapMask == 0xFFFF ? (short) diff : (byte) diff;
    }

    /** Where a ship's numbers would be by now if it had simply carried on: moved by its speed, turned by its turn rate. */
    private static void guessShip(int[] base, float dt, int[] g) {
        System.arraycopy(base, 0, g, 0, SHIP_ALL);
        if (!(dt > 0f)) return;
        float da = dt <= ACC_SPAN ? dt : 0f;
        int ax = base[SHIP_FIELDS], ay = base[SHIP_FIELDS + 1], aw = base[SHIP_FIELDS + 2];
        // speed is in eighths of a unit a second, position in sixteenths; carried on at its acceleration
        g[1] = base[1] + Math.round((base[4] + 0.5f * ax * da) * 2f * dt);
        g[2] = base[2] + Math.round((base[5] + 0.5f * ay * da) * 2f * dt);
        g[3] = (base[3] + Math.round((base[6] + 0.5f * aw * da) * dt * 18.204445f)) & 0xFFFF; // tenths of a degree a second into 65536ths of a turn
        g[4] = base[4] + Math.round(ax * da);
        g[5] = base[5] + Math.round(ay * da);
        g[6] = base[6] + Math.round(aw * da);
    }

    private static void guessMissile(int[] base, float dt, int[] g) {
        System.arraycopy(base, 0, g, 0, MISSILE_ALL);
        if (!(dt > 0f)) return;
        float da = dt <= ACC_SPAN ? dt : 0f;
        int ax = base[MISSILE_FIELDS], ay = base[MISSILE_FIELDS + 1], aw = base[MISSILE_FIELDS + 2];
        g[1] = base[1] + Math.round((base[3] + 0.5f * ax * da) * 2f * dt);
        g[2] = base[2] + Math.round((base[4] + 0.5f * ay * da) * 2f * dt);
        g[5] = (base[5] + Math.round((base[6] + 0.5f * aw * da) * dt * 0.07111111f)) & 0xFF;
        g[3] = base[3] + Math.round(ax * da);
        g[4] = base[4] + Math.round(ay * da);
        g[6] = base[6] + Math.round(aw * da);
        g[7] = base[7] + Math.round(dt * 50f);                   // its flight clock runs on
    }

    /**
     * After an update: how fast the speeds are changing, from the speeds before and after (half the new
     * measure, half the old, so one odd change doesn't throw the guesses off). Zero for something new, or
     * after a gap too long to tell.
     */
    private static void settle(int[] now, int[] base, float dt, int fields, int[] speeds) {
        for (int k = 0; k < ACC; k++) {
            int a = 0;
            if (base != null && dt > 0f && dt <= ACC_SPAN) {
                int measured = Math.round((now[speeds[k]] - base[speeds[k]]) / dt);
                a = (base[fields + k] + measured) / 2;
                if (a > ACC_MAX) a = ACC_MAX; else if (a < -ACC_MAX) a = -ACC_MAX;
            }
            now[fields + k] = a;
        }
    }

    /** The time between two updates as both ends work it out; 0 (no guessing) if it is not a sensible step forward. */
    private static float step(float from, float to) {
        float dt = to - from;
        return dt > 0f && dt <= 1f ? dt : 0f;
    }

    private static void writeFields(Out out, int[] cur, int[] from, int n, int bits, int wrap, int wrapMask) throws IOException {
        int mask = 0;
        for (int i = 0; i < n; i++) if (cur[i] != from[i]) mask |= 1 << i;
        out.writeVarInt(mask);
        for (int i = 0; i < n; i++) {
            if ((mask & (1 << i)) == 0) continue;
            out.writeVarInt((bits & (1 << i)) != 0 ? cur[i] ^ from[i] : i == wrap ? zig(around(cur[i] - from[i], wrapMask)) : zig(cur[i] - from[i]));
        }
    }

    /** Reads into 'into' (which may be the remembered numbers themselves); 'from' is the guess, or zeros. */
    private static void readFields(In in, int[] into, int[] from, int n, int bits, int wrap, int wrapMask) throws IOException {
        int mask = in.readVarInt();
        if ((mask >>> n) != 0) throw new IOException("unknown field in update");
        for (int i = 0; i < n; i++) {
            if ((mask & (1 << i)) == 0) { into[i] = from[i]; continue; }
            int v = in.readVarInt();
            into[i] = (bits & (1 << i)) != 0 ? from[i] ^ v : i == wrap ? (from[i] + zag(v)) & wrapMask : from[i] + zag(v);
        }
    }

    private static void writeHead(Out out, Snapshot s) throws IOException {
        out.writeBoolean(s.paused);
        out.writeVarInt(s.hostFlagship + 1);
        out.writeFloat(s.timeMult);
        out.writeFloat(Snapshot.bad(s.hostTime) ? 0f : s.hostTime);
        out.writeByte(s.piloted.length);
        for (int i = 0; i < s.piloted.length; i++) {
            out.writeVarInt(s.piloted[i]);
            out.writeShort(i < s.pilotedSeq.length ? s.pilotedSeq[i] : 0);
            float age = i < s.pilotedAge.length ? s.pilotedAge[i] : 2.55f;
            out.writeByte(Math.round(Snapshot.clamp(age, 0f, 2.55f) * 100f));
            out.writeByte(i < s.pilotedSysAmmo.length ? Math.max(0, Math.min(255, s.pilotedSysAmmo[i])) : 255);
        }
    }

    private static void readHead(In in, Snapshot snap) throws IOException {
        snap.paused = in.readBoolean();
        snap.hostFlagship = in.readVarInt() - 1;
        snap.timeMult = Snapshot.clamp(in.readFloat(), 0.05f, 20f);
        snap.hostTime = in.readFloat();
        if (Snapshot.bad(snap.hostTime)) snap.hostTime = 0f;
        snap.piloted = new int[in.readByte() & 0xFF];
        snap.pilotedSeq = new int[snap.piloted.length];
        snap.pilotedAge = new float[snap.piloted.length];
        snap.pilotedSysAmmo = new int[snap.piloted.length];
        for (int i = 0; i < snap.piloted.length; i++) {
            snap.piloted[i] = in.readVarInt();
            snap.pilotedSeq[i] = in.readShort() & 0xFFFF;
            snap.pilotedAge[i] = (in.readByte() & 0xFF) / 100f;
            snap.pilotedSysAmmo[i] = in.readByte() & 0xFF;
        }
    }

    /** What one ship stands at after an update: its numbers, its turret angles, its running shot totals. */
    private static final class Rec {
        int[] f;
        byte[] angles, totals;
    }

    /** Everything as it stood after one update, as both ends hold it. Never changed once made. */
    private static final class State {
        int tick, epoch;
        float time;
        final Map<Integer, Rec> ships = new HashMap<Integer, Rec>();
        final Map<Integer, int[]> missiles = new HashMap<Integer, int[]>();
    }

    /** How many updates back both ends remember (about a second's worth at 60 a second). */
    public static final int HISTORY = 64;

    /**
     * A list of bytes (turret angles, shot totals) against what it was: nothing if unchanged, else which
     * entries changed and their new values.
     */
    private static void writeBytes(Out out, byte[] cur, int count, byte[] was) throws IOException {
        if (was != null && was.length != count) was = null;
        int head = Math.min(count, 64);
        long moved = 0L;
        for (int i = 0; i < head; i++) if (was == null || was[i] != cur[i]) moved |= 1L << i;
        boolean tail = false;
        for (int i = 64; i < count && !tail; i++) if (was == null || was[i] != cur[i]) tail = true;
        if (was != null && moved == 0L && !tail) { out.writeByte(0); return; }
        out.writeByte(1);
        out.writeByte(count);
        out.writeVarLong(moved);
        for (int i = 0; i < head; i++) if ((moved & (1L << i)) != 0) out.writeByte(cur[i]);
        if (count > 64) out.write(cur, 64, count - 64);
    }

    /** Returns the list as it now stands: 'was' itself if there is no news, otherwise a new array. */
    private static byte[] readBytes(In in, byte[] was) throws IOException {
        int news = in.readByte();
        if (news == 0) return was;
        if (news != 1) throw new IOException("bad list in update");
        int count = in.readByte() & 0xFF;
        byte[] now = new byte[count];
        if (was != null && was.length == count) System.arraycopy(was, 0, now, 0, count);
        int head = Math.min(count, 64);
        long moved = in.readVarLong();
        for (int i = 0; i < head; i++) if ((moved & (1L << i)) != 0) now[i] = in.readByte();
        for (int i = 64; i < count; i++) now[i] = in.readByte();
        return now;
    }

    /** Carry a ship left out of this update on by the guess; null once it has been left out too long. */
    private static Rec carryShip(Rec was, float dt, int[] g) {
        if (was.f[SHIP_Q] >= CARRY) return null;
        guessShip(was.f, dt, g);
        Rec r = new Rec();
        r.f = new int[SHIP_ALL];
        System.arraycopy(g, 0, r.f, 0, SHIP_ALL);
        settle(r.f, was.f, dt, SHIP_FIELDS, SHIP_SPEEDS);
        r.f[SHIP_Q] = was.f[SHIP_Q] + 1;
        r.angles = was.angles;
        r.totals = was.totals;
        return r;
    }

    private static int[] carryMissile(int[] was, float dt, int[] g) {
        if (was[MISSILE_Q] >= CARRY) return null;
        guessMissile(was, dt, g);
        int[] r = new int[MISSILE_ALL];
        System.arraycopy(g, 0, r, 0, MISSILE_ALL);
        settle(r, was, dt, MISSILE_FIELDS, MISSILE_SPEEDS);
        r[MISSILE_Q] = was[MISSILE_Q] + 1;
        return r;
    }

    private static boolean sameBytes(byte[] cur, int most, byte[] was) {
        if (cur == null) return true;
        int count = Math.min(cur.length, most);
        if (was == null || was.length != count) return false;
        for (int i = 0; i < count; i++) if (was[i] != cur[i]) return false;
        return true;
    }

    private static boolean same(int[] a, int[] b, int n) {
        for (int i = 0; i < n; i++) if (a[i] != b[i]) return false;
        return true;
    }

    /** Host side. One per player per battle. */
    public static final class Encoder {
        private final State[] ring = new State[HISTORY];
        private final int epoch = (int) System.nanoTime() ^ (int) (Math.random() * Integer.MAX_VALUE);
        private final int[] cur = new int[SHIP_ALL], guess = new int[SHIP_ALL], rec = new int[SHIP_ALL];
        /** True if the update just packed was one in full. */
        public boolean lastKey;
        /** Values left a hair off the truth instead of being sent, and values sent, since the battle began. */
        public long fieldsHeld, fieldsSent;
        /** Ships and missiles left out of updates because the guess was right. */
        public long carried;

        public int epoch() { return epoch; }

        private State find(int tick) {
            State s = tick < 0 ? null : ring[tick & (HISTORY - 1)];
            return s != null && s.tick == tick ? s : null;
        }

        /** True if an update with this number is still remembered, so a new one can be built on it. */
        public boolean has(int tick) { return find(tick) != null; }

        /**
         * Pack an update for a player who holds update number 'builtOn' (-1, or one no longer remembered: in
         * full). For each ship and missile: guess where it would be, decide value by value whether the truth
         * is near enough to the guess to leave unsent, and remember what results (the guess or the truth),
         * which is exactly what the joiner will hold after reading this.
         */
        public byte[] encode(Snapshot s, int builtOn) {
            try {
                return pack(s, find(builtOn));
            } catch (IOException e) { throw new IllegalStateException(e); }
        }

        private byte[] pack(Snapshot s, State base) throws IOException {
            if (base != null && base.tick >= s.tick) base = null;
            lastKey = base == null;
            State next = new State();
            next.tick = s.tick;
            next.epoch = epoch;
            next.time = Snapshot.bad(s.hostTime) ? 0f : s.hostTime;
            Out out = new Out();
            out.writeByte(base == null ? 1 : 0);
            out.writeInt(epoch);
            out.writeVarInt(s.tick);
            if (base != null) out.writeVarInt(base.tick);
            writeHead(out, s);
            float dt = base == null ? 0f : step(base.time, next.time);
            Out body = new Out();
            int good = 0;
            int prevId = 0;
            for (int q = 0, n = s.ships.size(); q < n; q++) {
                Snapshot.ShipState st = s.ships.get(q);
                if (!ok(st.x, st.y)) continue;
                Integer id = st.netId;
                shipFields(st, cur);
                Rec was = base == null ? null : base.ships.get(id);
                if (was != null && !st.exact && was.f[SHIP_Q] < CARRY && sameBytes(st.weaponAngles, 254, was.angles)
                        && sameBytes(st.shotTotals, 64, was.totals)) {
                    // Every value where the guess puts it (or near enough): left out, carried on by both ends.
                    guessShip(was.f, dt, guess);
                    boolean near = true;
                    int fluxBand = Math.max(0, Math.round(st.fluxStep));
                    for (int i = 0; i < SHIP_FIELDS && near; i++) {
                        int band = i == 7 || i == 8 ? fluxBand : SHIP_BAND[i];
                        int off = i == SHIP_WRAP ? around(cur[i] - guess[i], SHIP_WRAP_MASK) : cur[i] - guess[i];
                        if (off == 0) continue;
                        if (Math.abs(off) > band || ((i == 7 || i == 8) && (cur[i] == 0) != (guess[i] == 0))) near = false;
                    }
                    if (near) {
                        Rec kept = carryShip(was, dt, guess);
                        next.ships.put(id, kept);
                        carried++;
                        continue;
                    }
                }
                good++;
                Out out0 = out;
                out = body;
                out.writeVarInt(zig(st.netId - prevId));
                prevId = st.netId;
                Rec now = new Rec();
                if (was == null) {
                    now.f = new int[SHIP_ALL];
                    System.arraycopy(cur, 0, now.f, 0, SHIP_FIELDS);
                    writeFields(out, now.f, ZERO_SHIP, SHIP_FIELDS, SHIP_BITS, SHIP_WRAP, SHIP_WRAP_MASK);
                    settle(now.f, null, dt, SHIP_FIELDS, SHIP_SPEEDS);
                } else {
                    guessShip(was.f, dt, guess);
                    int fluxBand = st.exact ? 0 : Math.max(0, Math.round(st.fluxStep));
                    for (int i = 0; i < SHIP_FIELDS; i++) {
                        int band = st.exact ? 0 : i == 7 || i == 8 ? fluxBand : SHIP_BAND[i];
                        int off = i == SHIP_WRAP ? around(cur[i] - guess[i], SHIP_WRAP_MASK) : cur[i] - guess[i];
                        boolean near = off != 0 && band > 0 && Math.abs(off) <= band;
                        // (flux: none at all and "some" are never taken for each other)
                        if (near && (i == 7 || i == 8) && (cur[i] == 0) != (guess[i] == 0)) near = false;
                        if (near) fieldsHeld++; else if (off != 0) fieldsSent++;
                        rec[i] = near ? guess[i] : cur[i];
                    }
                    writeFields(out, rec, guess, SHIP_FIELDS, SHIP_BITS, SHIP_WRAP, SHIP_WRAP_MASK);
                    settle(rec, was.f, dt, SHIP_FIELDS, SHIP_SPEEDS);
                    rec[SHIP_Q] = 0;
                    if (same(rec, was.f, SHIP_ALL)) now.f = was.f; // nothing moved: one array serves both updates
                    else { now.f = new int[SHIP_ALL]; System.arraycopy(rec, 0, now.f, 0, SHIP_ALL); }
                }
                // Turret angles and shot totals: which entries changed, and what to.
                now.angles = packBytes(out, st.weaponAngles, 254, was == null ? null : was.angles);
                now.totals = packBytes(out, st.shotTotals, 64, was == null ? null : was.totals);
                now.f[SHIP_Q] = 0;
                next.ships.put(id, now);
                out = out0;
            }
            // The ones the player holds that this update says nothing of: carried on (or forgotten) the same way.
            if (base != null) {
                for (Map.Entry<Integer, Rec> e : base.ships.entrySet()) {
                    if (next.ships.containsKey(e.getKey())) continue;
                    Rec kept = carryShip(e.getValue(), dt, guess);
                    if (kept != null) next.ships.put(e.getKey(), kept);
                }
            }
            out.writeVarInt(good);
            out.write(body.toByteArray());
            body = new Out();
            good = 0;
            prevId = 0;
            for (int q = 0, n = s.missiles.size(); q < n; q++) {
                Snapshot.MissileState m = s.missiles.get(q);
                if (!ok(m.x, m.y)) continue;
                Integer id = m.id;
                missileFields(m, cur);
                int[] was = base == null ? null : base.missiles.get(id);
                if (was != null && was[MISSILE_Q] < CARRY) {
                    guessMissile(was, dt, guess);
                    boolean near = true;
                    for (int i = 0; i < MISSILE_FIELDS && near; i++) {
                        int off = i == MISSILE_WRAP ? around(cur[i] - guess[i], MISSILE_WRAP_MASK) : cur[i] - guess[i];
                        if (off != 0 && Math.abs(off) > MISSILE_BAND[i]) near = false;
                    }
                    if (near) {
                        next.missiles.put(id, carryMissile(was, dt, guess));
                        carried++;
                        continue;
                    }
                }
                good++;
                Out out0 = out;
                out = body;
                out.writeVarInt(zig(m.id - prevId));
                prevId = m.id;
                int[] now;
                if (was == null) {
                    now = new int[MISSILE_ALL];
                    System.arraycopy(cur, 0, now, 0, MISSILE_FIELDS);
                    writeFields(out, now, ZERO_MISSILE, MISSILE_FIELDS, MISSILE_BITS, MISSILE_WRAP, MISSILE_WRAP_MASK);
                    settle(now, null, dt, MISSILE_FIELDS, MISSILE_SPEEDS);
                } else {
                    guessMissile(was, dt, guess);
                    for (int i = 0; i < MISSILE_FIELDS; i++) {
                        int off = i == MISSILE_WRAP ? around(cur[i] - guess[i], MISSILE_WRAP_MASK) : cur[i] - guess[i];
                        boolean near = off != 0 && Math.abs(off) <= MISSILE_BAND[i];
                        if (near) fieldsHeld++; else if (off != 0) fieldsSent++;
                        rec[i] = near ? guess[i] : cur[i];
                    }
                    writeFields(out, rec, guess, MISSILE_FIELDS, MISSILE_BITS, MISSILE_WRAP, MISSILE_WRAP_MASK);
                    settle(rec, was, dt, MISSILE_FIELDS, MISSILE_SPEEDS);
                    rec[MISSILE_Q] = 0;
                    if (same(rec, was, MISSILE_ALL)) now = was;
                    else { now = new int[MISSILE_ALL]; System.arraycopy(rec, 0, now, 0, MISSILE_ALL); }
                }
                now[MISSILE_Q] = 0;
                next.missiles.put(id, now);
                out = out0;
            }
            if (base != null) {
                for (Map.Entry<Integer, int[]> e : base.missiles.entrySet()) {
                    if (next.missiles.containsKey(e.getKey())) continue;
                    int[] kept = carryMissile(e.getValue(), dt, guess);
                    if (kept != null) next.missiles.put(e.getKey(), kept);
                }
            }
            out.writeVarInt(good);
            out.write(body.toByteArray());
            ring[s.tick & (HISTORY - 1)] = next;
            return out.toByteArray();
        }

        /** Writes a list against what the player holds; returns what they will hold after this update. */
        private static byte[] packBytes(Out out, byte[] cur, int most, byte[] was) throws IOException {
            if (cur == null) { out.writeByte(0); return was; } // no news: whatever they hold stands
            int count = Math.min(cur.length, most);
            writeBytes(out, cur, count, was);
            if (was != null && was.length == count) {
                boolean changed = false;
                for (int i = 0; i < count && !changed; i++) if (was[i] != cur[i]) changed = true;
                if (!changed) return was;
            }
            byte[] kept = new byte[count];
            System.arraycopy(cur, 0, kept, 0, count);
            return kept;
        }
    }

    /** Joiner side. One per battle. */
    public static final class Decoder {
        public static final int READ = 0, OLD = 1, NOTHING_TO_BUILD_ON = 2;

        private final State[] ring = new State[HISTORY];
        private State latest;
        private final int[] guess = new int[SHIP_ALL];
        /** Updates that could not be read because the one they are built on is not held here. */
        public int missed;
        /** Updates that arrived after a newer one had already been read (out of order, or twice). */
        public int late;
        /** What became of the last update given to decode(). */
        public int result;

        /** The update last read, for telling the host: its battle mark and number (-1: none yet). */
        public int ackEpoch() { return latest == null ? 0 : latest.epoch; }
        public int ackTick() { return latest == null ? -1 : latest.tick; }

        /**
         * Unpack an update. Null if it is older than one already read (result OLD: nothing to do), or built
         * on one not held here (result NOTHING_TO_BUILD_ON: the caller should ask the host for one in full).
         */
        public Snapshot decode(byte[] data) throws IOException {
            try {
                return unpack(data);
            } catch (IOException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new IOException("bad update: " + e);
            }
        }

        private Snapshot unpack(byte[] data) throws IOException {
            In in = Msg.reader(data);
            int kind = in.readByte();
            if (kind != 0 && kind != 1) throw new IOException("unknown kind of update " + kind);
            boolean full = kind == 1;
            int ep = in.readInt();
            Snapshot snap = new Snapshot();
            snap.tick = in.readVarInt();
            State base = null;
            if (!full) {
                int builtOn = in.readVarInt();
                State b = ring[builtOn & (HISTORY - 1)];
                if (b != null && b.tick == builtOn && b.epoch == ep) base = b;
            }
            if (latest != null && latest.epoch == ep && snap.tick <= latest.tick) { late++; result = OLD; return null; }
            if (!full && base == null) { missed++; result = NOTHING_TO_BUILD_ON; return null; }
            readHead(in, snap);
            State next = new State();
            next.tick = snap.tick;
            next.epoch = ep;
            next.time = snap.hostTime;
            // Shots are counted from the update last read here, whichever one this update was built on.
            State since = latest != null && latest.epoch == ep ? latest : null;
            float dt = full ? 0f : step(base.time, snap.hostTime);
            int n = in.readVarInt();
            if (n < 0 || n > 100000) throw new IOException("bad ship count " + n);
            int prevId = 0;
            for (int k = 0; k < n; k++) {
                Integer id = prevId + zag(in.readVarInt());
                prevId = id;
                Rec was = base == null ? null : base.ships.get(id);
                Rec now = new Rec();
                now.f = new int[SHIP_ALL];
                if (was != null) guessShip(was.f, dt, guess);
                readFields(in, now.f, was == null ? ZERO_SHIP : guess, SHIP_FIELDS, SHIP_BITS, SHIP_WRAP, SHIP_WRAP_MASK);
                settle(now.f, was == null ? null : was.f, dt, SHIP_FIELDS, SHIP_SPEEDS);
                now.f[SHIP_Q] = 0;
                if (was != null && same(now.f, was.f, SHIP_ALL)) now.f = was.f;
                Snapshot.ShipState st = new Snapshot.ShipState();
                st.netId = id;
                shipFrom(now.f, st);
                now.angles = readBytes(in, was == null ? null : was.angles);
                // Always handed on, changed or not: this update may be built on an older one than the last one
                // read here, and then "no change since that one" is still news to the game. (Never altered once made.)
                st.weaponAngles = now.angles;
                now.totals = readBytes(in, was == null ? null : was.totals);
                Rec before = since == null ? null : since.ships.get(id);
                if (now.totals != null && before != null && before.totals != null && before.totals != now.totals
                        && before.totals.length == now.totals.length) {
                    int pairs = 0;
                    for (int i = 0; i < now.totals.length; i++) if (now.totals[i] != before.totals[i]) pairs++;
                    if (pairs > 0) {
                        byte[] list = new byte[pairs * 2];
                        int o = 0;
                        for (int i = 0; i < now.totals.length; i++) {
                            int fired = (now.totals[i] - before.totals[i]) & 0xFF;
                            if (fired > 120) fired = 0; // not a count of shots: the host began counting afresh
                            if (fired == 0) continue;
                            list[o++] = (byte) i;
                            list[o++] = (byte) Math.min(fired, 100);
                            if (i < 64) st.firing |= 1L << i; // a weapon that fired is "firing" in this update, as on the host
                        }
                        if (o > 0) st.shots = o == list.length ? list : java.util.Arrays.copyOf(list, o);
                    }
                }
                now.f[SHIP_Q] = 0;
                next.ships.put(id, now);
                snap.ships.add(st);
            }
            if (base != null) {
                for (Map.Entry<Integer, Rec> e : base.ships.entrySet()) {
                    if (next.ships.containsKey(e.getKey())) continue;
                    Rec kept = carryShip(e.getValue(), dt, guess);
                    if (kept == null) continue;
                    next.ships.put(e.getKey(), kept);
                    Snapshot.ShipState st = new Snapshot.ShipState();
                    st.netId = e.getKey();
                    shipFrom(kept.f, st);
                    st.weaponAngles = kept.angles;
                    snap.ships.add(st);
                }
            }
            if (next.ships.size() > MAX_HELD) throw new IOException("too many ships held: " + next.ships.size());
            int mn = in.readVarInt();
            if (mn < 0 || mn > 100000) throw new IOException("bad missile count " + mn);
            prevId = 0;
            for (int k = 0; k < mn; k++) {
                Integer id = prevId + zag(in.readVarInt());
                prevId = id;
                int[] was = base == null ? null : base.missiles.get(id);
                int[] now = new int[MISSILE_ALL];
                if (was != null) guessMissile(was, dt, guess);
                readFields(in, now, was == null ? ZERO_MISSILE : guess, MISSILE_FIELDS, MISSILE_BITS, MISSILE_WRAP, MISSILE_WRAP_MASK);
                settle(now, was, dt, MISSILE_FIELDS, MISSILE_SPEEDS);
                now[MISSILE_Q] = 0;
                if (was != null && same(now, was, MISSILE_ALL)) now = was;
                next.missiles.put(id, now);
                Snapshot.MissileState m = new Snapshot.MissileState();
                m.id = id;
                missileFrom(now, m);
                snap.missiles.add(m);
            }
            if (base != null) {
                for (Map.Entry<Integer, int[]> e : base.missiles.entrySet()) {
                    if (next.missiles.containsKey(e.getKey())) continue;
                    int[] kept = carryMissile(e.getValue(), dt, guess);
                    if (kept == null) continue;
                    next.missiles.put(e.getKey(), kept);
                    Snapshot.MissileState m = new Snapshot.MissileState();
                    m.id = e.getKey();
                    missileFrom(kept, m);
                    snap.missiles.add(m);
                }
            }
            if (next.missiles.size() > MAX_HELD) throw new IOException("too many missiles held: " + next.missiles.size());
            if (latest != null && latest.epoch != ep) java.util.Arrays.fill(ring, null); // a new battle: nothing of the old one is to be built on
            ring[snap.tick & (HISTORY - 1)] = next;
            latest = next;
            result = READ;
            return snap;
        }
    }
}

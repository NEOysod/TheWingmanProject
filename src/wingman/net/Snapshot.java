package wingman.net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The state of tracked ships, fighters and missiles at one moment on the host.
 *
 * Built to be small. A ship only appears in an update if it has done something the joiner could not have
 * worked out alone (see the host's "dead reckoning"), and each ship only carries the parts that are not at
 * their resting value: no speed for a ship standing still, no flux for a ship with none, no weapon angles if
 * no turret has moved.
 */
public final class Snapshot {
    public static final int F_DEFENSE = 1, F_OVERLOADED = 2, F_VENTING = 4, F_SYSTEM = 8, F_PHASED = 16;
    /** On the wire only: a list of shot counts follows the firing bits. */
    private static final int F_SHOTS = 32;

    private static final int P_MOVING = 1, P_FLUX = 2, P_SHIELD = 4, P_WEAPONS = 8, P_FIRING = 16, P_HURT = 32, P_CR = 64, P_SYSTEMS = 128;

    public static final class ShipState {
        public int netId;
        public float x, y, vx, vy, facing, angVel;
        /**
         * How fast its speed and turn rate were changing (units/s^2, degrees/s^2), as worked out by the compact
         * format from the updates both ends hold. Not sent; zero in the old format. Lets a copy be carried on
         * between updates along the curve it is on, not in a straight line.
         */
        public float ax, ay, angAcc;
        public float hull = 1f; // 0..1
        public float cr = 1f;   // combat readiness 0..1, sent in half-percent steps
        public float flux, hardFlux;
        public int flags;
        public float shieldFacing;
        public long firing;     // bit i = weapon i (host order) is firing
        /**
         * Shots since the last update, as pairs: weapon (host order), how many. "Is firing" lasts longer than a
         * shot and says nothing of how many; this is the count, so a copy fires exactly as often as the real one.
         */
        public byte[] shots;
        /**
         * The ship system (F key) and the right-click system (phase cloak, damper field...): which stage each is
         * in (0 idle, 1 charging up, 2 active, 3 winding down, 4 cooling down) and how far its effect has built, 0..1.
         */
        public int system, cloak;
        public float systemLevel, cloakLevel;

        /**
         * Host only, not sent: how the update packer may treat this ship. 'exact': every value goes as it is
         * (the ships players fly). 'fluxStep': how far flux may sit from the truth before it is sent again.
         */
        public boolean exact;
        public float fluxStep;
        /**
         * Host only: how many shots each weapon (host order, first 64) has fired so far in the battle, low 8
         * bits. Sent in place of 'shots' by the packer that works on changes, because a running total survives
         * a lost update and "shots since the last update" does not. The joiner's unpacker turns it back into 'shots'.
         */
        public byte[] shotTotals;

        /** Back to a blank state, so one object can be reused for ships that turn out not to need sending. */
        public void clear() {
            netId = 0;
            x = y = vx = vy = facing = angVel = 0f;
            hull = 1f;
            cr = 1f;
            flux = hardFlux = shieldFacing = 0f;
            flags = 0;
            firing = 0L;
            shots = null;
            exact = false;
            fluxStep = 0f;
            shotTotals = null;
            weaponAngles = null;
            system = cloak = 0;
            systemLevel = cloakLevel = 0f;
        }
        /** Each weapon's angle relative to the hull, 256 steps per turn. Null: unchanged since the last time sent. */
        public byte[] weaponAngles;
    }

    public static final int M_FIZZLING = 1;
    private static final int MP_TURNING = 2;

    public static final class MissileState {
        public int id;
        public float x, y, vx, vy, facing;
        /** Turn rate in degrees per second, so a turning missile can be drawn turning between updates. */
        public float angVel;
        /** How fast its speed and turn rate were changing (see ShipState.ax). */
        public float ax, ay, angAcc;
        public int flags;
        /** How long the missile has been flying on the host, in seconds. Its fuel runs by this clock, not the joiner's. */
        public float flightTime;
    }

    public List<MissileState> missiles = new ArrayList<MissileState>();
    public int tick;
    public boolean paused;
    public int hostFlagship = -1;
    /** How fast the host's battle is running: 1 normal, below 1 slowed (phase), above 1 sped up. */
    public float timeMult = 1f;
    /** Ships currently flown by joined players (so others can't transfer into them). */
    public int[] piloted = new int[0];
    /**
     * For each piloted ship: the number of the last set of controls from its player that the ship has acted
     * on, and for how long (battle seconds) it had been acting on them when this update was made. With these
     * the player's own game can compare like with like: where the real ship is now against where its own copy
     * was that long after sending those same controls.
     */
    public int[] pilotedSeq = new int[0];
    public float[] pilotedAge = new float[0];
    /** For each piloted ship: charges left in its ship system (254 or more = as good as unlimited, 255 = no system). */
    public int[] pilotedSysAmmo = new int[0];
    /**
     * Where each piloted ship's player is pointing (map coordinates, to 2 units), and the host's own (hasHostAim):
     * so on every PC a mod's script that aims "where the player points" for one of these ships (with Wingman's
     * agent, or through the ship's mouse target) aims where that person really points.
     */
    public float[] pilotedAimX = new float[0], pilotedAimY = new float[0];
    public boolean hasHostAim;
    public float hostAimX, hostAimY;

    /** Aims: piloted ones (as many as piloted), then the host's if any. */
    static void writeAims(Out out, Snapshot s) throws IOException {
        for (int i = 0; i < s.piloted.length; i++) {
            out.writeShort(packAim(i < s.pilotedAimX.length ? s.pilotedAimX[i] : 0f));
            out.writeShort(packAim(i < s.pilotedAimY.length ? s.pilotedAimY[i] : 0f));
        }
        out.writeBoolean(s.hasHostAim);
        if (s.hasHostAim) { out.writeShort(packAim(s.hostAimX)); out.writeShort(packAim(s.hostAimY)); }
    }

    static void readAims(In in, Snapshot s) throws IOException {
        s.pilotedAimX = new float[s.piloted.length];
        s.pilotedAimY = new float[s.piloted.length];
        for (int i = 0; i < s.piloted.length; i++) {
            s.pilotedAimX[i] = in.readShort() * 2f;
            s.pilotedAimY[i] = in.readShort() * 2f;
        }
        s.hasHostAim = in.readBoolean();
        if (s.hasHostAim) { s.hostAimX = in.readShort() * 2f; s.hostAimY = in.readShort() * 2f; }
    }

    static int packAim(float v) {
        if (bad(v)) return 0;
        return Math.max(-32767, Math.min(32767, Math.round(v / 2f)));
    }

    /** The host's battle clock when this update was made. */
    public float hostTime;
    public List<ShipState> ships = new ArrayList<ShipState>();

    /** True if a number from the network can't be a real coordinate or speed: not a number, infinite, or absurd. */
    public static boolean bad(float v) { return v != v || v > 1.0e7f || v < -1.0e7f; }

    /** Keeps a value that should lie between lo and hi inside that range; garbage becomes lo. */
    public static float clamp(float v, float lo, float hi) { return v != v ? lo : v < lo ? lo : v > hi ? hi : v; }

    public static byte packAngle(float deg) {
        float a = deg % 360f;
        if (a < 0) a += 360f;
        return (byte) Math.round(a * 256f / 360f);
    }

    public static float unpackAngle(byte b) {
        return (b & 0xFF) * 360f / 256f;
    }

    /** Heading in 65536 steps per turn. */
    public static int packFacing(float deg) {
        float a = deg % 360f;
        if (a < 0) a += 360f;
        return Math.round(a * 65536f / 360f) & 0xFFFF;
    }

    public static float unpackFacing(int v) { return (v & 0xFFFF) * 360f / 65536f; }

    /** Speeds travel in eighths of a unit per second (or tenths of a degree per second for turning). */
    public static int packSpeed(float v, float scale) {
        if (v != v) return 0;
        return Math.round(Math.max(-32767f, Math.min(32767f, v * scale)));
    }

    public static final float SPEED = 8f, TURN = 10f;

    public byte[] encode() {
        Msg.W w = new Msg.W();
        try {
            w.out.writeVarInt(tick);
            w.out.writeBoolean(paused);
            w.out.writeVarInt(hostFlagship + 1);
            w.out.writeFloat(timeMult);
            w.out.writeFloat(hostTime);
            w.out.writeByte(piloted.length);
            for (int i = 0; i < piloted.length; i++) {
                w.out.writeVarInt(piloted[i]);
                w.out.writeShort(i < pilotedSeq.length ? pilotedSeq[i] : 0);
                float age = i < pilotedAge.length ? pilotedAge[i] : 2.55f;
                w.out.writeByte(Math.round(clamp(age, 0f, 2.55f) * 100f));
                w.out.writeByte(i < pilotedSysAmmo.length ? Math.max(0, Math.min(255, pilotedSysAmmo[i])) : 255);
            }
            writeAims(w.out, this);
            w.out.writeVarInt(ships.size());
            for (ShipState s : ships) {
                int vx = packSpeed(s.vx, SPEED), vy = packSpeed(s.vy, SPEED), av = packSpeed(s.angVel, TURN);
                int hull = Math.round(clamp(s.hull, 0f, 1f) * 65535f);
                int cr = Math.round(clamp(s.cr, 0f, 1f) * 200f);
                boolean shield = (s.flags & F_DEFENSE) != 0 && s.shieldFacing != 0f;
                int parts = 0;
                if (vx != 0 || vy != 0 || av != 0) parts |= P_MOVING;
                if (s.flux > 0f || s.hardFlux > 0f) parts |= P_FLUX;
                if (shield) parts |= P_SHIELD;
                if (s.weaponAngles != null) parts |= P_WEAPONS;
                if (s.firing != 0L) parts |= P_FIRING;
                if (hull != 65535) parts |= P_HURT;
                if (cr != 200) parts |= P_CR;
                if (s.system != 0 || s.cloak != 0) parts |= P_SYSTEMS;
                boolean counted = s.shots != null && s.shots.length >= 2;
                w.out.writeVarInt(s.netId);
                w.out.writeByte(parts);
                w.out.writeByte((s.flags & 0x1F) | (counted ? F_SHOTS : 0));
                w.out.writeFloat(s.x);
                w.out.writeFloat(s.y);
                w.out.writeShort(packFacing(s.facing));
                if ((parts & P_MOVING) != 0) { w.out.writeShort(vx); w.out.writeShort(vy); w.out.writeShort(av); }
                if ((parts & P_FLUX) != 0) { w.out.writeFloat(s.flux); w.out.writeFloat(s.hardFlux); }
                if ((parts & P_SHIELD) != 0) w.out.writeByte(packAngle(s.shieldFacing));
                if ((parts & P_WEAPONS) != 0) {
                    int n = Math.min(s.weaponAngles.length, 255);
                    w.out.writeByte(n);
                    for (int i = 0; i < n; i++) w.out.writeByte(s.weaponAngles[i]);
                }
                if ((parts & P_FIRING) != 0) w.out.writeVarLong(s.firing);
                if (counted) {
                    int pairs = Math.min(s.shots.length / 2, 64);
                    w.out.writeByte(pairs);
                    w.out.write(s.shots, 0, pairs * 2);
                }
                if ((parts & P_HURT) != 0) w.out.writeShort(hull);
                if ((parts & P_CR) != 0) w.out.writeByte(cr);
                if ((parts & P_SYSTEMS) != 0) {
                    w.out.writeByte((s.system & 7) | ((s.cloak & 7) << 3));
                    w.out.writeByte(Math.round(clamp(s.systemLevel, 0f, 1f) * 255f));
                    w.out.writeByte(Math.round(clamp(s.cloakLevel, 0f, 1f) * 255f));
                }
            }
            w.out.writeVarInt(missiles.size());
            for (MissileState m : missiles) {
                int av = packSpeed(m.angVel, TURN);
                int flags = (m.flags & M_FIZZLING) | (av != 0 ? MP_TURNING : 0);
                w.out.writeVarInt(m.id);
                w.out.writeByte(flags);
                w.out.writeFloat(m.x);
                w.out.writeFloat(m.y);
                w.out.writeShort(packSpeed(m.vx, SPEED));
                w.out.writeShort(packSpeed(m.vy, SPEED));
                w.out.writeByte(packAngle(m.facing));
                if (av != 0) w.out.writeShort(av);
                w.out.writeShort(Math.round(clamp(m.flightTime, 0f, 1300f) * 50f));
            }
        } catch (IOException e) { throw new IllegalStateException(e); }
        return w.done();
    }

    public static Snapshot decode(byte[] data) throws IOException {
        In in = Msg.reader(data);
        Snapshot snap = new Snapshot();
        snap.tick = in.readVarInt();
        snap.paused = in.readBoolean();
        snap.hostFlagship = in.readVarInt() - 1;
        snap.timeMult = clamp(in.readFloat(), 0.05f, 20f);
        snap.hostTime = in.readFloat();
        if (bad(snap.hostTime)) snap.hostTime = 0f;
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
        readAims(in, snap);
        int n = in.readVarInt();
        if (n < 0 || n > 100000) throw new IOException("bad ship count " + n);
        for (int i = 0; i < n; i++) {
            ShipState s = new ShipState();
            s.netId = in.readVarInt();
            int parts = in.readByte();
            int rawFlags = in.readByte();
            s.flags = rawFlags & 0x1F;
            s.x = in.readFloat();
            s.y = in.readFloat();
            s.facing = unpackFacing(in.readShort());
            if ((parts & P_MOVING) != 0) { s.vx = in.readShort() / SPEED; s.vy = in.readShort() / SPEED; s.angVel = in.readShort() / TURN; }
            if ((parts & P_FLUX) != 0) { s.flux = in.readFloat(); s.hardFlux = in.readFloat(); }
            if ((parts & P_SHIELD) != 0) s.shieldFacing = unpackAngle(in.readByte());
            if ((parts & P_WEAPONS) != 0) {
                s.weaponAngles = new byte[in.readByte() & 0xFF];
                in.readFully(s.weaponAngles);
            }
            if ((parts & P_FIRING) != 0) s.firing = in.readVarLong();
            if ((rawFlags & F_SHOTS) != 0) {
                s.shots = new byte[(in.readByte() & 0xFF) * 2];
                in.readFully(s.shots);
            }
            if ((parts & P_HURT) != 0) s.hull = (in.readShort() & 0xFFFF) / 65535f;
            if ((parts & P_CR) != 0) s.cr = (in.readByte() & 0xFF) / 200f;
            if ((parts & P_SYSTEMS) != 0) {
                int both = in.readByte();
                s.system = Math.min(4, both & 7);
                s.cloak = Math.min(4, (both >> 3) & 7);
                s.systemLevel = (in.readByte() & 0xFF) / 255f;
                s.cloakLevel = (in.readByte() & 0xFF) / 255f;
            }
            // Guard rail: a ship state with impossible numbers is dropped; the ship keeps its last good state.
            if (bad(s.x) || bad(s.y)) continue;
            s.cr = clamp(s.cr, 0f, 1f);
            s.flux = clamp(s.flux, 0f, 1.0e7f);
            s.hardFlux = clamp(s.hardFlux, 0f, s.flux);
            snap.ships.add(s);
        }
        int mn = in.readVarInt();
        if (mn < 0 || mn > 100000) throw new IOException("bad missile count " + mn);
        for (int i = 0; i < mn; i++) {
            MissileState m = new MissileState();
            m.id = in.readVarInt();
            int flags = in.readByte();
            m.flags = flags & M_FIZZLING;
            m.x = in.readFloat();
            m.y = in.readFloat();
            m.vx = in.readShort() / SPEED;
            m.vy = in.readShort() / SPEED;
            m.facing = unpackAngle(in.readByte());
            if ((flags & MP_TURNING) != 0) m.angVel = in.readShort() / TURN;
            m.flightTime = (in.readShort() & 0xFFFF) / 50f;
            if (bad(m.x) || bad(m.y)) continue;
            snap.missiles.add(m);
        }
        return snap;
    }
}

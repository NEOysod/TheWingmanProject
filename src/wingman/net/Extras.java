package wingman.net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The smaller side-channels: damage reports, ship stats, the values scripts keep on a ship, what ships aim at, their
 * bodies and switches, missiles' owners, and named stats.
 */
public final class Extras {
    private Extras() {}

    /** Damage one ship took from one source over a short while, summed up. */
    public static final class Hurt {
        public int target, source = -1;
        /** Damage type by its place in the game's list (kinetic, high explosive, ...). */
        public int type;
        public boolean onShield;
        public float amount, emp;
        /** Where on the battlefield the (largest) hit landed. */
        public float x, y;
    }

    public static byte[] encodeHurts(List<Hurt> list) {
        Msg.W w = new Msg.W();
        try {
            w.out.writeVarInt(list.size());
            for (Hurt h : list) {
                w.out.writeVarInt(h.target);
                w.out.writeVarInt(h.source + 1);
                w.out.writeByte((h.type & 0x0F) | (h.onShield ? 0x10 : 0));
                w.out.writeFloat(h.amount);
                w.out.writeFloat(h.emp);
                w.out.writeFloat(h.x);
                w.out.writeFloat(h.y);
            }
        } catch (IOException e) { throw new IllegalStateException(e); }
        return w.done();
    }

    public static List<Hurt> decodeHurts(byte[] data) throws IOException {
        In in = Msg.reader(data);
        int n = in.readVarInt();
        if (n < 0 || n > 2000) throw new IOException("bad damage report count " + n);
        List<Hurt> out = new ArrayList<Hurt>(n);
        for (int i = 0; i < n; i++) {
            Hurt h = new Hurt();
            h.target = in.readVarInt();
            h.source = in.readVarInt() - 1;
            int b = in.readByte();
            h.type = b & 0x0F;
            h.onShield = (b & 0x10) != 0;
            h.amount = in.readFloat();
            h.emp = in.readFloat();
            h.x = in.readFloat();
            h.y = in.readFloat();
            if (Snapshot.bad(h.amount) || Snapshot.bad(h.emp) || Snapshot.bad(h.x) || Snapshot.bad(h.y) || h.amount < 0f || h.emp < 0f) continue;
            out.add(h);
        }
        return out;
    }

    /** Some of one ship's stats as they stand on the host: bit i of the mask set = value i follows. */
    public static final class Stats {
        public int ship;
        public long mask;
        public float[] values = new float[0];
    }

    public static byte[] encodeStats(List<Stats> list) {
        Msg.W w = new Msg.W();
        try {
            w.out.writeVarInt(list.size());
            for (Stats s : list) {
                w.out.writeVarInt(s.ship);
                w.out.writeVarLong(s.mask);
                for (float v : s.values) w.out.writeFloat(v);
            }
        } catch (IOException e) { throw new IllegalStateException(e); }
        return w.done();
    }

    /** Guard rail: a stat that is NaN or infinite is left out, as if it had not been sent. */
    static void dropBadValues(Stats s) {
        int bad = 0;
        for (float v : s.values) if (v != v || Float.isInfinite(v)) bad++;
        if (bad == 0) return;
        float[] kept = new float[s.values.length - bad];
        long mask = 0;
        for (int i = 0, k = 0, j = 0; i < 64 && k < s.values.length; i++) {
            if ((s.mask & (1L << i)) == 0) continue;
            float v = s.values[k++];
            if (v != v || Float.isInfinite(v)) continue;
            mask |= 1L << i;
            kept[j++] = v;
        }
        s.mask = mask;
        s.values = kept;
    }

    public static List<Stats> decodeStats(byte[] data) throws IOException {
        In in = Msg.reader(data);
        int n = in.readVarInt();
        if (n < 0 || n > 5000) throw new IOException("bad stats count " + n);
        List<Stats> out = new ArrayList<Stats>(n);
        for (int i = 0; i < n; i++) {
            Stats s = new Stats();
            s.ship = in.readVarInt();
            s.mask = in.readVarLong();
            s.values = new float[Long.bitCount(s.mask)];
            for (int k = 0; k < s.values.length; k++) s.values[k] = in.readFloat();
            dropBadValues(s);
            out.add(s);
        }
        return out;
    }

    /** One simple value a script keeps under a name. kind: 0 gone, 1 true/false, 2 whole number, 3 number, 4 text. */
    public static final class Note {
        /** The ship it is kept on, or -1 for the battle as a whole. */
        public int ship = -1;
        public String key = "";
        public int kind;
        public boolean flag;
        public long whole;
        public double number;
        public String text = "";

        /** The value as the game's scripts would read it back, or null for "gone". */
        public Object value(boolean wide) {
            switch (kind) {
                case 1: return flag;
                case 2: return wide ? (Object) Long.valueOf(whole) : (Object) Integer.valueOf((int) whole);
                case 3: return wide ? (Object) Double.valueOf(number) : (Object) Float.valueOf((float) number);
                case 4: return text;
                default: return null;
            }
        }
        /** Set for whole numbers and numbers that were the wider of the two sorts on the host (long, double). */
        public boolean wide;
        /** Kept on a missile (ship is then the host's missile number) rather than a ship or the battle. */
        public boolean onMissile;
        /** For a point (kind POINT): its y; x is in number. */
        public float y;
    }

    /**
     * Kinds beyond the simple ones: a ship (whole = the host's ship number), a missile (whole = its missile number),
     * a point (number = x, y). The joiner turns them back into its own copy of that ship or missile, or a Vector2f.
     */
    public static final int SHIP_REF = 5, MISSILE_REF = 6, POINT = 7;

    /** A note whose value is one of the host's ships or missiles, by number. */
    public static Note refNote(int ship, String key, int kind, int id) {
        if (key == null || key.length() > 80) return null;
        Note n = new Note();
        n.ship = ship;
        n.key = key;
        n.kind = kind;
        n.whole = id;
        return n;
    }

    /** A note whose value is a point on the battlefield. */
    public static Note pointNote(int ship, String key, float x, float y) {
        if (key == null || key.length() > 80 || !(Math.abs(x) < 1e7f) || !(Math.abs(y) < 1e7f)) return null;
        Note n = new Note();
        n.ship = ship;
        n.key = key;
        n.kind = POINT;
        n.number = x;
        n.y = y;
        return n;
    }

    /** Turn a script's value into a note, or return null if it is not one of the simple sorts. */
    public static Note noteOf(int ship, String key, Object v) {
        if (key == null || key.length() > 80) return null;
        Note n = new Note();
        n.ship = ship;
        n.key = key;
        if (v == null) { n.kind = 0; return n; }
        if (v instanceof Boolean) { n.kind = 1; n.flag = (Boolean) v; return n; }
        if (v instanceof Integer || v instanceof Short || v instanceof Byte) { n.kind = 2; n.whole = ((Number) v).longValue(); return n; }
        if (v instanceof Long) { n.kind = 2; n.whole = (Long) v; n.wide = true; return n; }
        if (v instanceof Float) { n.kind = 3; n.number = (Float) v; return n; }
        if (v instanceof Double) { n.kind = 3; n.number = (Double) v; n.wide = true; return n; }
        if (v instanceof String && ((String) v).length() <= 64) { n.kind = 4; n.text = (String) v; return n; }
        return null;
    }

    public static byte[] encodeNotes(List<Note> list) {
        Msg.W w = new Msg.W();
        try {
            w.out.writeVarInt(list.size());
            for (Note n : list) {
                w.out.writeVarInt(n.ship + 1);
                w.out.writeUTF(n.key);
                w.out.writeByte(n.kind | (n.wide ? 0x10 : 0) | (n.onMissile ? 0x20 : 0));
                if (n.kind == 1) w.out.writeBoolean(n.flag);
                else if (n.kind == 2) w.out.writeVarLong(n.whole);
                else if (n.kind == 3) { if (n.wide) w.out.writeLong(Double.doubleToLongBits(n.number)); else w.out.writeFloat((float) n.number); }
                else if (n.kind == 4) w.out.writeUTF(n.text);
                else if (n.kind == SHIP_REF || n.kind == MISSILE_REF) w.out.writeVarInt((int) n.whole + 1);
                else if (n.kind == POINT) { w.out.writeFloat((float) n.number); w.out.writeFloat(n.y); }
            }
        } catch (IOException e) { throw new IllegalStateException(e); }
        return w.done();
    }

    public static List<Note> decodeNotes(byte[] data) throws IOException {
        In in = Msg.reader(data);
        int count = in.readVarInt();
        if (count < 0 || count > 5000) throw new IOException("bad note count " + count);
        List<Note> out = new ArrayList<Note>(count);
        for (int i = 0; i < count; i++) {
            Note n = new Note();
            n.ship = in.readVarInt() - 1;
            n.key = in.readUTF();
            int b = in.readByte();
            n.kind = b & 0x0F;
            n.wide = (b & 0x10) != 0;
            n.onMissile = (b & 0x20) != 0;
            if (n.kind == 1) n.flag = in.readBoolean();
            else if (n.kind == 2) n.whole = in.readVarLong();
            else if (n.kind == 3) n.number = n.wide ? Double.longBitsToDouble(in.readLong()) : in.readFloat();
            else if (n.kind == 4) n.text = in.readUTF();
            else if (n.kind == SHIP_REF || n.kind == MISSILE_REF) n.whole = in.readVarInt() - 1;
            else if (n.kind == POINT) {
                n.number = in.readFloat();
                n.y = in.readFloat();
                if (!(Math.abs(n.number) < 1e7) || !(Math.abs(n.y) < 1e7f)) continue; // nonsense: dropped
            }
            else if (n.kind != 0) throw new IOException("unknown note kind " + n.kind);
            out.add(n);
        }
        return out;
    }

    // ---- what a ship aims at (Msg.TARGETS) ----

    /**
     * What a ship aims at on the host: its R target, and the targets its AI hands the ship system (what system
     * scripts ask a ship that isn't the player's: TARGET_FOR_SHIP_SYSTEM, MANEUVER_TARGET, CARRIER_FIGHTER_TARGET,
     * SYSTEM_TARGET_COORDS). Ship numbers, -1 for none.
     */
    public static final class Aim {
        public int ship;
        public int target = -1, system = -1, maneuver = -1, carrier = -1;
        public boolean hasPoint;
        public float px, py;

        public boolean same(Aim o) {
            return o != null && target == o.target && system == o.system && maneuver == o.maneuver && carrier == o.carrier
                    && hasPoint == o.hasPoint && (!hasPoint || (Math.abs(px - o.px) < 4f && Math.abs(py - o.py) < 4f));
        }
    }

    public static byte[] encodeAims(List<Aim> list) {
        Msg.W w = new Msg.W();
        try {
            w.out.writeVarInt(list.size());
            for (Aim a : list) {
                w.out.writeVarInt(a.ship);
                w.out.writeVarInt(a.target + 1);
                w.out.writeVarInt(a.system + 1);
                w.out.writeVarInt(a.maneuver + 1);
                w.out.writeVarInt(a.carrier + 1);
                w.out.writeBoolean(a.hasPoint);
                if (a.hasPoint) { w.out.writeFloat(a.px); w.out.writeFloat(a.py); }
            }
        } catch (IOException e) { throw new IllegalStateException(e); }
        return w.done();
    }

    public static List<Aim> decodeAims(byte[] data) throws IOException {
        In in = Msg.reader(data);
        int count = in.readVarInt();
        if (count < 0 || count > 5000) throw new IOException("bad aim count " + count);
        List<Aim> out = new ArrayList<Aim>(count);
        for (int i = 0; i < count; i++) {
            Aim a = new Aim();
            a.ship = in.readVarInt();
            a.target = in.readVarInt() - 1;
            a.system = in.readVarInt() - 1;
            a.maneuver = in.readVarInt() - 1;
            a.carrier = in.readVarInt() - 1;
            a.hasPoint = in.readBoolean();
            if (a.hasPoint) {
                a.px = in.readFloat();
                a.py = in.readFloat();
                if (!(Math.abs(a.px) < 1e7f) || !(Math.abs(a.py) < 1e7f)) a.hasPoint = false;
            }
            out.add(a);
        }
        return out;
    }

    // ---- a ship's body and switches (Msg.BODY) ----

    /** Bits of Body.switches. */
    public static final int NO_DEFENSE = 1, NO_SYSTEM = 2;

    /**
     * What scripts change about a ship's body and switches, as it stands on the host. Sent whole when any of it
     * changes. Tags are the ship's own (ShipAPI.getTags(): markers scripts put on it in battle), at most 40, each
     * at most 80 characters, none starting "wingman".
     */
    public static final class Body {
        public int ship;
        /** CollisionClass by its place in the game's list; -1 unknown. */
        public int collision = -1;
        public float mass = Float.NaN, radius = Float.NaN;
        public int switches;
        /** The shield's arc (degrees), NaN for none; its type by its place in the game's list, -1 for none. */
        public float shieldArc = Float.NaN;
        public int shieldType = -1;
        public List<String> tags = new ArrayList<String>();
        /** Joiner only, not sent: when it came (battle clock here). */
        public float seenAt;

        /** True if the two say the same (masses and radii within 0.5%, arcs within 1 degree). */
        public boolean same(Body o) {
            return o != null && collision == o.collision && switches == o.switches && shieldType == o.shieldType
                    && near(mass, o.mass, 0.005f) && near(radius, o.radius, 0.005f)
                    && (shieldArc != shieldArc ? o.shieldArc != o.shieldArc : Math.abs(shieldArc - o.shieldArc) < 1f)
                    && tags.equals(o.tags);
        }

        private static boolean near(float a, float b, float share) {
            if (a != a || b != b) return (a != a) == (b != b);
            return Math.abs(a - b) <= Math.max(0.01f, Math.abs(b) * share);
        }
    }

    public static byte[] encodeBodies(List<Body> list) {
        Msg.W w = new Msg.W();
        try {
            w.out.writeVarInt(list.size());
            for (Body b : list) {
                w.out.writeVarInt(b.ship);
                w.out.writeByte(b.collision + 1);
                w.out.writeFloat(b.mass);
                w.out.writeFloat(b.radius);
                w.out.writeByte(b.switches);
                w.out.writeFloat(b.shieldArc);
                w.out.writeByte(b.shieldType + 1);
                int n = Math.min(40, b.tags.size());
                w.out.writeByte(n);
                for (int i = 0; i < n; i++) w.out.writeUTF(b.tags.get(i));
            }
        } catch (IOException e) { throw new IllegalStateException(e); }
        return w.done();
    }

    public static List<Body> decodeBodies(byte[] data) throws IOException {
        In in = Msg.reader(data);
        int count = in.readVarInt();
        if (count < 0 || count > 5000) throw new IOException("bad body count " + count);
        List<Body> out = new ArrayList<Body>(count);
        for (int i = 0; i < count; i++) {
            Body b = new Body();
            b.ship = in.readVarInt();
            b.collision = (in.readByte() & 0xFF) - 1;
            b.mass = in.readFloat();
            b.radius = in.readFloat();
            if (!(b.mass > 0f && b.mass < 1e9f)) b.mass = Float.NaN;
            if (!(b.radius > 0f && b.radius < 1e6f)) b.radius = Float.NaN;
            b.switches = in.readByte() & 0xFF;
            b.shieldArc = in.readFloat();
            if (!(b.shieldArc >= 0f && b.shieldArc <= 360f)) b.shieldArc = Float.NaN;
            b.shieldType = (in.readByte() & 0xFF) - 1;
            int n = in.readByte() & 0xFF;
            if (n > 40) throw new IOException("too many tags");
            for (int k = 0; k < n; k++) {
                String t = in.readUTF();
                if (t.length() <= 80) b.tags.add(t);
            }
            out.add(b);
        }
        return out;
    }

    // ---- a missile's side and launcher (Msg.MISSILE_WHO) ----

    /** A missile whose side or launching ship changed on the host (a hijack, a hand-over). */
    public static final class MissileWho {
        public int missile, owner, source = -1;
    }

    public static byte[] encodeMissileWho(List<MissileWho> list) {
        Msg.W w = new Msg.W();
        try {
            w.out.writeVarInt(list.size());
            for (MissileWho m : list) {
                w.out.writeVarInt(m.missile);
                w.out.writeByte(m.owner);
                w.out.writeVarInt(m.source + 1);
            }
        } catch (IOException e) { throw new IllegalStateException(e); }
        return w.done();
    }

    public static List<MissileWho> decodeMissileWho(byte[] data) throws IOException {
        In in = Msg.reader(data);
        int count = in.readVarInt();
        if (count < 0 || count > 5000) throw new IOException("bad missile count " + count);
        List<MissileWho> out = new ArrayList<MissileWho>(count);
        for (int i = 0; i < count; i++) {
            MissileWho m = new MissileWho();
            m.missile = in.readVarInt();
            m.owner = in.readByte();
            m.source = in.readVarInt() - 1;
            out.add(m);
        }
        return out;
    }

    // ---- stats beyond the fixed list (Msg.STAT_NAMES, Msg.STATS_MORE) ----

    /** A name for a stat index: "m:" + a MutableShipStatsAPI getter, "d:" + a named stat, "b:" + a named bonus. */
    public static final class StatName {
        public int index;
        public String name = "";
    }

    public static byte[] encodeStatNames(List<StatName> list) {
        Msg.W w = new Msg.W();
        try {
            w.out.writeVarInt(list.size());
            for (StatName n : list) { w.out.writeVarInt(n.index); w.out.writeUTF(n.name); }
        } catch (IOException e) { throw new IllegalStateException(e); }
        return w.done();
    }

    public static List<StatName> decodeStatNames(byte[] data) throws IOException {
        In in = Msg.reader(data);
        int count = in.readVarInt();
        if (count < 0 || count > 20000) throw new IOException("bad stat name count " + count);
        List<StatName> out = new ArrayList<StatName>(count);
        for (int i = 0; i < count; i++) {
            StatName n = new StatName();
            n.index = in.readVarInt();
            n.name = in.readUTF();
            if (n.index < 0 || n.index > 100000 || n.name.length() > 120) throw new IOException("bad stat name");
            out.add(n);
        }
        return out;
    }

    /** A ship's named stats that changed: index (see StatName), value (NaN: the stat is gone). */
    public static final class MoreStats {
        public int ship;
        public int[] index = new int[0];
        public float[] values = new float[0];
    }

    public static byte[] encodeMoreStats(List<MoreStats> list) {
        Msg.W w = new Msg.W();
        try {
            w.out.writeVarInt(list.size());
            for (MoreStats m : list) {
                w.out.writeVarInt(m.ship);
                w.out.writeVarInt(m.index.length);
                for (int i = 0; i < m.index.length; i++) { w.out.writeVarInt(m.index[i]); w.out.writeFloat(m.values[i]); }
            }
        } catch (IOException e) { throw new IllegalStateException(e); }
        return w.done();
    }

    public static List<MoreStats> decodeMoreStats(byte[] data) throws IOException {
        In in = Msg.reader(data);
        int count = in.readVarInt();
        if (count < 0 || count > 5000) throw new IOException("bad stats count " + count);
        List<MoreStats> out = new ArrayList<MoreStats>(count);
        for (int i = 0; i < count; i++) {
            MoreStats m = new MoreStats();
            m.ship = in.readVarInt();
            int n = in.readVarInt();
            if (n < 0 || n > 2000) throw new IOException("bad stat count " + n);
            m.index = new int[n];
            m.values = new float[n];
            for (int k = 0; k < n; k++) { m.index[k] = in.readVarInt(); m.values[k] = in.readFloat(); }
            out.add(m);
        }
        return out;
    }

    // ---- peak time and carriers' replacement rates (Msg.CLOCKS) ----

    /** A ship's time deployed (what its peak performance counts down from) and its bays' replacement rates. */
    public static final class Clock {
        public int ship;
        public float deployed = Float.NaN;
        public float[] bayRates = new float[0];
    }

    public static byte[] encodeClocks(List<Clock> list) {
        Msg.W w = new Msg.W();
        try {
            w.out.writeVarInt(list.size());
            for (Clock c : list) {
                w.out.writeVarInt(c.ship);
                w.out.writeFloat(c.deployed);
                int n = Math.min(40, c.bayRates.length);
                w.out.writeByte(n);
                for (int i = 0; i < n; i++) w.out.writeFloat(c.bayRates[i]);
            }
        } catch (IOException e) { throw new IllegalStateException(e); }
        return w.done();
    }

    public static List<Clock> decodeClocks(byte[] data) throws IOException {
        In in = Msg.reader(data);
        int count = in.readVarInt();
        if (count < 0 || count > 5000) throw new IOException("bad clock count " + count);
        List<Clock> out = new ArrayList<Clock>(count);
        for (int i = 0; i < count; i++) {
            Clock c = new Clock();
            c.ship = in.readVarInt();
            c.deployed = in.readFloat();
            if (!(c.deployed >= 0f && c.deployed < 1e7f)) c.deployed = Float.NaN;
            int n = in.readByte() & 0xFF;
            if (n > 40) throw new IOException("too many bays");
            c.bayRates = new float[n];
            for (int k = 0; k < n; k++) {
                float r = in.readFloat();
                c.bayRates[k] = r >= 0f && r <= 10f ? r : Float.NaN;
            }
            out.add(c);
        }
        return out;
    }

    // ---- what became of ordinary shots (Msg.SHOT_FATES) ----

    /** Kinds of ShotFate. */
    public static final int SHOT_HIT = 1, SHOT_GONE = 2, SHOT_TURNED = 3, SHOT_BURST = 4;

    /**
     * What became of one of the host's ordinary shots (not a missile, no on-hit script), where a joiner's copy of it
     * may not do the same by itself: it struck something (the copy may have missed, or flown through a missile that
     * never collides on a joiner), a script took it off the field, or a script turned it (reflected, redirected,
     * handed to the other side). Told by the ship that fired it, its weapon (the id's hash), how long it had flown,
     * and where; the joiner finds its own copy by those.
     */
    public static final class ShotFate {
        public int kind, source, weapon;
        /** at: the host's battle clock when it happened (the clock position updates carry). */
        public float at, age, x, y;
        /** SHOT_HIT: the ship struck (-1: not a ship), the point in that ship's own frame, whether on its shield. */
        public int target = -1;
        public float ox, oy;
        public boolean shield;
        /** SHOT_TURNED: its new velocity, facing and side. */
        public float vx, vy, facing;
        public int owner;
        /**
         * SHOT_BURST: the shot's fuse went off (flak and the like) and it became this explosion at (x, y): its look
         * and sound (DamagingExplosionSpec), so the joiner can show it without doing damage.
         */
        public float exDuration, exRadius, exCore, pSizeMin, pSizeRange, pDuration, exSpawnRadius, soundVolume = 1f,
                detRadius, detFlashRadius, detFlashDuration;
        public int pCount, pColor, exColor, detFringe, detCore;
        public boolean showGraphic = true, detailed;
        public String sound = "";
    }

    public static byte[] encodeShotFates(List<ShotFate> list) {
        Msg.W w = new Msg.W();
        try {
            w.out.writeVarInt(list.size());
            for (ShotFate f : list) {
                w.out.writeByte(f.kind);
                w.out.writeVarInt(f.source);
                w.out.writeInt(f.weapon);
                w.out.writeFloat(f.at);
                w.out.writeFloat(f.age);
                w.out.writeFloat(f.x);
                w.out.writeFloat(f.y);
                if (f.kind == SHOT_HIT) {
                    w.out.writeVarInt(f.target + 1);
                    if (f.target >= 0) { w.out.writeFloat(f.ox); w.out.writeFloat(f.oy); w.out.writeBoolean(f.shield); }
                } else if (f.kind == SHOT_TURNED) {
                    w.out.writeFloat(f.vx);
                    w.out.writeFloat(f.vy);
                    w.out.writeFloat(f.facing);
                    w.out.writeByte(f.owner);
                } else if (f.kind == SHOT_BURST) {
                    float[] n = { f.exDuration, f.exRadius, f.exCore, f.pSizeMin, f.pSizeRange, f.pDuration, f.exSpawnRadius, f.soundVolume,
                            f.detRadius, f.detFlashRadius, f.detFlashDuration };
                    for (float x : n) w.out.writeFloat(x);
                    w.out.writeVarInt(Math.max(0, Math.min(2000, f.pCount)));
                    w.out.writeInt(f.pColor);
                    w.out.writeInt(f.exColor);
                    w.out.writeInt(f.detFringe);
                    w.out.writeInt(f.detCore);
                    w.out.writeByte((f.showGraphic ? 1 : 0) | (f.detailed ? 2 : 0));
                    w.out.writeUTF(f.sound == null || f.sound.length() > 80 ? "" : f.sound);
                }
            }
        } catch (IOException e) { throw new IllegalStateException(e); }
        return w.done();
    }

    public static List<ShotFate> decodeShotFates(byte[] data) throws IOException {
        In in = Msg.reader(data);
        int count = in.readVarInt();
        if (count < 0 || count > 5000) throw new IOException("bad shot count " + count);
        List<ShotFate> out = new ArrayList<ShotFate>(count);
        for (int i = 0; i < count; i++) {
            ShotFate f = new ShotFate();
            f.kind = in.readByte();
            f.source = in.readVarInt();
            f.weapon = in.readInt();
            f.at = in.readFloat();
            f.age = in.readFloat();
            f.x = in.readFloat();
            f.y = in.readFloat();
            if (f.kind == SHOT_HIT) {
                f.target = in.readVarInt() - 1;
                if (f.target >= 0) { f.ox = in.readFloat(); f.oy = in.readFloat(); f.shield = in.readBoolean(); }
            } else if (f.kind == SHOT_TURNED) {
                f.vx = in.readFloat();
                f.vy = in.readFloat();
                f.facing = in.readFloat();
                f.owner = in.readByte();
            } else if (f.kind == SHOT_BURST) {
                f.exDuration = in.readFloat(); f.exRadius = in.readFloat(); f.exCore = in.readFloat();
                f.pSizeMin = in.readFloat(); f.pSizeRange = in.readFloat(); f.pDuration = in.readFloat();
                f.exSpawnRadius = in.readFloat(); f.soundVolume = in.readFloat();
                f.detRadius = in.readFloat(); f.detFlashRadius = in.readFloat(); f.detFlashDuration = in.readFloat();
                f.pCount = in.readVarInt();
                f.pColor = in.readInt(); f.exColor = in.readInt(); f.detFringe = in.readInt(); f.detCore = in.readInt();
                int b = in.readByte();
                f.showGraphic = (b & 1) != 0;
                f.detailed = (b & 2) != 0;
                f.sound = in.readUTF();
                float[] n = { f.exDuration, f.exRadius, f.exCore, f.pSizeMin, f.pSizeRange, f.pDuration, f.exSpawnRadius, f.soundVolume,
                        f.detRadius, f.detFlashRadius, f.detFlashDuration };
                boolean ok = f.pCount >= 0 && f.pCount <= 2000;
                for (float x : n) if (!(x > -1f && x < 1e5f)) ok = false;
                if (!ok) continue;
            } else if (f.kind != SHOT_GONE) throw new IOException("unknown shot fate " + f.kind);
            boolean sane = Math.abs(f.x) < 1e7f && Math.abs(f.y) < 1e7f && f.age >= 0f && f.age < 1e5f && Math.abs(f.at) < 1e8f
                    && Math.abs(f.ox) < 1e5f && Math.abs(f.oy) < 1e5f && Math.abs(f.vx) < 1e6f && Math.abs(f.vy) < 1e6f && Math.abs(f.facing) < 1e6f;
            if (sane) out.add(f);
        }
        return out;
    }

    // ---- effects drawn by code that runs only on the host, or differently there (Msg.VISUALS) ----

    /** Kinds of Visual, and the frames they come from (the agent's frame kinds). */
    public static final int FX_ARC = 1, FX_EXPLOSION = 2, FX_HIT = 3, FX_SMOOTH = 4;
    public static final int FX_AI = 6, FX_DAMAGE = 7;

    /**
     * An effect a mod's code drew on the host inside a ship AI (which runs only there) or a damage listener (which on
     * a joiner's copy, taking no damage there, hears of next to none): an EMP arc, an explosion, or a particle. Its
     * points are each given on a ship (that ship's number and the point in its own frame) where there is one to
     * hold to, so they land on the ship as it stands on the joiner's screen. cls: the hash of the class that drew it.
     */
    public static final class Visual {
        /** frame 0: drawn anywhere (an arc, sent from wherever it was drawn: see VIS_ALL_ARCS). src: an arc's damage source, a ship number or -1. */
        public int kind, frame, cls, src = -1;
        public int aShip = -1, bShip = -1;
        public float ax, ay, bx, by;
        public float vx, vy, size, duration = -1f, ramp = -1f, brightness = 1f, thickness;
        public int c1, c2;
        public String sound = "";
    }

    /** VISUALS flag: the host sends every EMP arc drawn there, so a joiner draws the host's instead of its own. */
    public static final int VIS_ALL_ARCS = 1;

    public static byte[] encodeVisuals(List<Visual> list) { return encodeVisuals(list, 0); }

    public static byte[] encodeVisuals(List<Visual> list, int flags) {
        Msg.W w = new Msg.W();
        try {
            w.out.writeByte(flags);
            w.out.writeVarInt(list.size());
            for (Visual v : list) {
                w.out.writeByte(v.kind);
                w.out.writeByte(v.frame);
                w.out.writeInt(v.cls);
                w.out.writeVarInt(v.aShip + 1);
                w.out.writeFloat(v.ax);
                w.out.writeFloat(v.ay);
                w.out.writeInt(v.c1);
                if (v.kind == FX_ARC) {
                    w.out.writeVarInt(v.src + 1);
                    w.out.writeVarInt(v.bShip + 1);
                    w.out.writeFloat(v.bx);
                    w.out.writeFloat(v.by);
                    w.out.writeFloat(v.thickness);
                    w.out.writeInt(v.c2);
                    w.out.writeUTF(v.sound == null ? "" : v.sound.length() > 80 ? "" : v.sound);
                } else {
                    w.out.writeFloat(v.vx);
                    w.out.writeFloat(v.vy);
                    w.out.writeFloat(v.size);
                    w.out.writeFloat(v.duration);
                    if (v.kind != FX_EXPLOSION) { w.out.writeFloat(v.ramp); w.out.writeFloat(v.brightness); }
                }
            }
        } catch (IOException e) { throw new IllegalStateException(e); }
        return w.done();
    }

    public static List<Visual> decodeVisuals(byte[] data) throws IOException { return decodeVisuals(data, null); }

    /** flagsOut (if given): flagsOut[0] gets the message's flags (VIS_ALL_ARCS). */
    public static List<Visual> decodeVisuals(byte[] data, int[] flagsOut) throws IOException {
        In in = Msg.reader(data);
        int flags = in.readByte() & 0xFF;
        if (flagsOut != null && flagsOut.length > 0) flagsOut[0] = flags;
        int count = in.readVarInt();
        if (count < 0 || count > 5000) throw new IOException("bad effect count " + count);
        List<Visual> out = new ArrayList<Visual>(count);
        for (int i = 0; i < count; i++) {
            Visual v = new Visual();
            v.kind = in.readByte();
            v.frame = in.readByte();
            v.cls = in.readInt();
            v.aShip = in.readVarInt() - 1;
            v.ax = in.readFloat();
            v.ay = in.readFloat();
            v.c1 = in.readInt();
            if (v.kind == FX_ARC) {
                v.src = in.readVarInt() - 1;
                v.bShip = in.readVarInt() - 1;
                v.bx = in.readFloat();
                v.by = in.readFloat();
                v.thickness = in.readFloat();
                v.c2 = in.readInt();
                v.sound = in.readUTF();
            } else if (v.kind == FX_EXPLOSION || v.kind == FX_HIT || v.kind == FX_SMOOTH) {
                v.vx = in.readFloat();
                v.vy = in.readFloat();
                v.size = in.readFloat();
                v.duration = in.readFloat();
                if (v.kind != FX_EXPLOSION) { v.ramp = in.readFloat(); v.brightness = in.readFloat(); }
            } else throw new IOException("unknown effect " + v.kind);
            boolean sane = Math.abs(v.ax) < 1e7f && Math.abs(v.ay) < 1e7f && Math.abs(v.bx) < 1e7f && Math.abs(v.by) < 1e7f
                    && Math.abs(v.vx) < 1e6f && Math.abs(v.vy) < 1e6f && v.size >= 0f && v.size < 1e5f && v.thickness >= 0f && v.thickness < 1e4f
                    && v.duration < 1e3f && v.ramp < 1e3f && Math.abs(v.brightness) < 1e3f;
            if (sane) out.add(v);
        }
        return out;
    }
}

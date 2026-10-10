package wingman.net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** The smaller side-channels: damage reports, ship stats, and the simple values scripts keep on a ship. */
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
                w.out.writeByte(n.kind | (n.wide ? 0x10 : 0));
                if (n.kind == 1) w.out.writeBoolean(n.flag);
                else if (n.kind == 2) w.out.writeVarLong(n.whole);
                else if (n.kind == 3) { if (n.wide) w.out.writeLong(Double.doubleToLongBits(n.number)); else w.out.writeFloat((float) n.number); }
                else if (n.kind == 4) w.out.writeUTF(n.text);
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
            if (n.kind == 1) n.flag = in.readBoolean();
            else if (n.kind == 2) n.whole = in.readVarLong();
            else if (n.kind == 3) n.number = n.wide ? Double.longBitsToDouble(in.readLong()) : in.readFloat();
            else if (n.kind == 4) n.text = in.readUTF();
            else if (n.kind != 0) throw new IOException("unknown note kind " + n.kind);
            out.add(n);
        }
        return out;
    }
}

package wingman.net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Slow-changing damage on ships: the armor grid, and which weapons and engines are knocked out. */
public final class DamageState {
    public static final int ARMOR_SAME = 0, ARMOR_FULL = 1, ARMOR_CELLS = 2;

    public static final class Ship {
        public int netId;
        /** Bit i = weapon i (host order) is disabled. */
        public long weaponsDisabled;
        /** Bit i = engine i is disabled. */
        public int enginesDisabled;
        public boolean flamedOut;
        /** Rounds left in each weapon (host order); -1 for weapons that don't use ammo. */
        public short[] ammo = new short[0];
        /** Armor grid size, then one byte per cell (column by column): 0 = stripped, 255 = intact. */
        public int gridWidth, gridHeight;
        public byte[] armor = Msg.EMPTY;
        /**
         * How the armor travels: ARMOR_SAME = not at all (nothing changed), ARMOR_FULL = the whole grid,
         * ARMOR_CELLS = only the cells that changed since the last message about this ship.
         */
        public int armorMode = ARMOR_FULL;
        public int[] cells = new int[0];
        public byte[] values = Msg.EMPTY;

        void write(Out out) throws IOException {
            out.writeInt(netId);
            out.writeLong(weaponsDisabled);
            out.writeInt(enginesDisabled);
            out.writeBoolean(flamedOut);
            out.writeByte(ammo.length);
            for (short a : ammo) out.writeShort(a);
            out.writeByte(armorMode);
            if (armorMode == ARMOR_SAME) return;
            out.writeByte(gridWidth);
            out.writeByte(gridHeight);
            if (armorMode == ARMOR_FULL) { out.write(armor); return; }
            out.writeShort(cells.length);
            for (int i = 0; i < cells.length; i++) { out.writeShort(cells[i]); out.writeByte(values[i]); }
        }

        /** True if everything except the armor is the same as in the other. */
        public boolean sameSystems(Ship o) {
            return weaponsDisabled == o.weaponsDisabled && enginesDisabled == o.enginesDisabled && flamedOut == o.flamedOut
                    && java.util.Arrays.equals(ammo, o.ammo);
        }

        /**
         * Decide how this ship's armor should be sent, given what was sent last (null: nothing yet).
         * Returns false if the armor has not changed at all.
         */
        public boolean armorAgainst(Ship last) {
            armorMode = ARMOR_FULL;
            cells = new int[0];
            values = Msg.EMPTY;
            if (last == null || last.gridWidth != gridWidth || last.gridHeight != gridHeight || last.armor.length != armor.length) return true;
            int changed = 0;
            for (int i = 0; i < armor.length; i++) if (armor[i] != last.armor[i]) changed++;
            if (changed == 0) { armorMode = ARMOR_SAME; return false; }
            if (changed * 3 >= armor.length) return true; // most of it: the whole grid is no bigger
            armorMode = ARMOR_CELLS;
            cells = new int[changed];
            values = new byte[changed];
            for (int i = 0, n = 0; i < armor.length; i++) {
                if (armor[i] == last.armor[i]) continue;
                cells[n] = i;
                values[n++] = armor[i];
            }
            return true;
        }

        /**
         * Joiner's side: turn a message that carries only changes into the whole grid, using the grid we had.
         * Returns false if there is nothing to build on (the next full grid from the host puts that right).
         */
        public boolean mergeOnto(Ship had) {
            if (armorMode == ARMOR_FULL) return true;
            if (had == null || had.armor.length == 0 || (armorMode == ARMOR_CELLS && (had.gridWidth != gridWidth || had.gridHeight != gridHeight))) {
                gridWidth = 0; gridHeight = 0; armor = Msg.EMPTY;
                return false;
            }
            gridWidth = had.gridWidth;
            gridHeight = had.gridHeight;
            armor = had.armor.clone();
            for (int i = 0; i < cells.length; i++) if (cells[i] >= 0 && cells[i] < armor.length) armor[cells[i]] = values[i];
            return true;
        }

        static Ship read(In in) throws IOException {
            Ship s = new Ship();
            s.netId = in.readInt();
            s.weaponsDisabled = in.readLong();
            s.enginesDisabled = in.readInt();
            s.flamedOut = in.readBoolean();
            s.ammo = new short[in.readByte() & 0xFF];
            for (int i = 0; i < s.ammo.length; i++) s.ammo[i] = in.readShort();
            s.armorMode = in.readByte() & 0xFF;
            if (s.armorMode == ARMOR_SAME) return s;
            if (s.armorMode != ARMOR_FULL && s.armorMode != ARMOR_CELLS) throw new IOException("unknown armor mode " + s.armorMode);
            s.gridWidth = in.readByte() & 0xFF;
            s.gridHeight = in.readByte() & 0xFF;
            if (s.armorMode == ARMOR_FULL) {
                s.armor = new byte[s.gridWidth * s.gridHeight];
                in.readFully(s.armor);
                return s;
            }
            int n = in.readShort() & 0xFFFF;
            if (n > s.gridWidth * s.gridHeight) throw new IOException("more armor changes than cells");
            s.cells = new int[n];
            s.values = new byte[n];
            for (int i = 0; i < n; i++) { s.cells[i] = in.readShort() & 0xFFFF; s.values[i] = in.readByte(); }
            return s;
        }
    }

    public List<Ship> ships = new ArrayList<Ship>();

    public byte[] encode() {
        Msg.W w = new Msg.W();
        try {
            w.out.writeShort(ships.size());
            for (Ship s : ships) s.write(w.out);
        } catch (IOException e) { throw new IllegalStateException(e); }
        return w.done();
    }

    public static DamageState decode(byte[] data) throws IOException {
        In in = Msg.reader(data);
        DamageState d = new DamageState();
        int n = in.readShort() & 0xFFFF;
        for (int i = 0; i < n; i++) d.ships.add(Ship.read(in));
        return d;
    }
}

package wingman.net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The after-action report (Msg.REPORT, host -> joiners, just before BATTLE_END): how the host's battle went,
 * as the host's game counted it. Shown on a joiner's lobby screen instead of the join mission's own results,
 * which only know the joiner's copy of the battle.
 */
public final class BattleReport {
    /** From the players' side: 0 won, 1 lost, 2 undecided (left, or nobody won). */
    public int outcome = 2;
    public float seconds;
    public final Side[] sides = { new Side(), new Side() };
    public List<Pilot> pilots = new ArrayList<Pilot>();

    /** One side's losses. Index 0: the players' side. */
    public static final class Side {
        public int deployed, destroyed, disabled, retreated, fightersLost;
        /** The biggest ships lost, "ISS Name (Class)", biggest first. */
        public List<String> lost = new ArrayList<String>();
    }

    public static final int ALIVE = 0, DESTROYED = 1, RETREATED = 2, WATCHED = 3, LEFT = 4;

    /** One player (the host included). */
    public static final class Pilot {
        public String name = "", ship = "";
        public int slot, status, kills, fighterKills;
        public float damageDealt, shieldDamageDealt, damageTaken;
    }

    public static final int MAX_LOST = 12, MAX_PILOTS = 16;

    private static String cap(String s, int n) { return s == null ? "" : s.length() > n ? s.substring(0, n) : s; }

    private static float clamp(float v) { return v != v ? 0f : Math.max(0f, Math.min(9e6f, v)); }

    public byte[] encode() {
        Msg.W w = new Msg.W();
        try {
            w.out.writeByte(outcome);
            w.out.writeFloat(clamp(seconds));
            for (Side s : sides) {
                w.out.writeShort(Math.min(s.deployed, 30000));
                w.out.writeShort(Math.min(s.destroyed, 30000));
                w.out.writeShort(Math.min(s.disabled, 30000));
                w.out.writeShort(Math.min(s.retreated, 30000));
                w.out.writeShort(Math.min(s.fightersLost, 30000));
                w.out.writeByte(Math.min(s.lost.size(), MAX_LOST));
                for (int i = 0; i < s.lost.size() && i < MAX_LOST; i++) w.out.writeUTF(cap(s.lost.get(i), 80));
            }
            w.out.writeByte(Math.min(pilots.size(), MAX_PILOTS));
            for (int i = 0; i < pilots.size() && i < MAX_PILOTS; i++) {
                Pilot p = pilots.get(i);
                w.out.writeUTF(cap(p.name, 40));
                w.out.writeUTF(cap(p.ship, 80));
                w.out.writeByte(p.slot);
                w.out.writeByte(p.status);
                w.out.writeShort(Math.min(p.kills, 30000));
                w.out.writeShort(Math.min(p.fighterKills, 30000));
                w.out.writeFloat(clamp(p.damageDealt));
                w.out.writeFloat(clamp(p.shieldDamageDealt));
                w.out.writeFloat(clamp(p.damageTaken));
            }
        } catch (IOException ex) { throw new IllegalStateException(ex); }
        return w.done();
    }

    public static BattleReport decode(byte[] data) throws IOException {
        In in = Msg.reader(data);
        BattleReport r = new BattleReport();
        r.outcome = in.readByte() & 0xFF;
        r.seconds = in.readFloat();
        if (r.outcome > 2 || Snapshot.bad(r.seconds)) throw new IOException("impossible report");
        for (Side s : r.sides) {
            s.deployed = in.readShort() & 0xFFFF;
            s.destroyed = in.readShort() & 0xFFFF;
            s.disabled = in.readShort() & 0xFFFF;
            s.retreated = in.readShort() & 0xFFFF;
            s.fightersLost = in.readShort() & 0xFFFF;
            int n = in.readByte() & 0xFF;
            if (n > MAX_LOST) throw new IOException("too many losses listed");
            for (int i = 0; i < n; i++) s.lost.add(Roster.clean(in.readUTF()));
        }
        int np = in.readByte() & 0xFF;
        if (np > MAX_PILOTS) throw new IOException("too many players");
        for (int i = 0; i < np; i++) {
            Pilot p = new Pilot();
            p.name = HostSession.cleanName(in.readUTF());
            p.ship = Roster.clean(in.readUTF());
            p.slot = in.readByte() & 0xFF;
            p.status = in.readByte() & 0xFF;
            p.kills = in.readShort() & 0xFFFF;
            p.fighterKills = in.readShort() & 0xFFFF;
            p.damageDealt = in.readFloat();
            p.shieldDamageDealt = in.readFloat();
            p.damageTaken = in.readFloat();
            if (p.status > LEFT || Snapshot.bad(p.damageDealt) || Snapshot.bad(p.shieldDamageDealt) || Snapshot.bad(p.damageTaken)) throw new IOException("impossible player line");
            r.pilots.add(p);
        }
        return r;
    }
}

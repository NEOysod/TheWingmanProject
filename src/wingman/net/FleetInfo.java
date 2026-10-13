package wingman.net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One of the campaign fleets in the host's battle (Msg.FLEET, host -> joiner, before the first ship of it): its
 * faction, name, memory flags and commander, so a joiner's copies of its ships belong to a stand-in of that very
 * fleet. Scripts ask a ship's fleet things like "is this the player's fleet?", "does it carry this flag?" and
 * "who commands it?" (UAF's mimics, for one, are made by a fleet's flag and its commander's skill).
 */
public final class FleetInfo {
    /** The host's number for this fleet, as ShipSpawn.fleetKey gives it. */
    public int key;
    public int owner;
    public boolean playerFleet;
    public String faction = "", name = "";
    public Map<String, Object> memory = new LinkedHashMap<String, Object>();
    /** null: no commander (or the default one). */
    public Person commander;

    public static final int MAX_MEMORY_BYTES = 64 * 1024;

    /**
     * The fleet's other ships, those not (yet) in the battle: scripts that look over a ship's fleet (a bonus for
     * having a carrier along, the size of the fleet for a fleet-wide skill) count them too. A joiner's stand-in
     * fleet holds a simple copy of each (see CampaignStandIn.onFleet).
     */
    public List<Reserve> reserves = new ArrayList<Reserve>();
    public static final int MAX_RESERVES = 200, MAX_MODS = 40, MAX_WINGS = 12;

    /** One ship of the fleet, as much of it as fleet-wide scripts look at. */
    public static final class Reserve {
        public String id = "", hullId = "", name = "";
        public List<String> hullmods = new ArrayList<String>(), permaMods = new ArrayList<String>(), sMods = new ArrayList<String>(), wings = new ArrayList<String>();
        public float cr = 0.7f;
        public boolean mothballed, flagship;
    }

    private static void writeList(Out out, List<String> l, int max) throws IOException {
        int n = Math.min(l.size(), max);
        out.writeByte(n);
        for (int i = 0; i < n; i++) out.writeUTF(cap(l.get(i)));
    }

    private static List<String> readList(In in, int max) throws IOException {
        int n = in.readByte() & 0xFF;
        if (n > max) throw new IOException("list too long: " + n);
        List<String> l = new ArrayList<String>(n);
        for (int i = 0; i < n; i++) l.add(in.readUTF());
        return l;
    }

    private static String cap(String s) { return s == null ? "" : s.length() > 200 ? s.substring(0, 200) : s; }

    public byte[] encode() {
        Msg.W w = new Msg.W();
        try {
            w.out.writeInt(key);
            w.out.writeByte(owner);
            w.out.writeBoolean(playerFleet);
            w.out.writeUTF(cap(faction));
            w.out.writeUTF(cap(name));
            Plain.writeMap(w.out, memory);
            w.out.writeBoolean(commander != null);
            if (commander != null) commander.write(w.out);
            int n = Math.min(reserves.size(), MAX_RESERVES);
            w.out.writeShort(n);
            for (int i = 0; i < n; i++) {
                Reserve r = reserves.get(i);
                w.out.writeUTF(cap(r.id));
                w.out.writeUTF(cap(r.hullId));
                w.out.writeUTF(cap(r.name));
                writeList(w.out, r.hullmods, MAX_MODS);
                writeList(w.out, r.permaMods, MAX_MODS);
                writeList(w.out, r.sMods, MAX_MODS);
                writeList(w.out, r.wings, MAX_WINGS);
                w.out.writeFloat(r.cr);
                w.out.writeByte((r.mothballed ? 1 : 0) | (r.flagship ? 2 : 0));
            }
        } catch (IOException e) { throw new IllegalStateException(e); }
        return w.done();
    }

    public static FleetInfo decode(byte[] data) throws IOException {
        In in = Msg.reader(data);
        FleetInfo f = new FleetInfo();
        f.key = in.readInt();
        f.owner = in.readByte();
        if (f.owner < 0 || f.owner > 1) throw new IOException("impossible side");
        f.playerFleet = in.readBoolean();
        f.faction = in.readUTF();
        f.name = in.readUTF();
        f.memory = Plain.readMap(in);
        if (in.readBoolean()) f.commander = Person.read(in);
        int n = in.readShort() & 0xFFFF;
        if (n > MAX_RESERVES) throw new IOException("too many ships: " + n);
        for (int i = 0; i < n; i++) {
            Reserve r = new Reserve();
            r.id = in.readUTF();
            r.hullId = in.readUTF();
            r.name = in.readUTF();
            r.hullmods = readList(in, MAX_MODS);
            r.permaMods = readList(in, MAX_MODS);
            r.sMods = readList(in, MAX_MODS);
            r.wings = readList(in, MAX_WINGS);
            r.cr = in.readFloat();
            if (Snapshot.bad(r.cr)) throw new IOException("impossible readiness");
            r.cr = Math.max(0f, Math.min(1f, r.cr));
            int flags = in.readByte();
            r.mothballed = (flags & 1) != 0;
            r.flagship = (flags & 2) != 0;
            f.reserves.add(r);
        }
        return f;
    }
}

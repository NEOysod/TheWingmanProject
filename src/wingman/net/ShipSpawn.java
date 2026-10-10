package wingman.net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Everything a client needs to rebuild one of the host's ships locally. */
public final class ShipSpawn {
    public int netId;
    public int owner;
    public String hullId = "";
    public String name = "";
    /** Non-empty for a fighter: the wing it belongs to. Fighters are rebuilt from the wing, not from a loadout. */
    public String wingId = "";
    /** Fighters only: the host's number for the wing this fighter flies in, its carrier (-1 none) and that carrier's bay. */
    public int wingNet = -1, carrierId = -1, bay = -1;
    /** What sort of thing this is: an ordinary ship or fighter, a module of a multi-part ship, or a drone. */
    public static final int SHIP = 0, MODULE = 1, DRONE = 2;
    public int kind = SHIP;
    /** Module: the ship it is part of, and the slot on that ship it sits in. Drone: the ship that launched it. */
    public int parentId = -1;
    public String slotId = "";
    /** A multi-part ship: the numbers of its modules. Their own descriptions are always sent before this one. */
    public List<Integer> moduleIds = new ArrayList<Integer>();
    public int vents, caps;
    public float x, y, facing;
    /** Combat readiness, 0..1. */
    public float cr = 1f;
    public List<String> hullMods = new ArrayList<String>();
    /** Which of those are built in with a story point (they give a bonus), permanent, or switched off. */
    public List<String> sMods = new ArrayList<String>();
    public List<String> permaMods = new ArrayList<String>();
    public List<String> sModdedBuiltIns = new ArrayList<String>();
    public List<String> suppressedMods = new ArrayList<String>();

    /** The officer in command, whose skills change what the ship can do. */
    public boolean hasCaptain;
    public String captainFirst = "", captainLast = "", captainPortrait = "", captainAiCore = "";
    public int captainLevel;
    public List<String> skillIds = new ArrayList<String>();
    public List<Float> skillLevels = new ArrayList<Float>();
    /**
     * The skills of the commander of the fleet this ship belongs to: the ones that apply to every ship in
     * the fleet. Empty if there is no commander or they have none.
     */
    public List<String> fleetSkillIds = new ArrayList<String>();
    public List<Float> fleetSkillLevels = new ArrayList<Float>();

    /** What all of the above adds up to on the host, so the joiner's copy can be checked against it. */
    public float maxFlux, maxHull, maxSpeed;
    /** Fitted (non built-in) weapons: slot id -> weapon id, as two parallel lists. */
    public List<String> weaponSlots = new ArrayList<String>();
    public List<String> weaponIds = new ArrayList<String>();
    /** The ship's weapon groups exactly as the host has them, so "group 2" means the same guns on both sides. */
    public static final class Group {
        public String type = "LINKED";
        public boolean autofire;
        public List<String> slots = new ArrayList<String>();
    }
    public List<Group> groups = new ArrayList<Group>();
    /** Fighter wing in each bay ("" for an empty bay). Needed so a joiner's copy of a carrier has fighter controls. */
    public List<String> wings = new ArrayList<String>();
    /** Slot ids in the order the host reports weapons in snapshots. */
    public List<String> weaponOrder = new ArrayList<String>();
    /** The weapon actually sitting in each of those slots on the host, whatever put it there. */
    public List<String> weaponOrderIds = new ArrayList<String>();

    private static void writeList(Msg.W w, List<String> l) throws IOException {
        w.out.writeShort(l.size());
        for (String s : l) w.out.writeUTF(s == null ? "" : s);
    }

    private static List<String> readList(In in) throws IOException {
        int n = in.readShort() & 0xFFFF;
        List<String> l = new ArrayList<String>(n);
        for (int i = 0; i < n; i++) l.add(in.readUTF());
        return l;
    }

    public byte[] encode() {
        Msg.W w = new Msg.W();
        try {
            w.out.writeInt(netId);
            w.out.writeByte(owner);
            w.out.writeUTF(hullId);
            w.out.writeUTF(name);
            w.out.writeUTF(wingId);
            w.out.writeInt(wingNet);
            w.out.writeInt(carrierId);
            w.out.writeByte(bay);
            w.out.writeByte(kind);
            w.out.writeInt(parentId);
            w.out.writeUTF(slotId);
            w.out.writeByte(Math.min(moduleIds.size(), 255));
            for (int i = 0; i < moduleIds.size() && i < 255; i++) w.out.writeInt(moduleIds.get(i));
            w.out.writeShort(vents);
            w.out.writeShort(caps);
            w.out.writeFloat(x);
            w.out.writeFloat(y);
            w.out.writeFloat(facing);
            w.out.writeFloat(cr);
            writeList(w, hullMods);
            writeList(w, sMods);
            writeList(w, permaMods);
            writeList(w, sModdedBuiltIns);
            writeList(w, suppressedMods);
            w.out.writeBoolean(hasCaptain);
            w.out.writeUTF(captainFirst);
            w.out.writeUTF(captainLast);
            w.out.writeUTF(captainPortrait);
            w.out.writeUTF(captainAiCore);
            w.out.writeInt(captainLevel);
            writeList(w, skillIds);
            for (int i = 0; i < skillIds.size(); i++) w.out.writeFloat(i < skillLevels.size() ? skillLevels.get(i) : 1f);
            writeList(w, fleetSkillIds);
            for (int i = 0; i < fleetSkillIds.size(); i++) w.out.writeFloat(i < fleetSkillLevels.size() ? fleetSkillLevels.get(i) : 1f);
            w.out.writeFloat(maxFlux);
            w.out.writeFloat(maxHull);
            w.out.writeFloat(maxSpeed);
            writeList(w, weaponSlots);
            writeList(w, weaponIds);
            writeList(w, weaponOrder);
            writeList(w, weaponOrderIds);
            writeList(w, wings);
            w.out.writeByte(groups.size());
            for (Group g : groups) {
                w.out.writeUTF(g.type);
                w.out.writeBoolean(g.autofire);
                writeList(w, g.slots);
            }
        } catch (IOException e) { throw new IllegalStateException(e); }
        return w.done();
    }

    public static ShipSpawn decode(byte[] data) throws IOException {
        In in = Msg.reader(data);
        ShipSpawn s = new ShipSpawn();
        s.netId = in.readInt();
        s.owner = in.readByte();
        s.hullId = in.readUTF();
        s.name = in.readUTF();
        s.wingId = in.readUTF();
        s.wingNet = in.readInt();
        s.carrierId = in.readInt();
        s.bay = in.readByte();
        s.kind = in.readByte();
        if (s.kind < SHIP || s.kind > DRONE) s.kind = SHIP;
        s.parentId = in.readInt();
        s.slotId = in.readUTF();
        int modules = in.readByte() & 0xFF;
        for (int i = 0; i < modules; i++) s.moduleIds.add(in.readInt());
        s.vents = in.readShort();
        s.caps = in.readShort();
        s.x = in.readFloat();
        s.y = in.readFloat();
        s.facing = in.readFloat();
        s.cr = in.readFloat();
        s.hullMods = readList(in);
        s.sMods = readList(in);
        s.permaMods = readList(in);
        s.sModdedBuiltIns = readList(in);
        s.suppressedMods = readList(in);
        s.hasCaptain = in.readBoolean();
        s.captainFirst = in.readUTF();
        s.captainLast = in.readUTF();
        s.captainPortrait = in.readUTF();
        s.captainAiCore = in.readUTF();
        s.captainLevel = in.readInt();
        s.skillIds = readList(in);
        for (int i = 0; i < s.skillIds.size(); i++) s.skillLevels.add(in.readFloat());
        s.fleetSkillIds = readList(in);
        for (int i = 0; i < s.fleetSkillIds.size(); i++) s.fleetSkillLevels.add(in.readFloat());
        s.maxFlux = in.readFloat();
        s.maxHull = in.readFloat();
        s.maxSpeed = in.readFloat();
        // Guard rail: impossible numbers (NaN, infinity, absurd sizes) mean a broken or hostile message; it is
        // refused whole rather than handed to the game, which may not survive a ship at NaN.
        if (Snapshot.bad(s.x) || Snapshot.bad(s.y) || Snapshot.bad(s.facing) || Snapshot.bad(s.maxFlux) || Snapshot.bad(s.maxHull) || Snapshot.bad(s.maxSpeed))
            throw new IOException("ship description with impossible numbers");
        for (Float f : s.skillLevels) if (Snapshot.bad(f)) throw new IOException("impossible skill level");
        for (Float f : s.fleetSkillLevels) if (Snapshot.bad(f)) throw new IOException("impossible skill level");
        s.cr = Snapshot.clamp(s.cr, 0f, 1f);
        s.weaponSlots = readList(in);
        s.weaponIds = readList(in);
        s.weaponOrder = readList(in);
        s.weaponOrderIds = readList(in);
        s.wings = readList(in);
        int gn = in.readByte() & 0xFF;
        for (int i = 0; i < gn; i++) {
            Group g = new Group();
            g.type = in.readUTF();
            g.autofire = in.readBoolean();
            g.slots = readList(in);
            s.groups.add(g);
        }
        return s;
    }
}

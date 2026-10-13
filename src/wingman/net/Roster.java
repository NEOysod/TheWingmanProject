package wingman.net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The pre-battle lobby (Msg.ROSTER, host -> joiners): sent while the host is on the campaign map (and when it
 * changes), so joiners can connect before a battle and choose a ship from the host's fleet. A pick is a wish
 * for the next battle: if that ship is deployed, the host seats its picker in it at the start.
 */
public final class Roster {
    /** Most fleet members sent; a bigger fleet is cut (the biggest ships come first). */
    public static final int MAX_MEMBERS = 120;
    public static final int FLAGSHIP = 1, MOTHBALLED = 2, CANT_FLY = 4;
    public static final int MAX_MODULES = 16, MAX_SKILLS = 40, MAX_GEAR = 40, MAX_FITS = 160;

    public static final class Member {
        public String id = "", shipName = "", hullName = "", hullSize = "FRIGATE", designation = "", officer = "";
        public int cr = -1, officerLevel, flags;
        /** The player who picked it ("" if nobody). */
        public String pickedBy = "";
        /** For CANT_FLY: why ("a station", ...). */
        public String note = "";
        /** For the lobby's ship card: the hull's picture, and the loadout as short lines. */
        public String sprite = "", weapons = "", hullmods = "", personality = "", hullId = "";
        /** A multi-part ship's modules, for its picture: "hullId|sprite|x|y|angle" (slot place on the ship, ship units). */
        public List<String> modules = new ArrayList<String>();
        /** The officer's portrait (a sprite path), and skills as "id:level" (level 2: elite). */
        public String portrait = "";
        public List<String> skills = new ArrayList<String>();
        /** Weapons and wings for their pictures: "w|id|count" or "f|wingId|count", as fitted. */
        public List<String> gear = new ArrayList<String>();
        /** Weapons in their slots, for the picture: "i|slot|weaponId" (i: -1 the main hull, else the module's place in modules). */
        public List<String> fits = new ArrayList<String>();
        /** The ship's D-mods (damage it carries), names, comma-separated. */
        public String dmods = "";
    }

    public static final class Player {
        public String name = "";
        public int slot;
    }

    public String hostName = "";
    /** 0: on the campaign map (or in a menu), 1: in a battle. */
    public int hostState;
    public String maxShipSize = "CAPITAL_SHIP";
    public List<Player> players = new ArrayList<Player>();
    public List<Member> members = new ArrayList<Member>();

    private static String cap(String s, int n) { return s == null ? "" : s.length() > n ? s.substring(0, n) : s; }

    public byte[] encode() {
        Msg.W w = new Msg.W();
        try {
            w.out.writeUTF(cap(hostName, 40));
            w.out.writeByte(hostState);
            w.out.writeUTF(cap(maxShipSize, 20));
            w.out.writeByte(Math.min(players.size(), 16));
            for (int i = 0; i < players.size() && i < 16; i++) {
                w.out.writeUTF(cap(players.get(i).name, 40));
                w.out.writeByte(players.get(i).slot);
            }
            int n = Math.min(members.size(), MAX_MEMBERS);
            w.out.writeByte(n);
            for (int i = 0; i < n; i++) {
                Member m = members.get(i);
                w.out.writeUTF(cap(m.id, 64));
                w.out.writeUTF(cap(m.shipName, 60));
                w.out.writeUTF(cap(m.hullName, 60));
                w.out.writeUTF(cap(m.hullSize, 20));
                w.out.writeUTF(cap(m.designation, 40));
                w.out.writeByte(Math.max(-1, Math.min(100, m.cr)) + 1);
                w.out.writeUTF(cap(m.officer, 60));
                w.out.writeByte(Math.max(0, Math.min(99, m.officerLevel)));
                w.out.writeByte(m.flags);
                w.out.writeUTF(cap(m.pickedBy, 40));
                w.out.writeUTF(cap(m.note, 60));
                w.out.writeUTF(cap(m.sprite, 120));
                w.out.writeUTF(cap(m.weapons, 400));
                w.out.writeUTF(cap(m.hullmods, 400));
                w.out.writeUTF(cap(m.personality, 30));
                w.out.writeUTF(cap(m.hullId, 100));
                w.out.writeByte(Math.min(m.modules.size(), MAX_MODULES));
                for (int k = 0; k < m.modules.size() && k < MAX_MODULES; k++) w.out.writeUTF(cap(m.modules.get(k), 300));
                w.out.writeUTF(cap(m.portrait, 200));
                w.out.writeByte(Math.min(m.skills.size(), MAX_SKILLS));
                for (int k = 0; k < m.skills.size() && k < MAX_SKILLS; k++) w.out.writeUTF(cap(m.skills.get(k), 120));
                w.out.writeByte(Math.min(m.gear.size(), MAX_GEAR));
                for (int k = 0; k < m.gear.size() && k < MAX_GEAR; k++) w.out.writeUTF(cap(m.gear.get(k), 160));
                w.out.writeByte(Math.min(m.fits.size(), MAX_FITS));
                for (int k = 0; k < m.fits.size() && k < MAX_FITS; k++) w.out.writeUTF(cap(m.fits.get(k), 200));
                w.out.writeUTF(cap(m.dmods, 400));
            }
        } catch (IOException ex) { throw new IllegalStateException(ex); }
        return w.done();
    }

    public static Roster decode(byte[] data) throws IOException {
        In in = Msg.reader(data);
        Roster r = new Roster();
        r.hostName = HostSession.cleanName(in.readUTF());
        r.hostState = in.readByte() & 0xFF;
        r.maxShipSize = in.readUTF();
        int np = in.readByte() & 0xFF;
        if (np > 16) throw new IOException("too many players");
        for (int i = 0; i < np; i++) {
            Player p = new Player();
            p.name = HostSession.cleanName(in.readUTF());
            p.slot = in.readByte() & 0xFF;
            r.players.add(p);
        }
        int n = in.readByte() & 0xFF;
        if (n > MAX_MEMBERS) throw new IOException("too many fleet members");
        for (int i = 0; i < n; i++) {
            Member m = new Member();
            m.id = in.readUTF();
            m.shipName = clean(in.readUTF());
            m.hullName = clean(in.readUTF());
            m.hullSize = in.readUTF();
            m.designation = clean(in.readUTF());
            m.cr = (in.readByte() & 0xFF) - 1;
            m.officer = clean(in.readUTF());
            m.officerLevel = in.readByte() & 0xFF;
            m.flags = in.readByte() & 0xFF;
            m.pickedBy = HostSession.cleanName(in.readUTF());
            m.note = clean(in.readUTF());
            m.sprite = in.readUTF();
            m.weapons = cleanLong(in.readUTF());
            m.hullmods = cleanLong(in.readUTF());
            m.personality = clean(in.readUTF());
            m.hullId = in.readUTF();
            int nm = in.readByte() & 0xFF;
            if (nm > MAX_MODULES) throw new IOException("too many modules");
            for (int k = 0; k < nm; k++) m.modules.add(in.readUTF());
            m.portrait = in.readUTF();
            int ns = in.readByte() & 0xFF;
            if (ns > MAX_SKILLS) throw new IOException("too many skills");
            for (int k = 0; k < ns; k++) m.skills.add(in.readUTF());
            int ng = in.readByte() & 0xFF;
            if (ng > MAX_GEAR) throw new IOException("too many weapons");
            for (int k = 0; k < ng; k++) m.gear.add(in.readUTF());
            int nf = in.readByte() & 0xFF;
            if (nf > MAX_FITS) throw new IOException("too many weapons in slots");
            for (int k = 0; k < nf; k++) m.fits.add(in.readUTF());
            m.dmods = cleanLong(in.readUTF());
            r.members.add(m);
        }
        return r;
    }

    /** Text from the host shown on screen: printable characters only, one line. */
    static String clean(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 80; i++) {
            char c = s.charAt(i);
            sb.append(c < 32 || (c >= 127 && c < 160) || Character.isSurrogate(c) ? ' ' : c);
        }
        return sb.toString().trim();
    }

    /** Like clean(), for the longer loadout lines (up to 400 characters). */
    static String cleanLong(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 400; i++) {
            char c = s.charAt(i);
            sb.append(c < 32 || (c >= 127 && c < 160) || Character.isSurrogate(c) ? ' ' : c);
        }
        return sb.toString().trim();
    }

    /** The member a player picked, or null. */
    public Member pickOf(String player) {
        for (Member m : members) if (m.pickedBy.equalsIgnoreCase(player)) return m;
        return null;
    }

    /** "ISS Child of Venus, Onslaught" or just the class. */
    public static String label(Member m) {
        return m.shipName.isEmpty() ? m.hullName : m.shipName + ", " + m.hullName;
    }
}

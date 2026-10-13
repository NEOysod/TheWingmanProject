package wingman.net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Friend refit (Msg.REFIT_REQUEST, joiner -> host): the loadout a joiner wants for the ship they picked, as
 * changes to it as the host last described it (RefitData). Each weapon slot or fighter bay changed says what goes
 * in, where it comes from (the host's cargo, storage, or a submarket to buy it from) and where the one taken out
 * goes (cargo, storage, or a submarket to sell it to). Hull mods added and removed, those to build in (S-mods),
 * vents and capacitors; and the cost the joiner was shown, so the host can see if anything changed meanwhile.
 * Nothing happens until the host accepts it, after checking all of it again (see wingman.FriendRefit).
 */
public final class RefitRequest {
    /** Where an item comes from or goes to, besides a submarket (by its id). */
    public static final String CARGO = "cargo", STORAGE = "storage";
    public static final int MAX_CHANGES = 200, MAX_MODS = 100, MAX_NOTE = 160;

    /** One weapon slot (place: the slot id) or fighter bay (place: the bay's number from 0), and what goes in it. */
    public static final class Change {
        public boolean wing;
        public String place = "";
        /** The weapon or wing id going in; "" leaves it empty. */
        public String item = "";
        /** Where the new one comes from (CARGO, STORAGE or a submarket id); "" when nothing goes in. */
        public String from = "";
        /** Where the one taken out goes (CARGO, STORAGE or a submarket id to sell it to). */
        public String to = CARGO;
    }

    public String memberId = "", note = "";
    public List<Change> changes = new ArrayList<Change>();
    public List<String> addMods = new ArrayList<String>(), removeMods = new ArrayList<String>(), buildIn = new ArrayList<String>();
    public int vents, caps;
    /** What the joiner was shown: credits to pay (negative: to receive) and story points to spend. */
    public int expectCredits, expectStoryPoints;

    /** The change for a slot or bay, or null. */
    public Change change(String place, boolean wing) {
        for (Change c : changes) if (c.wing == wing && c.place.equals(place)) return c;
        return null;
    }

    private static String cap(String s, int n) { return s == null ? "" : s.length() > n ? s.substring(0, n) : s; }

    private static void writeStrings(Out o, List<String> l) throws IOException {
        int n = Math.min(l.size(), MAX_MODS);
        o.writeVarInt(n);
        for (int i = 0; i < n; i++) o.writeUTF(cap(l.get(i), 100));
    }

    private static void readStrings(In in, List<String> l) throws IOException {
        int n = in.readVarInt();
        if (n < 0 || n > MAX_MODS) throw new IOException("too many hull mods");
        for (int i = 0; i < n; i++) l.add(id(in, 100));
    }

    private static String id(In in, int max) throws IOException {
        String s = in.readUTF();
        if (s.length() > max) throw new IOException("id too long");
        return s;
    }

    public byte[] encode() {
        Msg.W w = new Msg.W();
        try {
            Out o = w.out;
            o.writeUTF(cap(memberId, 64));
            o.writeUTF(cap(note, MAX_NOTE));
            int n = Math.min(changes.size(), MAX_CHANGES);
            o.writeVarInt(n);
            for (int i = 0; i < n; i++) {
                Change c = changes.get(i);
                o.writeBoolean(c.wing);
                o.writeUTF(cap(c.place, 100));
                o.writeUTF(cap(c.item, 100));
                o.writeUTF(cap(c.from, 64));
                o.writeUTF(cap(c.to, 64));
            }
            writeStrings(o, addMods);
            writeStrings(o, removeMods);
            writeStrings(o, buildIn);
            o.writeVarInt(Math.max(0, Math.min(1000, vents)));
            o.writeVarInt(Math.max(0, Math.min(1000, caps)));
            o.writeInt(expectCredits);
            o.writeVarInt(Math.max(0, Math.min(1000, expectStoryPoints)));
        } catch (IOException ex) { throw new IllegalStateException(ex); }
        return w.done();
    }

    public static RefitRequest decode(byte[] data) throws IOException {
        In in = Msg.reader(data);
        RefitRequest r = new RefitRequest();
        r.memberId = id(in, 64);
        r.note = Roster.cleanLong(in.readUTF());
        if (r.note.length() > MAX_NOTE) r.note = r.note.substring(0, MAX_NOTE);
        int n = in.readVarInt();
        if (n < 0 || n > MAX_CHANGES) throw new IOException("too many changes");
        for (int i = 0; i < n; i++) {
            Change c = new Change();
            c.wing = in.readBoolean();
            c.place = id(in, 100);
            c.item = id(in, 100);
            c.from = id(in, 64);
            c.to = id(in, 64);
            if (r.change(c.place, c.wing) != null) throw new IOException("a slot changed twice");
            r.changes.add(c);
        }
        readStrings(in, r.addMods);
        readStrings(in, r.removeMods);
        readStrings(in, r.buildIn);
        r.vents = in.readVarInt();
        r.caps = in.readVarInt();
        if (r.vents < 0 || r.vents > 1000 || r.caps < 0 || r.caps > 1000) throw new IOException("impossible vents or capacitors");
        r.expectCredits = in.readInt();
        r.expectStoryPoints = in.readVarInt();
        if (r.expectStoryPoints < 0 || r.expectStoryPoints > 1000) throw new IOException("impossible story points");
        return r;
    }
}

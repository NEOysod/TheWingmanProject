package wingman.net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Who is connected, and where each player is in the two stages before a battle: loading it, choosing a ship
 * (or to watch), then ready.
 */
public final class Lobby {
    public static final class Entry {
        public String name = "";
        public boolean ready;
        /** They chose to go without a ship (watch). */
        public boolean watching;
        /** Ships this player has built locally, and ships they could not build (content they don't have). */
        public int built, skipped;
        /** The ship this player is flying, as shown to everyone ("" if none): "Name (Hull class)". */
        public String ship = "";
        /** The player's number (P2, P3...) and the ship they fly (-1: none), for the tags over ships. */
        public int slot, shipNetId = -1;
    }

    /** The largest ship a joiner may fly: FRIGATE, DESTROYER, CRUISER or CAPITAL_SHIP. */
    public String maxShipSize = "CAPITAL_SHIP";

    /** True while the host is holding the battle paused for the ready check. */
    public boolean readyCheck;
    public String hostName = "";
    /** Number of ships (not fighters) in the host's battle: what every player should have. */
    public int expected;
    public List<Entry> players = new ArrayList<Entry>();

    public boolean isSynced(Entry e) { return e.built + e.skipped >= expected; }

    public boolean allReady() {
        for (Entry e : players) if (!e.ready || !isSynced(e)) return false;
        return true;
    }

    public byte[] encode() {
        Msg.W w = new Msg.W();
        try {
            w.out.writeBoolean(readyCheck);
            w.out.writeUTF(hostName);
            w.out.writeInt(expected);
            w.out.writeUTF(maxShipSize);
            w.out.writeByte(players.size());
            for (Entry e : players) {
                w.out.writeUTF(e.name);
                w.out.writeBoolean(e.ready);
                w.out.writeInt(e.built);
                w.out.writeInt(e.skipped);
                w.out.writeUTF(e.ship);
                w.out.writeByte(e.slot);
                w.out.writeInt(e.shipNetId);
                w.out.writeBoolean(e.watching);
            }
        } catch (IOException ex) { throw new IllegalStateException(ex); }
        return w.done();
    }

    public static Lobby decode(byte[] data) throws IOException {
        In in = Msg.reader(data);
        Lobby l = new Lobby();
        l.readyCheck = in.readBoolean();
        l.hostName = in.readUTF();
        l.expected = in.readInt();
        l.maxShipSize = in.readUTF();
        int n = in.readByte() & 0xFF;
        for (int i = 0; i < n; i++) {
            Entry e = new Entry();
            e.name = HostSession.cleanName(in.readUTF());
            e.ready = in.readBoolean();
            e.built = in.readInt();
            e.skipped = in.readInt();
            e.ship = in.readUTF();
            e.slot = in.readByte() & 0xFF;
            e.shipNetId = in.readInt();
            e.watching = in.readBoolean();
            l.players.add(e);
        }
        return l;
    }

    /** One status line per player, the same wording on every screen. */
    public String describe(Entry e) {
        String load = e.built + "/" + expected + " ships";
        String who = (e.slot > 0 ? "P" + e.slot + "  " : "") + e.name;
        if (!isSynced(e)) return who + ":  loading, " + load;
        String miss = e.skipped > 0 ? ",  MISSING " + e.skipped + " (different mods than host)" : "";
        if (e.ship.isEmpty() && !e.watching) return who + ":  choosing a ship" + miss;
        return who + ":  " + (e.ready ? "READY" : "not ready") + ",  " + (e.ship.isEmpty() ? "watching (no ship)" : "flying " + e.ship) + miss;
    }
}

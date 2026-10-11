package wingman.net;

import java.io.IOException;

/** Message type ids and small helpers for building / reading payloads. */
public final class Msg {
    private Msg() {}

    public static final int PROTOCOL_VERSION = 49;

    // client -> host
    public static final byte HELLO = 1;     // int protocol, UTF name, ModList
    public static final byte INPUT = 2;     // InputState
    public static final byte SYNC_REQ = 3;  // (empty) client entered a fresh battle, resend everything
    public static final byte CLAIM = 4;     // int seq, int netId (-1 = I left my ship): sent when the player transfers command
    public static final byte BYE = 5;       // (empty)
    public static final byte READY = 6;     // boolean ready, int shipsBuilt, int shipsSkipped

    // host -> client
    public static final byte WELCOME = 10;  // int playerId, int maxPlayers, int snapshotRate, UTF hostName
    public static final byte REJECT = 11;   // UTF reason
    public static final byte SPAWN = 12;    // ShipSpawn
    public static final byte DESPAWN = 13;  // int netId, byte how
    public static final byte SNAPSHOT = 14; // Snapshot
    public static final byte ASSIGN = 15;   // int lastClaimSeqSeen, int netId (-1 = no ship)
    public static final byte MISSILE_SPAWN = 20;   // int id, UTF weaponId, int sourceNetId, byte owner, x, y, facing, vx, vy
    public static final byte MISSILE_DESPAWN = 21; // int id, byte how
    public static final byte RESET = 16;    // (empty) host started a new battle, drop all ships
    public static final byte NOTICE = 19;   // UTF text to show on screen

    public static final byte LOBBY = 22;    // Lobby
    public static final byte ALERT = 23;    // UTF title, UTF text: a pop-up the player dismisses

    public static final byte OBJECTIVES = 24; // count, then per objective: UTF type, x, y
    public static final byte ORDERS = 25;   // Orders

    public static final byte BATTLE_INFO = 26; // BattleInfo
    public static final byte ASTEROIDS = 27;  // count, then per asteroid: id, x, y, vx, vy, radius*10
    public static final byte NEBULA = 28;     // int tilesWide, int tilesHigh, boolean any, bits

    public static final byte PING = 31;       // sent by the network thread when a link is otherwise quiet; never reaches the game
    public static final byte BATTLE_END = 32; // host -> joiners: the battle is over, leave it (the connection stays)
    public static final byte CHECK = 33;      // host -> joiners: how many ships and fighters there should be, to catch a copy that has drifted
    public static final byte MISSILE_HIT = 34;  // a missile struck something: which one, what it hit, exactly where
    public static final byte SHOT_HIT = 35;     // a shot from a gun with an on-hit script struck something: which gun, what, exactly where
    public static final byte HURTS = 37;        // damage each ship took lately, by source and type (see Extras.Hurt)
    public static final byte STATS = 38;        // ship stats as they stand on the host (see Extras.Stats)
    public static final byte NOTES = 39;        // simple values scripts keep on ships and on the battle (see Extras.Note)
    public static final byte WRECK_SPLIT = 36;  // a wreck broke in two: the wreck's id, the id given to the piece that came off, the dice seed (long, 0 unknown), its place among that seed's cuts
    public static final byte MISSILE_TARGET = 30; // missile id, ship id: what a script-steered missile is aimed at
    public static final byte DAMAGE = 29;     // DamageState: armor grids and knocked-out weapons/engines, changed ships only

    public static final byte DESPAWN_REMOVED = 0;   // retreated / removed quietly
    public static final byte DESPAWN_DESTROYED = 1; // blew up
    public static final byte DESPAWN_WRECKED = 2;   // was destroyed before this player joined: make it a wreck, quietly
    public static final byte DESPAWN_NO_WRECK = 3;
    /** A missile that was neither shot down nor burnt out nor hit anything: a script took it away (a fuse went off, say). */
    public static final byte DESPAWN_SCRIPTED = 4;  // blew up, and the host is not keeping track of its wreck

    public static final byte SNAPSHOT_D = 40;   // a position update as changes since the one before (see SnapshotCodec)
    public static final byte NEED_KEY = 41;     // joiner -> host: I missed an update, send me the next one in full
    public static final byte ZIP_OK = 42;       // "you may send me a compressed stream"; handled by the connection, never reaches the game
    public static final byte ZIP_ON = 43;       // "everything after this message is compressed"; likewise
    public static final byte UDP_OFFER = 44;    // host -> joiner: long token, int port. "Updates and controls may also travel by UDP: say hello there"
    public static final byte UDP_READY = 45;    // joiner -> host: the host's answer came back by UDP, so that way works
    public static final byte UDP_MODE = 46;     // host -> joiner: byte 1 = send your controls by UDP from now on, 0 = by this connection again
    /** A ship changed sides mid-battle (boarded, hacked, a derelict captured): int net id, byte new side (0, 1 or neutral 100). */
    public static final byte SIDE = 47;
    /** host -> joiner: byte objective index (host's order), UTF tooltip text (at most 4,000 characters). Sent when it changes. */
    public static final byte OBJECTIVE_TEXT = 48;
    /** host -> joiner: a script's line in the battle's message feed (see MessageTap): byte indent, byte count, parts. */
    public static final byte COMBAT_MSG = 49;
    /** joiner -> host: int ship net id, UTF subsystem class: the player switched on a MagicLib subsystem (see Subsystems). */
    public static final byte SUBSYSTEM = 50;
    /** host -> joiner: int ship id, int carrier id (or -1), byte 1 = landing on that carrier, 2 = launched again. */
    public static final byte LANDING = 51;
    /** Either way: another mod's message carried for it (see ModChannel, wingman.api.WingmanAPI): UTF channel, int length, bytes. */
    public static final byte MOD = 52;
    /** host -> joiner: byte 1 = send controls as bundles with the joiner's clock and copies (see InputBuffer), 0 = plain. */
    public static final byte INPUT_MODE = 53;
    /** joiner -> host: a bundle of controls (see InputBuffer). */
    public static final byte INPUTS = 54;
    public static final byte[] EMPTY = new byte[0];

    /** Payload writer. */
    public static final class W {
        public final Out out = new Out();
        public byte[] done() { return out.toByteArray(); }
    }

    public static In reader(byte[] data) {
        return new In(data);
    }

    public static byte[] utf(String s) {
        W w = new W();
        try { w.out.writeUTF(s == null ? "" : s); } catch (IOException e) { throw new IllegalStateException(e); }
        return w.done();
    }

    public static String readUtf(byte[] data) {
        try { return reader(data).readUTF(); } catch (IOException e) { return ""; }
    }

    public static byte[] twoInts(int a, int b) {
        W w = new W();
        try { w.out.writeInt(a); w.out.writeInt(b); } catch (IOException e) { throw new IllegalStateException(e); }
        return w.done();
    }
}

package wingman.net;

import java.io.IOException;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The host's lobby: accepts connections, enforces the player cap, and keeps each player's latest input.
 * It lives for the whole game session (not one battle), so friends stay connected between fights.
 * Knows nothing about Starsector; the combat plugin drives it by calling pump() every frame.
 */
public final class HostSession {
    public static final class Player {
        public final int id;
        public final Conn conn;
        public String name = "Player";
        public final InputState input = new InputState();
        /** True until the current battle's full ship list has been sent to this player. */
        public boolean needsSync = true;
        public int shipNetId = -1;
        /** Game-side scratch space (the assigned ship and its original AI). */
        public Object ship, savedAI;
        /** The carrier held for this player while they fly one of its fighters (see CarrierHoldAI), or null. */
        public Object heldCarrier;
        public int prevFlags;
        /** The retreat requests seen in this player's controls last frame, to act on each one once. */
        public int retreatFlags;
        public int badMessages;
        /** Set when the player transferred command; the plugin decides whether the ship is free. */
        public boolean hasClaim;
        public int claim = -1, claimSeq;
        /** The claim was made by a mod's script on the host (Wingman's agent), not by the player. */
        public boolean claimByScript;
        /** MagicLib subsystems this player switched on, waiting for the game thread: ship net id, subsystem class. */
        public final java.util.List<Object[]> subsystemPresses = new java.util.ArrayList<Object[]>();
        long subsystemWindow;
        int subsystemsInWindow;
        /** Other mods' messages from this player in the current second (see ModChannel). */
        long modWindow;
        int modsInWindow;
        /** Ready-check report from this player; watching: they chose to go without a ship. */
        public boolean ready, watching;
        /** A pick from the pre-battle lobby (Msg.PICK) waiting for the game thread: fleet member id, "" for none. */
        public String pick;
        /** Friend refit: the member whose refit screen this player opened (Msg.REFIT_ASK), waiting for the game thread; "" closed. */
        public String refitAsk;
        public int built, skipped;
        /** This player came back on a new connection, and this is the old one being cleared away. */
        public boolean replaced;
        /** The player's number: the host is P1, joiners P2 onwards. Kept when they reconnect. */
        public int slot;
        /** This player's next position update must be one in full: they are new, or missed the last one. */
        public boolean needKey = true;
        /** The player's own keys for mods' LunaLib settings ("mod/field" -> key code), as their game told us. */
        public volatile java.util.Map<String, Integer> lunaKeys;

        // ---- position updates: what this player holds, and how they are reached ----
        /** Packs this player's updates; a new one for each battle. */
        SnapshotCodec.Encoder encoder;
        /** The update the next one is to be built on when everything arrives in order (TCP). */
        int baseTick = -1;
        int sinceKey;
        /** Which battle's packer the acknowledgements below belong to. */
        volatile int encoderEpoch;
        /** The newest update this player has said (by UDP) that it has read. */
        volatile int ackTick = -1;
        /** A number only this player was told, which marks its UDP datagrams as its own. */
        final long token = Double.doubleToLongBits(Math.random()) ^ (System.nanoTime() * 0x9E3779B97F4A7C15L) ^ ((long) System.identityHashCode(this) << 32);
        /** Where this player's UDP datagrams come from (a java.net.SocketAddress), once one has arrived. */
        volatile Object udpAddress;
        volatile long udpHeardAt, ackAt;
        /** The latest controls that came by UDP, waiting for the game thread. */
        volatile byte[] udpInput;
        /** Bundles of controls that came by UDP, each to be played in turn (see InputBuffer). */
        final java.util.concurrent.ConcurrentLinkedQueue<byte[]> udpBundles = new java.util.concurrent.ConcurrentLinkedQueue<byte[]>();
        /** This player's controls, played back at an even pace when steady controls are on. */
        public final InputBuffer buffer = new InputBuffer();
        /** This player's share of the bandwidth limit, if one is set (see Budget). */
        public final Budget budget = new Budget();
        /** The player has seen our answer come back by UDP. */
        boolean udpReady;
        /** Updates go to this player by UDP right now, and it sends its controls the same way. */
        public boolean udp;
        long udpOffAt, lastUpdateAt;
        public int udpFallbacks, keysSent, updatesByUdp, updatesByTcp;

        /** Called from the UDP thread: the player has read this update. */
        void ack(int epoch, int tick) {
            if (epoch != encoderEpoch) return; // about an earlier battle
            if (tick > ackTick) ackTick = tick;
            ackAt = System.currentTimeMillis();
        }

        Player(int id, Conn conn) { this.id = id; this.conn = conn; }
    }

    private static HostSession active;

    /** Returns the running session, (re)starting it if the port changed or nothing is running. */
    public static synchronized HostSession getOrStart(int port, int maxPlayers, int snapshotRate, String hostName)
            throws IOException {
        return getOrStart(port, maxPlayers, snapshotRate, hostName, null, MOD_CHECK_OFF);
    }

    public static final int MOD_CHECK_OFF = 0, MOD_CHECK_CONTENT = 1, MOD_CHECK_ALL = 2;

    /**
     * @param hostMods the host's enabled mods; joiners whose list differs are refused.
     * @param modCheck MOD_CHECK_OFF, MOD_CHECK_CONTENT (ignore utility mods) or MOD_CHECK_ALL.
     */
    public static synchronized HostSession getOrStart(int port, int maxPlayers, int snapshotRate, String hostName,
            ModList hostMods, int modCheck) throws IOException {
        if (active != null && (active.port != port || active.server.isClosed())) {
            active.shutdown();
            active = null;
        }
        if (active == null) active = new HostSession(port);
        active.maxPlayers = Math.max(2, maxPlayers);
        active.snapshotRate = snapshotRate;
        active.hostName = hostName;
        active.hostMods = hostMods;
        active.modCheck = hostMods == null ? MOD_CHECK_OFF : modCheck;
        return active;
    }

    private volatile boolean battleOpen;
    /** How many battles have been announced as over; for the log. */
    public volatile int battlesEnded;

    /** The host's battle is over: tell everyone to leave it. They stay connected for the next one. */
    public void endBattle() {
        if (!battleOpen) return;
        battleOpen = false;
        battlesEnded++;
        for (Player p : players) {
            p.conn.send(Msg.BATTLE_END, Msg.EMPTY);
            p.shipNetId = -1;
            p.ship = null;
            p.savedAI = null; // nothing of the finished battle is kept (an AI holds its ship, and the ship its battle)
            p.ready = false;
        }
    }

    /** For code outside a battle (the title screen): end the open battle, if there is one. */
    public static synchronized void endBattleIfOpen() {
        HostSession s = active;
        if (s == null || !s.battleOpen) return;
        s.pump(); // keeps handshakes and departures moving while nobody else is driving the session
        s.endBattle();
    }

    /** A link with nothing heard on it for this long (keep-alives are sent every second) is treated as dead. */
    public static final long LINK_TIMEOUT_MS = 12000;

    /**
     * Who may join. password: empty for an open game. gameVersion: the host's Starsector build; joiners on
     * another build are refused (null skips the check).
     */
    public void setAccess(String password, String gameVersion) {
        this.password = password == null ? "" : password;
        this.gameVersion = gameVersion;
    }

    public void setHostName(String name) { if (name != null && !name.trim().isEmpty()) hostName = name.trim(); }

    /** Remove a player. They are told why, and are not let back in until the game is restarted. */
    public void kick(Player p, String reason) {
        banned.add(p.name.toLowerCase());
        reject(p.conn, reason);
    }

    private volatile boolean steadyControls;

    /** Steady controls (see InputBuffer): joiners are told to send bundles, which the host plays back evenly. */
    public void setSteadyControls(boolean on) {
        if (on == steadyControls) return;
        steadyControls = on;
        for (Player p : players) { p.conn.send(Msg.INPUT_MODE, new byte[] { (byte) (on ? 1 : 0) }); p.buffer.clear(); }
    }

    public boolean steadyControls() { return steadyControls; }

    /** The open session on this PC, or null (for other mods, through wingman.api.WingmanAPI). */
    public static synchronized HostSession active() { return active; }

    /** Send to one player whether or not they are in the battle yet. */
    public boolean sendTo(int playerId, byte type, byte[] data) {
        for (Player p : players) if (p.id == playerId) { p.conn.send(type, data); return true; }
        return false;
    }

    public static synchronized void stopActive() {
        if (active != null) { active.shutdown(); active = null; }
    }

    private final int port;
    private final RawIO.Listener server;
    private final ConcurrentLinkedQueue<Conn> accepted = new ConcurrentLinkedQueue<Conn>();
    private final List<Conn> handshaking = new ArrayList<Conn>();
    private final List<Player> players = new ArrayList<Player>();
    private final List<Player> departed = new ArrayList<Player>();
    private volatile int maxPlayers = 4;
    private volatile int snapshotRate = 20;
    private volatile String hostName = "Host";
    private volatile ModList hostMods;
    private volatile int modCheck = MOD_CHECK_OFF;
    private int nextPlayerId = 1;
    private volatile String password = "";
    private volatile String gameVersion;
    private final java.util.Set<String> banned = new java.util.HashSet<String>();
    /** Set if the thread that accepts connections died; the plugin reports it. */
    public volatile String acceptError;
    /** Set whenever something the ready-check panel shows has changed. */
    public boolean lobbyDirty = true;

    private HostSession(int port) throws IOException {
        this.port = port;
        this.server = Transport.listen(port);
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (!server.isClosed()) {
                    try {
                        RawIO io = server.accept();
                        if (!admit(io)) { io.close(); continue; } // turned away at the door: no threads, no memory
                        Conn c = new Conn(io, STRANGER_MAX_FRAME, STRANGER_MAX_RATE);
                        synchronized (strangers) { strangers.add(c); }
                        accepted.add(c);
                    } catch (IOException e) {
                        if (server.isClosed()) return;
                    } catch (RuntimeException e) {
                        acceptError = String.valueOf(e); // e.g. the game's sandbox refusing a class
                        return;
                    }
                }
            }
        }, "Wingman-accept");
        t.setDaemon(true);
        t.start();
    }

    // ---- Guard rails at the door ----
    // Connections that haven't been let in yet are capped, in all and per PC, and checked when the next one
    // arrives, not only when the game drives pump() (which it doesn't on the campaign map). So a flood of
    // connections can't pile up threads and memory on the host.
    /** Largest message a stranger may send (a hello with a long mod list is well under this). */
    static final int STRANGER_MAX_FRAME = 256 * 1024;
    static final int STRANGER_MAX_RATE = 300;
    /** Connections not yet let in, in all and from one PC. */
    static final int MAX_STRANGERS = 16, MAX_STRANGERS_PER_PC = 4;
    /** A connection that hasn't sent one whole message in this long is closed. */
    static final long SILENT_LIMIT_MS = 5000;
    private final List<Conn> strangers = new ArrayList<Conn>();

    /** On the accepting thread: may this new connection in? Also clears out stale ones. */
    private boolean admit(RawIO io) {
        String from = hostPart(String.valueOf(io.remote()));
        long now = System.currentTimeMillis();
        synchronized (strangers) {
            int fromThere = 0;
            for (Iterator<Conn> it = strangers.iterator(); it.hasNext();) {
                Conn c = it.next();
                if (!c.isClosed() && !c.heardAnything() && now - c.openedAt > SILENT_LIMIT_MS) c.close("said nothing");
                if (c.isClosed()) { it.remove(); continue; }
                if (hostPart(c.getRemote()).equals(from)) fromThere++;
            }
            return strangers.size() < MAX_STRANGERS && fromThere < MAX_STRANGERS_PER_PC;
        }
    }

    /** Its hello has been dealt with: it no longer counts against the caps. */
    private void settled(Conn c) { synchronized (strangers) { strangers.remove(c); } }

    private static String hostPart(String r) {
        int i = r.lastIndexOf(':');
        return i > 0 ? r.substring(0, i) : r;
    }

    /** Wrong passwords per PC: after a few, that PC must wait before trying again. */
    static final int PASSWORD_TRIES = 3;
    static final long PASSWORD_WAIT_MS = 30000;
    private final Map<String, long[]> wrongPasswords = new HashMap<String, long[]>(); // address -> {count, last}

    /**
     * A name fit to show: no line breaks or control characters (so nobody can fake a "Host:" line), no
     * invisible direction marks, spaces tidied, and not too long.
     */
    public static String cleanName(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        boolean space = false;
        for (int i = 0; i < raw.length() && sb.length() < MAX_NAME; i++) {
            char ch = raw.charAt(i);
            boolean bad = ch < 32 || (ch >= 127 && ch < 160) || (ch >= 0x200B && ch <= 0x200F) || (ch >= 0x2028 && ch <= 0x202E)
                    || (ch >= 0x2066 && ch <= 0x2069) || ch == 0xFEFF || Character.isSurrogate(ch);
            if (bad || Character.isWhitespace(ch)) { space = sb.length() > 0; continue; }
            if (space) { sb.append(' '); space = false; }
            sb.append(ch);
        }
        return sb.toString().trim();
    }
    public static final int MAX_NAME = 24;

    public int getPort() { return port; }
    public int getMaxPlayers() { return maxPlayers; }
    public List<Player> getPlayers() { return players; }

    /** Players who left since the last call; the plugin hands their ships back to the AI. */
    public List<Player> drainDeparted() {
        List<Player> out = new ArrayList<Player>(departed);
        departed.clear();
        return out;
    }

    /** A new battle started on the host: everyone must drop their ships and get a fresh list. */
    public void beginBattle() {
        battleOpen = true;
        for (Player p : players) {
            p.conn.send(Msg.RESET, Msg.EMPTY);
            p.needsSync = true;
            p.shipNetId = -1;
            p.ship = null;
            p.savedAI = null;
            p.prevFlags = 0;
            p.hasClaim = false;
            p.subsystemPresses.clear();
            p.ready = false;
            p.built = 0;
            p.skipped = 0;
            p.input.flags = 0;
            p.buffer.clear();
            p.encoder = null; // a new battle: nothing of the last one is to be built on
            p.needKey = true;
        }
    }

    private Udp.Host udpHost;
    private boolean udpTried;
    /** Why UDP is not in use, for the log; empty if it is. */
    public String udpProblem = "";

    /** The UDP side, opened the first time it is wanted. Null if switched off or not to be had. */
    private Udp.Host udp() {
        if (!Conn.udp) return null;
        if (udpTried) return udpHost;
        udpTried = true;
        try {
            if (!Udp.available()) { udpProblem = "the game does not allow UDP sockets"; return null; }
            udpHost = new Udp.Host(port);
        } catch (Throwable t) {
            udpProblem = "UDP port " + port + " could not be opened (" + t + ")";
        }
        return udpHost;
    }

    /** How updates are reaching each player, for the log. */
    public String describeUpdates() {
        StringBuilder sb = new StringBuilder();
        for (Player p : players) {
            sb.append(sb.length() == 0 ? "" : "; ").append(p.name).append(": ").append(p.udp ? "UDP" : p.udpReady ? "TCP (UDP works but is resting)" : "TCP")
              .append(", ").append(p.updatesByUdp).append(" by UDP, ").append(p.updatesByTcp).append(" by TCP, ").append(p.keysSent)
              .append(" in full, fell back ").append(p.udpFallbacks).append(" times");
            if (Budget.bytesPerSecond > 0) {
                sb.append("; limit ").append(Budget.bytesPerSecond / 1024).append(" KB/s: ").append(p.budget.cut).append(" updates cut down, ")
                  .append(p.budget.leftOut).append(" states left out");
                p.budget.cut = 0; p.budget.leftOut = 0;
            }
            if (steadyControls) {
                InputBuffer b = p.buffer;
                sb.append("; steady controls: ").append(b.played).append(" played, ").append(b.delayMs).append(" ms added, ")
                  .append(b.recovered).append(" made good from copies, ").append(b.skipped).append(" skipped to catch up");
                b.played = 0; b.recovered = 0; b.skipped = 0;
            }
            p.updatesByUdp = 0; p.updatesByTcp = 0; p.keysSent = 0;
        }
        if (!udpProblem.isEmpty()) sb.append(" [").append(udpProblem).append("]");
        return sb.toString();
    }

    /** Handle handshakes, inputs and disconnects. Returns lines worth showing to the host. */
    public List<String> pump() {
        List<String> events = new ArrayList<String>();
        Conn c;
        while ((c = accepted.poll()) != null) handshaking.add(c); // limits were set when it arrived

        for (Iterator<Conn> it = handshaking.iterator(); it.hasNext();) {
            Conn h = it.next();
            if (h.isClosed()) { it.remove(); settled(h); continue; }
            Conn.Packet p = h.poll();
            if (p == null) {
                if (System.currentTimeMillis() - h.openedAt > SILENT_LIMIT_MS) { h.close("handshake timeout"); it.remove(); settled(h); }
                continue;
            }
            it.remove();
            settled(h);
            if (p.type != Msg.HELLO) { h.close("expected hello"); continue; }
            try {
                In in = Msg.reader(p.data);
                int version = in.readInt();
                String name = cleanName(in.readUTF());
                if (version != Msg.PROTOCOL_VERSION) {
                    reject(h, "Version mismatch: host has protocol " + Msg.PROTOCOL_VERSION + ", you have " + version
                            + ". Both players need the same mod version.");
                    continue;
                }
                ModList theirMods = ModList.read(in);
                String theirGame = in.readUTF(), theirPassword = in.readUTF();
                if (gameVersion != null && !gameVersion.equals(theirGame)) {
                    reject(h, "Different game version: the host runs " + gameVersion + ", you run " + theirGame + ".");
                    events.add(name + " was refused: different game version (" + theirGame + ").");
                    continue;
                }
                String fromPc = addressOf(h);
                long[] wrong = wrongPasswords.get(fromPc);
                if (!password.isEmpty() && wrong != null && wrong[0] >= PASSWORD_TRIES) {
                    long waited = System.currentTimeMillis() - wrong[1];
                    if (waited < PASSWORD_WAIT_MS) {
                        reject(h, "Too many wrong passwords. Wait " + ((PASSWORD_WAIT_MS - waited) / 1000 + 1) + " seconds and try again.");
                        continue;
                    }
                    wrong[0] = PASSWORD_TRIES - 1; // one more try after the wait, then wait again
                }
                if (!password.isEmpty() && !password.equals(theirPassword)) {
                    if (!theirPassword.isEmpty()) { // an empty box is a forgotten password, not a guess
                        if (wrong == null) { wrong = new long[2]; wrongPasswords.put(fromPc, wrong); }
                        wrong[0]++; wrong[1] = System.currentTimeMillis();
                    }
                    reject(h, theirPassword.isEmpty() ? "This game needs a password. Type it into the password box and connect again."
                            : "Wrong password.");
                    events.add(name + " was refused: " + (theirPassword.isEmpty() ? "no password given." : "wrong password."));
                    continue;
                }
                if (banned.contains(name.trim().toLowerCase())) {
                    reject(h, "The host removed you from this game.");
                    continue;
                }
                if (modCheck != MOD_CHECK_OFF) {
                    List<String> diff = ModList.differences(hostMods, theirMods, modCheck == MOD_CHECK_ALL);
                    if (!diff.isEmpty()) {
                        StringBuilder sb = new StringBuilder("Your enabled mods don't match the host's.");
                        int shown = Math.min(diff.size(), 10);
                        for (int i = 0; i < shown; i++) sb.append('\n').append(diff.get(i));
                        if (diff.size() > shown) sb.append("\n...and ").append(diff.size() - shown).append(" more.");
                        reject(h, sb.toString());
                        events.add(name + " was refused: " + diff.size() + " mod difference"
                                + (diff.size() == 1 ? "" : "s") + " (" + diff.get(0) + (diff.size() > 1 ? ", ..." : "") + ")");
                        continue;
                    }
                }
                // The same player coming back after their link dropped, before the old link has been given up
                // on here: the old one makes way. (Same name from the same PC, and the old link has gone quiet.)
                Player old = null;
                for (Player q : players) {
                    if (!q.name.equalsIgnoreCase(name.trim()) || !addressOf(q.conn).equals(addressOf(h))) continue;
                    if (q.conn.isClosed() || q.conn.idleMillis() > 1500) old = q;
                }
                if (old != null) {
                    old.replaced = true;
                    old.conn.close("replaced by a new connection");
                    players.remove(old);
                    departed.add(old);
                }
                if (players.size() + 1 >= maxPlayers) { // +1 is the host
                    reject(h, "Game is full (" + maxPlayers + " players).");
                    events.add(name + " tried to join but the game is full.");
                    continue;
                }
                wrongPasswords.remove(fromPc);
                Player pl = new Player(nextPlayerId++, h);
                if (old != null) pl.slot = old.slot;
                else { // the lowest number nobody has
                    pl.slot = 2;
                    for (boolean taken = true; taken; ) {
                        taken = false;
                        for (Player q : players) if (q.slot == pl.slot) { taken = true; pl.slot++; break; }
                    }
                }
                pl.name = name.trim().isEmpty() ? "Player " + pl.id : name.trim();
                players.add(pl);
                lobbyDirty = true;
                Msg.W w = new Msg.W();
                w.out.writeInt(pl.id);
                w.out.writeInt(maxPlayers);
                w.out.writeInt(snapshotRate);
                w.out.writeUTF(hostName);
                h.send(Msg.WELCOME, w.done());
                if (steadyControls) h.send(Msg.INPUT_MODE, new byte[] { 1 });
                h.offerCompression();
                try {
                    Udp.Host u = udp();
                    if (u != null) { // "you may also say hello by UDP, with this number"
                        u.add(pl);
                        Msg.W offer = new Msg.W();
                        offer.out.writeLong(pl.token);
                        offer.out.writeInt(port);
                        h.send(Msg.UDP_OFFER, offer.done());
                    }
                } catch (Throwable t) { udpProblem = String.valueOf(t); }
                events.add(pl.name + (old != null ? " reconnected (" : " joined (") + (players.size() + 1) + "/" + maxPlayers + ").");
                ModChannel.post(pl.name, pl.id, ModChannel.JOINED);
                broadcastNotice(pl.name + (old != null ? " reconnected." : " joined."), pl);
            } catch (IOException e) {
                h.close("bad hello");
            }
        }

        for (Iterator<Player> it = players.iterator(); it.hasNext();) {
            Player pl = it.next();
            Conn.Packet p;
            boolean bye = false;
            while ((p = pl.conn.poll()) != null) {
                try {
                    if (p.type == Msg.INPUT) pl.input.decodeIfNewer(p.data);
                    else if (p.type == Msg.UDP_READY) pl.udpReady = true;
                    else if (p.type == Msg.SYNC_REQ) pl.needsSync = true;
                    else if (p.type == Msg.NEED_KEY) pl.needKey = true;
                    else if (p.type == Msg.READY) {
                        In in = Msg.reader(p.data);
                        pl.ready = in.readBoolean();
                        pl.built = in.readInt();
                        pl.skipped = in.readInt();
                        pl.watching = in.remaining() > 0 && in.readBoolean();
                        lobbyDirty = true;
                    }
                    else if (p.type == Msg.SUBSYSTEM) {
                        In in = Msg.reader(p.data);
                        int ship = in.readInt();
                        String cls = in.readUTF();
                        long now = System.currentTimeMillis();
                        if (now - pl.subsystemWindow > 1000) { pl.subsystemWindow = now; pl.subsystemsInWindow = 0; }
                        // Guard rail: a class name, not a book; and no more presses than fingers can make.
                        if (cls.length() <= 200 && ++pl.subsystemsInWindow <= 10 && pl.subsystemPresses.size() < 20) {
                            pl.subsystemPresses.add(new Object[] { ship, cls });
                        }
                    }
                    else if (p.type == Msg.LUNA_KEYS) {
                        pl.lunaKeys = LunaKeys.decode(p.data);
                    }
                    else if (p.type == Msg.CLAIM) {
                        In in = Msg.reader(p.data);
                        pl.claimSeq = in.readInt();
                        pl.claim = in.readInt();
                        pl.hasClaim = true;
                    }
                    else if (p.type == Msg.INPUTS) pl.buffer.arrive(p.data, System.currentTimeMillis());
                    else if (p.type == Msg.PICK) {
                        String id = Msg.reader(p.data).readUTF();
                        if (id.length() <= 64) pl.pick = id; // (the last one counts; the game thread checks it)
                    }
                    else if (p.type == Msg.REFIT_ASK) {
                        String id = Msg.reader(p.data).readUTF();
                        if (id.length() <= 64) pl.refitAsk = id; // likewise
                    }
                    else if (p.type == Msg.MOD) {
                        long now = System.currentTimeMillis();
                        if (now - pl.modWindow > 1000) { pl.modWindow = now; pl.modsInWindow = 0; }
                        if (++pl.modsInWindow > ModChannel.PER_PLAYER_PER_SECOND) ModChannel.dropped.incrementAndGet();
                        else if (!ModChannel.received(pl.name, pl.id, p.data) && ++pl.badMessages > 20) bye = true;
                    }
                    else if (p.type == Msg.BYE) bye = true;
                } catch (IOException e) {
                    // Guard rail: one garbled message is skipped. Only a player who keeps sending them is dropped.
                    if (++pl.badMessages > 20) bye = true;
                }
            }
            byte[] byUdp = pl.udpInput;
            if (byUdp != null) {
                pl.udpInput = null;
                try { pl.input.decodeIfNewer(byUdp); } catch (IOException e) { /* a garbled datagram is nothing */ }
            }
            long nowMs = System.currentTimeMillis();
            for (byte[] b; (b = pl.udpBundles.poll()) != null; ) {
                try { pl.buffer.arrive(b, nowMs); } catch (IOException e) { /* a garbled datagram is nothing */ }
            }
            // (bundles still arriving just after steady controls were switched off are dropped: the plain ones rule)
            if (steadyControls) { try { pl.buffer.playOut(nowMs, pl.input); } catch (IOException e) { pl.buffer.clear(); } }
            else pl.buffer.clear();
            if (bye) pl.conn.close("left");
            boolean timedOut = !pl.conn.isClosed() && pl.conn.idleMillis() > LINK_TIMEOUT_MS;
            if (timedOut) pl.conn.close("timed out");
            if (pl.conn.isClosed()) {
                if (udpHost != null) udpHost.remove(pl);
                it.remove();
                departed.add(pl);
                lobbyDirty = true;
                String how = timedOut ? " lost connection." : " left.";
                events.add(pl.name + how);
                broadcastNotice(pl.name + how, null);
                if (!pl.replaced) ModChannel.post(pl.name, pl.id, ModChannel.LEFT);
            }
        }
        return events;
    }

    /** The PC a connection comes from, without the port. */
    private static String addressOf(Conn c) { return hostPart(String.valueOf(c.getRemote())); }

    private void reject(final Conn h, String reason) {
        h.send(Msg.REJECT, Msg.utf(reason));
        // give the writer thread a moment to flush before closing
        Thread t = new Thread(new Runnable() {
            public void run() {
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                h.close("rejected");
            }
        }, "Wingman-reject");
        t.setDaemon(true);
        t.start();
    }

    public void broadcast(byte type, byte[] data) {
        for (Player p : players) if (!p.needsSync) p.conn.send(type, data);
    }

    /** Returns false if any player's link was too backed up to take it. */
    public boolean broadcastDroppable(byte type, byte[] data) {
        boolean all = true;
        for (Player p : players) if (!p.needsSync && !p.conn.sendDroppable(type, data)) all = false;
        return all;
    }

    /** Everyone's next position update is to be one in full. */
    public void needKeyAll() { for (Player p : players) p.needKey = true; }

    /**
     * Send a position update to every player, each in the form that player can read: built on the last update
     * they are known to hold, or in full if there is none.
     *
     * By TCP everything arrives in order, so each update is built on the one before, and a player whose link
     * was too backed up to take one gets the next in full. By UDP an update may be lost, so each is built on
     * the newest one the player has said it has read; updates in full still go by TCP, which is sure to deliver
     * them. A player is on UDP while its acknowledgements keep coming, and back on TCP when they stop.
     *
     * Returns false if any player's TCP link was too backed up to take what was meant for it.
     */
    public boolean broadcastUpdate(Snapshot s) {
        boolean all = true;
        long now = System.currentTimeMillis();
        for (Player p : players) {
            if (p.needsSync) { p.needKey = true; continue; }
            if (p.encoder == null) {
                p.encoder = new SnapshotCodec.Encoder();
                p.encoderEpoch = p.encoder.epoch();
                p.ackTick = -1;
                p.baseTick = -1;
                p.needKey = true;
            }
            // Which road? UDP once the player has seen our answer come back that way, for as long as its
            // acknowledgements keep arriving. (A gap in our own sending is not the player's silence.)
            if (now - p.lastUpdateAt > 500) p.ackAt = now;
            p.lastUpdateAt = now;
            boolean wantUdp = Conn.udp && udpHost != null && p.udpReady && p.udpAddress != null;
            if (p.udp && (!wantUdp || now - p.ackAt > 3000)) {
                p.udp = false;
                p.udpOffAt = now;
                p.udpFallbacks++;
                p.needKey = true;
                p.conn.send(Msg.UDP_MODE, new byte[] { 0 });
            } else if (!p.udp && wantUdp && now - p.udpOffAt > 5000 && (p.udpOffAt == 0 || now - p.udpHeardAt < 500)) {
                p.udp = true; // (after a fall back: only once its datagrams are getting through again)
                p.ackAt = now;
                p.conn.send(Msg.UDP_MODE, new byte[] { 1 });
            }

            int base = -1;
            if (!p.needKey) {
                if (p.udp) base = p.encoder.has(p.ackTick) ? p.ackTick : -1;
                else if (++p.sinceKey < KEY_EVERY) base = p.baseTick;
            }
            byte[] data = p.budget.fit(s, p.encoder.encode(s, base), p.encoder, base);
            if (p.encoder.lastKey || !p.udp) {
                // In full, or on the TCP road: by TCP. It will arrive, so the next one can be built on it at once.
                if (p.conn.sendDroppable(Msg.SNAPSHOT_D, data)) {
                    p.baseTick = s.tick;
                    p.needKey = false;
                    if (p.encoder.lastKey) { p.keysSent++; p.sinceKey = 0; if (s.tick > p.ackTick) p.ackTick = s.tick; }
                    else p.updatesByTcp++;
                } else {
                    p.needKey = true;
                    all = false;
                }
            } else {
                udpHost.update(p, s.tick, data);
                p.updatesByUdp++;
            }
        }
        return all;
    }

    /** By TCP, how many updates may pass before a player is sent one in full regardless, as a safety net. */
    private static final int KEY_EVERY = 120;

    /** Values the packers left unsent as near enough, and values sent, over all players so far. */
    public long[] packerCounts() {
        long held = 0, sent = 0, carried = 0;
        for (Player p : players) if (p.encoder != null) { held += p.encoder.fieldsHeld; sent += p.encoder.fieldsSent; carried += p.encoder.carried; }
        return new long[] { held, sent, carried };
    }

    /** Sent to everyone connected, synced or not. */
    public void broadcastAll(byte type, byte[] data) {
        for (Player p : players) p.conn.send(type, data);
    }

    public void broadcastNotice(String text, Player except) {
        byte[] data = Msg.utf(text);
        for (Player p : players) if (p != except) p.conn.send(Msg.NOTICE, data);
    }

    public void shutdown() {
        if (udpHost != null) udpHost.close();
        server.close();
        for (Player p : players) p.conn.close("host closed");
        for (Conn h : handshaking) h.close("host closed");
        players.clear();
        handshaking.clear();
    }
}

package wingman.net;

import java.io.IOException;

/**
 * The joining player's connection to the host. Like HostSession it outlives a single battle,
 * so restarting the join mission reuses the same connection.
 */
public final class ClientLink {
    public enum State { IDLE, CONNECTING, CONNECTED, FAILED }

    private static final ClientLink INSTANCE = new ClientLink();
    public static ClientLink get() { return INSTANCE; }

    private volatile State state = State.IDLE;
    private volatile Conn conn;
    private volatile String error = "";

    private ClientLink() {}

    public State getState() {
        Conn c = conn;
        if (state == State.CONNECTED && c != null && !c.isClosed() && c.idleMillis() > HostSession.LINK_TIMEOUT_MS) {
            c.close("the host stopped responding");
        }
        if (state == State.CONNECTED && (c == null || c.isClosed())) {
            state = State.FAILED;
            error = c == null ? "connection lost" : c.getCloseReason();
        }
        return state;
    }

    public String getError() { return error; }

    /** The host's player name, once welcomed. */
    public volatile String hostName = "Host";
    public Conn getConn() { return conn; }

    /** Connect in the background and say hello. Safe to call from the game thread. */
    public synchronized void connect(final String address, final int port, final String playerName,
            final ModList mods) {
        connect(address, port, playerName, mods, "", "");
    }

    /** @param gameVersion this player's Starsector build; @param password the host's join password, or "". */
    public synchronized void connect(final String address, final int port, final String playerName,
            final ModList mods, final String gameVersion, final String password) {
        if (state == State.CONNECTING) return;
        Redact.alsoHide(address); // a host name typed in stays out of the log too
        disconnect();
        steady = false;
        sentSets.clear();
        sentTimes.clear();
        state = State.CONNECTING;
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    final Conn c = Conn.open(address, port);
                    // The host may offer UDP for updates and controls; that is settled here, out of the game's sight.
                    c.tap(new Conn.Tap() {
                        public boolean take(byte type, byte[] data) {
                            if (type == Msg.UDP_OFFER) {
                                startUdp(c, address, data);
                                return true;
                            }
                            if (type == Msg.MOD) { // another mod's message: waits for the game thread (WingmanAPI.deliver)
                                ModChannel.received(hostName, ModChannel.HOST_ID, data);
                                return true;
                            }
                            if (type == Msg.WELCOME) { // read here too, for other mods: who the host is (the game reads it as well)
                                try { In in = Msg.reader(data); in.readInt(); in.readInt(); in.readInt(); hostName = in.readUTF(); }
                                catch (IOException e) { /* the game's own reading reports it */ }
                                return false;
                            }
                            if (type == Msg.INPUT_MODE) {
                                steady = data.length > 0 && data[0] == 1;
                                return true;
                            }
                            if (type == Msg.UDP_MODE) {
                                Udp.Client u = udp;
                                if (u != null) u.sendControlsHere = data.length > 0 && data[0] == 1;
                                return true;
                            }
                            return false;
                        }
                    });
                    Msg.W w = new Msg.W();
                    w.out.writeInt(Msg.PROTOCOL_VERSION);
                    w.out.writeUTF(playerName == null ? "" : playerName);
                    (mods == null ? new ModList() : mods).write(w.out);
                    w.out.writeUTF(gameVersion == null ? "" : gameVersion);
                    w.out.writeUTF(password == null ? "" : password);
                    c.send(Msg.HELLO, w.done());
                    c.offerCompression();
                    conn = c;
                    state = State.CONNECTED;
                } catch (IOException e) {
                    error = "Could not reach the host (" + Redact.scrub(String.valueOf(e.getMessage())) + ")";
                    state = State.FAILED;
                } catch (Throwable e) { // anything else, such as the game's sandbox refusing a class
                    error = "Could not connect: " + Redact.scrub(String.valueOf(e));
                    state = State.FAILED;
                }
            }
        }, "Wingman-connect");
        t.setDaemon(true);
        t.start();
    }

    private volatile Udp.Client udp;
    /** The host wants our controls as bundles (steady controls, see InputBuffer). */
    public volatile boolean steady;
    private final java.util.List<byte[]> sentSets = new java.util.ArrayList<byte[]>();
    private final java.util.List<Integer> sentTimes = new java.util.ArrayList<Integer>();
    /** Why UDP is not in use, for the log; empty if it is or was never offered. */
    public volatile String udpProblem = "";

    private void startUdp(Conn c, String address, byte[] offer) {
        if (!Conn.udp) { udpProblem = "switched off in the settings"; return; }
        try {
            if (!Udp.available()) { udpProblem = "the game does not allow UDP sockets"; return; }
            In in = Msg.reader(offer);
            long token = in.readLong();
            int port = in.readInt();
            Udp.Client old = udp;
            if (old != null) old.close();
            udp = new Udp.Client(address, port, token, c);
            udpProblem = "";
        } catch (Throwable t) {
            udpProblem = String.valueOf(t);
        }
    }

    /**
     * Send our controls: by UDP if the host has said so (with word of the last update read riding along),
     * otherwise by the connection. Returns false only if the connection was too backed up to take them.
     */
    public boolean sendControls(byte[] controls, int ackEpoch, int ackTick) {
        Conn c = conn;
        if (c == null) return false;
        Udp.Client u = udp;
        if (steady) { // with our clock and the sets before it (see InputBuffer)
            if (sentSets.isEmpty() || !java.util.Arrays.equals(sentSets.get(0), controls)) {
                sentSets.add(0, controls);
                sentTimes.add(0, (int) (System.nanoTime() / 1000000L));
                while (sentSets.size() > InputBuffer.COPIES + 1) { sentSets.remove(sentSets.size() - 1); sentTimes.remove(sentTimes.size() - 1); }
            }
            byte[] b = InputBuffer.bundle(sentSets, sentTimes);
            if (u != null && u.up && u.sendControlsHere) { u.bundle(ackEpoch, ackTick, b); return true; }
            return c.sendDroppable(Msg.INPUTS, b);
        }
        if (u != null && u.up && u.sendControlsHere) { u.controls(ackEpoch, ackTick, controls); return true; }
        return c.sendDroppable(Msg.INPUT, controls);
    }

    /** True if our controls are going by UDP, where one may be lost and is not sent again. */
    public boolean controlsByUdp() { Udp.Client u = udp; return u != null && u.up && u.sendControlsHere; }

    /** Tell the host (by UDP, if that road is open) which update was last read. */
    public void ack(int ackEpoch, int ackTick) { Udp.Client u = udp; if (u != null && u.up) u.ack(ackEpoch, ackTick); }

    /** Next position update that came by UDP, or null. */
    public byte[] pollUpdate() { Udp.Client u = udp; return u == null ? null : u.poll(); }

    /** How the UDP road stands, for the log. */
    public String describeUdp() {
        Udp.Client u = udp;
        if (u == null) return udpProblem.isEmpty() ? "UDP not offered by the host" : "no UDP: " + udpProblem;
        return (u.up ? (u.sendControlsHere ? "UDP in use" : "UDP open, host is using TCP") : "UDP: no answer from the host yet")
                + ", " + u.updatesIn + " updates in " + u.piecesIn + " pieces by UDP so far, " + u.incomplete + " never completed";
    }

    public synchronized void disconnect() {
        Udp.Client u = udp;
        if (u != null) u.close();
        udp = null;
        Conn c = conn;
        if (c != null && !c.isClosed()) {
            c.send(Msg.BYE, Msg.EMPTY);
            c.close("left");
        }
        conn = null;
        state = State.IDLE;
    }
}

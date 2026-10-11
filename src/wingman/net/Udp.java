package wingman.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The second road between host and joiner, beside the TCP connection: UDP, for the two things that are sent
 * constantly and are worthless once late: position updates (host to joiner) and controls (joiner to host).
 *
 * TCP delivers everything, in order; when one packet is lost on the way, everything behind it waits until it
 * has been sent again, which on a real connection is a freeze and then a jump. UDP delivers each packet by
 * itself or not at all, so a lost update is simply skipped and the next one is on time. That only works for
 * messages that can stand being lost, and updates were made so (see SnapshotCodec); everything that must
 * arrive (ships appearing, hits, orders) stays on TCP.
 *
 * Nothing depends on UDP getting through. The joiner says hello by UDP with a number the host gave it over
 * TCP; if the host's answer comes back, the joiner reports that over TCP and the host starts using UDP for
 * that player. If the answers stop, the host goes back to TCP by itself.
 *
 * Joiner to host: token (8), kind (1), then HELLO: nothing; ACK: epoch (4), tick (4); INPUT: epoch, tick, controls.
 * Host to joiner: token (8), kind (1), then PONG: nothing; UPDATE: tick (4), piece (1), pieces (1), data.
 */
public final class Udp {
    private Udp() {}

    static final int HELLO = 1, ACK = 2, INPUT = 3, INPUTS = 4, PONG = 1, UPDATE = 2;
    /** The most data in one datagram. Kept well under what any ordinary link (or a VPN on top of one) passes whole. */
    static final int PIECE = 1100;
    private static final int HEAD = 9;

    private static void putLong(byte[] b, int at, long v) { for (int i = 0; i < 8; i++) b[at + i] = (byte) (v >>> (56 - 8 * i)); }
    private static long getLong(byte[] b, int at) { long v = 0; for (int i = 0; i < 8; i++) v = (v << 8) | (b[at + i] & 0xFF); return v; }
    private static void putInt(byte[] b, int at, int v) { b[at] = (byte) (v >>> 24); b[at + 1] = (byte) (v >>> 16); b[at + 2] = (byte) (v >>> 8); b[at + 3] = (byte) v; }
    private static int getInt(byte[] b, int at) { return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF); }

    /** The host's end: one socket for every player. */
    public static final class Host {
        private final DatagramSocket socket;
        private final ConcurrentHashMap<Long, HostSession.Player> byToken = new ConcurrentHashMap<Long, HostSession.Player>();
        private final LinkedBlockingQueue<DatagramPacket> out = new LinkedBlockingQueue<DatagramPacket>();
        private volatile boolean closed;
        public volatile long datagramsIn, datagramsOut, dropped;

        Host(int port) throws IOException {
            socket = new DatagramSocket(port);
            Thread r = new Thread(new Runnable() { public void run() { receive(); } }, "Wingman-udp-in");
            r.setDaemon(true);
            r.start();
            Thread w = new Thread(new Runnable() { public void run() { send(); } }, "Wingman-udp-out");
            w.setDaemon(true);
            w.start();
        }

        void add(HostSession.Player p) { byToken.put(p.token, p); }
        void remove(HostSession.Player p) { byToken.remove(p.token); }

        private void receive() {
            byte[] buf = new byte[2048];
            DatagramPacket d = new DatagramPacket(buf, buf.length);
            while (!closed) {
                try {
                    d.setLength(buf.length);
                    socket.receive(d);
                    int len = d.getLength();
                    if (len < HEAD) continue;
                    HostSession.Player p = byToken.get(getLong(buf, 0));
                    if (p == null) continue; // not one of ours: no answer, no work
                    datagramsIn++;
                    NetStats.wireIn(len);
                    p.udpAddress = d.getSocketAddress();
                    p.udpHeardAt = System.currentTimeMillis();
                    int kind = buf[8];
                    if (kind == HELLO) {
                        byte[] pong = new byte[HEAD];
                        putLong(pong, 0, p.token);
                        pong[8] = PONG;
                        queue(new DatagramPacket(pong, pong.length, d.getSocketAddress()));
                    } else if ((kind == ACK || kind == INPUT || kind == INPUTS) && len >= HEAD + 8) {
                        p.ack(getInt(buf, HEAD), getInt(buf, HEAD + 4));
                        if (kind == INPUT && len > HEAD + 8 && len < HEAD + 8 + 256) {
                            byte[] controls = new byte[len - HEAD - 8];
                            System.arraycopy(buf, HEAD + 8, controls, 0, controls.length);
                            p.udpInput = controls; // the game thread takes the latest
                            NetStats.received(Msg.INPUT, len);
                        }
                        if (kind == INPUTS && len > HEAD + 8 && len < HEAD + 8 + 1024 && p.udpBundles.size() < 64) {
                            byte[] bundle = new byte[len - HEAD - 8];
                            System.arraycopy(buf, HEAD + 8, bundle, 0, bundle.length);
                            p.udpBundles.add(bundle); // each one is played in turn (see InputBuffer)
                            NetStats.received(Msg.INPUT, len);
                        }
                    }
                } catch (IOException e) {
                    if (closed) return;
                } catch (RuntimeException e) {
                    // one bad datagram is nothing
                }
            }
        }

        private void queue(DatagramPacket d) {
            if (out.size() > 4000) { dropped++; return; } // the network is not taking them: these are the kind that may be lost
            out.add(d);
        }

        private void send() {
            while (!closed) {
                try {
                    DatagramPacket d = out.poll(500, TimeUnit.MILLISECONDS);
                    if (d == null) continue;
                    socket.send(d);
                    datagramsOut++;
                    NetStats.wireOut(d.getLength());
                } catch (IOException e) {
                    if (closed) return;
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    // carry on with the next
                }
            }
        }

        /** Send one update to one player, in as many pieces as it needs. Never blocks. */
        void update(HostSession.Player p, int tick, byte[] data) {
            Object where = p.udpAddress;
            if (!(where instanceof SocketAddress) || closed) return;
            SocketAddress to = (SocketAddress) where;
            int pieces = Math.max(1, (data.length + PIECE - 1) / PIECE);
            if (pieces > 255) return; // (a quarter of a megabyte: never)
            for (int i = 0; i < pieces; i++) {
                int from = i * PIECE, n = Math.min(PIECE, data.length - from);
                byte[] b = new byte[HEAD + 6 + n];
                putLong(b, 0, p.token);
                b[8] = UPDATE;
                putInt(b, HEAD, tick);
                b[HEAD + 4] = (byte) i;
                b[HEAD + 5] = (byte) pieces;
                System.arraycopy(data, from, b, HEAD + 6, n);
                queue(new DatagramPacket(b, b.length, to));
            }
            NetStats.sent(Msg.SNAPSHOT_D, data.length + pieces * (HEAD + 6));
        }

        void close() { closed = true; try { socket.close(); } catch (RuntimeException ignored) {} }
    }

    /** The joiner's end. */
    public static final class Client {
        private final DatagramSocket socket;
        private final SocketAddress host;
        private final long token;
        private final Conn conn;
        private volatile boolean closed;
        /** The host's answer has come back by UDP at least once. */
        public volatile boolean up;
        /** The host has said to send controls this way. */
        public volatile boolean sendControlsHere;
        private final ConcurrentLinkedQueue<byte[]> updates = new ConcurrentLinkedQueue<byte[]>();
        private volatile int waiting;
        private volatile long lastSentAt;
        public volatile long updatesIn, piecesIn, incomplete;

        Client(String address, int port, long token, Conn conn) throws IOException {
            this.host = new InetSocketAddress(address, port);
            this.token = token;
            this.conn = conn;
            socket = new DatagramSocket();
            socket.setSoTimeout(250);
            Thread t = new Thread(new Runnable() { public void run() { run0(); } }, "Wingman-udp");
            t.setDaemon(true);
            t.start();
        }

        /** Updates being put together from their pieces: tick -> the pieces so far. Only the newest few are kept. */
        private final Map<Integer, byte[][]> partial = new HashMap<Integer, byte[][]>();

        private void run0() {
            byte[] buf = new byte[2048];
            DatagramPacket d = new DatagramPacket(buf, buf.length);
            int hellos = 0;
            long lastHello = 0;
            while (!closed && !conn.isClosed()) {
                try {
                    long now = System.currentTimeMillis();
                    // Say hello until answered: four times a second for the first ten seconds, then every five
                    // seconds for as long as the connection lasts (a firewall may be opened meanwhile).
                    if (!up && now - lastHello >= (hellos < 40 ? 250 : 5000)) {
                        lastHello = now;
                        hellos++;
                        byte[] b = new byte[HEAD];
                        putLong(b, 0, token);
                        b[8] = HELLO;
                        socket.send(new DatagramPacket(b, b.length, host));
                        NetStats.wireOut(b.length);
                    }
                    d.setLength(buf.length);
                    try { socket.receive(d); } catch (SocketTimeoutException e) { continue; }
                    int len = d.getLength();
                    if (len < HEAD || getLong(buf, 0) != token) continue;
                    NetStats.wireIn(len);
                    int kind = buf[8];
                    if (kind == PONG) {
                        if (!up) { up = true; conn.send(Msg.UDP_READY, Msg.EMPTY); }
                    } else if (kind == UPDATE && len >= HEAD + 6) {
                        piecesIn++;
                        int tick = getInt(buf, HEAD), i = buf[HEAD + 4] & 0xFF, pieces = buf[HEAD + 5] & 0xFF;
                        if (pieces == 0 || i >= pieces) continue;
                        byte[] piece = new byte[len - HEAD - 6];
                        System.arraycopy(buf, HEAD + 6, piece, 0, piece.length);
                        if (pieces == 1) { deliver(piece); continue; }
                        byte[][] got = partial.get(tick);
                        if (got == null || got.length != pieces) {
                            if (partial.size() >= 8) { // forget the oldest: its missing piece is not coming
                                Iterator<Integer> it = partial.keySet().iterator();
                                int oldest = it.next();
                                while (it.hasNext()) { int t = it.next(); if (t < oldest) oldest = t; }
                                partial.remove(oldest);
                                incomplete++;
                            }
                            got = new byte[pieces][];
                            partial.put(tick, got);
                        }
                        got[i] = piece;
                        int total = 0;
                        boolean all = true;
                        for (byte[] g : got) { if (g == null) { all = false; break; } total += g.length; }
                        if (!all) continue;
                        partial.remove(tick);
                        byte[] whole = new byte[total];
                        int at = 0;
                        for (byte[] g : got) { System.arraycopy(g, 0, whole, at, g.length); at += g.length; }
                        deliver(whole);
                    }
                } catch (IOException e) {
                    if (closed) return;
                } catch (RuntimeException e) {
                    // one bad datagram is nothing
                }
            }
            close();
        }

        private void deliver(byte[] update) {
            if (waiting > 300) { updates.poll(); waiting--; } // nobody is reading (the game is loading): keep the newest
            updates.add(update);
            waiting++;
            updatesIn++;
            NetStats.received(Msg.SNAPSHOT_D, update.length);
        }

        /** Next whole update that came by UDP, or null. Game thread. */
        public byte[] poll() {
            byte[] u = updates.poll();
            if (u != null) waiting--;
            return u;
        }

        private void send(int kind, int epoch, int tick, byte[] more) {
            if (closed || !up) return;
            try {
                byte[] b = new byte[HEAD + 8 + (more == null ? 0 : more.length)];
                putLong(b, 0, token);
                b[8] = (byte) kind;
                putInt(b, HEAD, epoch);
                putInt(b, HEAD + 4, tick);
                if (more != null) System.arraycopy(more, 0, b, HEAD + 8, more.length);
                socket.send(new DatagramPacket(b, b.length, host));
                lastSentAt = System.currentTimeMillis();
                NetStats.wireOut(b.length);
                if (kind == INPUT || kind == INPUTS) NetStats.sent(Msg.INPUT, b.length);
            } catch (IOException e) {
                // lost like any other datagram
            } catch (RuntimeException e) {
                // likewise
            }
        }

        /** Our controls, with word of the last update read. */
        public void controls(int ackEpoch, int ackTick, byte[] controls) { send(INPUT, ackEpoch, ackTick, controls); }

        /** Our controls as a bundle (see InputBuffer). */
        public void bundle(int ackEpoch, int ackTick, byte[] bundle) { send(INPUTS, ackEpoch, ackTick, bundle); }

        /** Word of the last update read, if nothing else has carried it in the last 30 ms. */
        public void ack(int ackEpoch, int ackTick) {
            if (ackTick < 0 || System.currentTimeMillis() - lastSentAt < 30) return;
            send(ACK, ackEpoch, ackTick, null);
        }

        void close() { closed = true; try { socket.close(); } catch (RuntimeException ignored) {} }
    }

    private static volatile int state; // 0 not tried, 1 available, 2 refused by the game

    /** The game decides class by class what a mod may load; find out once whether it allows these. */
    static boolean available() {
        if (state == 0) {
            try {
                DatagramSocket s = new DatagramSocket();
                s.close();
                state = 1;
            } catch (Throwable t) {
                state = 2;
            }
        }
        return state == 1;
    }

    public static boolean refused() { return state == 2; }
}

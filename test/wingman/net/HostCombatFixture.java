package wingman.net;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Real Conn framing without sockets. Only exercised HostSession fields are initialized. */
public final class HostCombatFixture implements AutoCloseable {
    private final MemoryIO io = new MemoryIO();
    public final Conn conn = new Conn(io);
    public final HostSession session;
    public final HostSession.Player player = new HostSession.Player(1, conn);

    public HostCombatFixture() throws Exception {
        // The normal private constructor opens a listener. Keep that side effect out of unit tests.
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeClass.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Object unsafe = singleton.get(null);
        session = (HostSession) unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, HostSession.class);
        Field players = HostSession.class.getDeclaredField("players");
        players.setAccessible(true);
        players.set(session, new ArrayList<HostSession.Player>());
        session.getPlayers().add(player);
        player.name = "Test pilot";
        player.needsSync = false;
    }

    /** A FIFO marker proves all earlier writes completed; no sleeps or live sockets. */
    public List<Conn.Packet> drain() throws Exception {
        byte[] marker = { 11, 37, 91 };
        synchronized (io) {
            conn.send(Msg.PING, marker);
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!io.marked) {
                long left = end - System.nanoTime();
                if (left <= 0) throw new AssertionError("Timed out waiting for Conn writer");
                TimeUnit.NANOSECONDS.timedWait(io, left);
            }
            io.marked = false;
            List<Conn.Packet> result = new ArrayList<Conn.Packet>(io.packets);
            io.packets.clear();
            return result;
        }
    }

    /** Deterministic game-thread inbox injection; outgoing packets still use the real writer. */
    @SuppressWarnings("unchecked")
    public void enqueue(byte type, byte[] data) throws Exception {
        Field inbox = Conn.class.getDeclaredField("inbox");
        Field size = Conn.class.getDeclaredField("inboxSize");
        inbox.setAccessible(true);
        size.setAccessible(true);
        ((java.util.Queue<Conn.Packet>) inbox.get(conn)).add(Conn.packet(type, data));
        ((java.util.concurrent.atomic.AtomicInteger) size.get(conn)).incrementAndGet();
    }
    @Override public void close() { conn.close("test complete"); }

    private static final class MemoryIO implements RawIO {
        final List<Conn.Packet> packets = new ArrayList<Conn.Packet>();
        boolean closed, marked;

        @Override public synchronized int read(byte[] b, int off, int len) throws IOException {
            while (!closed) {
                try { wait(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            }
            return -1;
        }

        @Override public synchronized void write(byte[] b, int off, int len) throws IOException {
            int end = off + len;
            while (off < end) {
                if (end - off < 5) throw new IOException("Truncated test frame");
                int size = ((b[off] & 255) << 24) | ((b[off + 1] & 255) << 16)
                        | ((b[off + 2] & 255) << 8) | (b[off + 3] & 255);
                if (size < 1 || off + 4 + size > end) throw new IOException("Bad test frame size");
                byte type = b[off + 4];
                byte[] data = Arrays.copyOfRange(b, off + 5, off + 4 + size);
                if (type == Msg.PING && Arrays.equals(data, new byte[] { 11, 37, 91 })) marked = true;
                else if (type != Msg.PING) packets.add(Conn.packet(type, data));
                off += 4 + size;
            }
            notifyAll();
        }

        @Override public synchronized void close() { closed = true; notifyAll(); }
        @Override public String remote() { return "in-memory-test"; }
    }
}

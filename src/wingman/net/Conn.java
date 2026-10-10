package wingman.net;

import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One TCP connection. A reader thread fills the inbox and a writer thread drains the outbox,
 * so the game thread never blocks on the network: it only calls send() and poll().
 */
public final class Conn {
    public static final class Packet {
        public final byte type;
        public final byte[] data;
        /** When it was queued (ms). */
        final long at = System.currentTimeMillis();
        Packet(byte type, byte[] data) { this.type = type; this.data = data; }
    }

    /** A message that did not come over this connection (an update that arrived by UDP), in the same wrapping. */
    public static Packet packet(byte type, byte[] data) { return new Packet(type, data); }

    /** Sees chosen messages as they arrive, on the network thread, before the game does. Returns true to keep one from the game. */
    public interface Tap { boolean take(byte type, byte[] data); }
    private volatile Tap tap;
    public void tap(Tap t) { tap = t; }

    private static final int MAX_FRAME = 4 * 1024 * 1024;
    /** Reliable messages waiting to go out. A link this far behind is not coming back; cut it rather than fill memory. */
    private static final int MAX_OUTBOX = 20000;
    private volatile int maxFrame = MAX_FRAME;
    /** 0 = no limit. Otherwise more messages than this in one second closes the link (a joiner sends about 30). */
    private volatile int maxPerSecond;
    private volatile long lastHeardAt = System.currentTimeMillis();
    private static final int MAX_INBOX = 4000;
    /** A link counts as backed up when the oldest message waiting to go out has waited this long (ms). */
    private static final long BACKED_UP_MS = 150;
    private volatile long writeStartedAt;

    private final RawIO io;
    private final ConcurrentLinkedQueue<Packet> inbox = new ConcurrentLinkedQueue<Packet>();
    private final AtomicInteger inboxSize = new AtomicInteger();
    private final LinkedBlockingQueue<Packet> outbox = new LinkedBlockingQueue<Packet>();
    private volatile boolean closed;
    private volatile String closeReason = "";
    public final long openedAt = System.currentTimeMillis();

    // reader-thread buffer
    private final byte[] rbuf = new byte[65536];
    private int rpos, rlim;

    /** Connect to a host. Blocks; call from a background thread. */
    public static Conn open(String address, int port) throws IOException {
        return new Conn(Transport.connect(address, port));
    }

    Conn(RawIO rawIo) { this(rawIo, MAX_FRAME, 0); }

    /**
     * A link whose limits hold from its very first byte (for connections from players the host doesn't know
     * yet: limits set later would leave a moment in which a stranger could announce a 4 MB message).
     */
    Conn(RawIO rawIo, int maxFrameBytes, int maxMessagesPerSecond) {
        this.io = rawIo;
        this.maxFrame = maxFrameBytes;
        this.maxPerSecond = maxMessagesPerSecond;

        Thread reader = new Thread(new Runnable() {
            public void run() {
                try {
                    byte[] head = new byte[5];
                    while (!closed) {
                        readFully(head, 5);
                        int len = ((head[0] & 0xFF) << 24) | ((head[1] & 0xFF) << 16) | ((head[2] & 0xFF) << 8) | (head[3] & 0xFF);
                        if (len < 1 || len > maxFrame) throw new IOException("bad frame length " + len);
                        byte[] data = new byte[len - 1];
                        readFully(data, data.length);
                        long now = System.currentTimeMillis();
                        lastHeardAt = now;
                        heardFrames = true;
                        if (head[4] == Msg.PING) continue; // only proves the other side is alive
                        if (head[4] == Msg.ZIP_OK) { // the other side can read a compressed stream: send it one
                            if (compression && Zip.available() && !zipOffered) { zipOffered = true; outbox.add(new Packet(Msg.ZIP_ON, Msg.EMPTY)); }
                            continue;
                        }
                        if (head[4] == Msg.ZIP_ON) { // everything after this message is compressed
                            if (inflater != null) throw new IOException("compression switched on twice");
                            // Guard rail: only once this side has offered it. A stranger can't make the host
                            // unpack anything before it has said hello and been let in.
                            if (!zipInvited) throw new IOException("sent a compressed stream without being asked");
                            if (!Zip.available()) throw new IOException("sent a compressed stream without being asked");
                            Zip.Unpacker u = new Zip.Unpacker();
                            byte[] rest = new byte[rlim - rpos];
                            System.arraycopy(rbuf, rpos, rest, 0, rest.length);
                            u.input(rest, rest.length);
                            inflater = u;
                            rpos = rlim = 0;
                            continue;
                        }
                        if (maxPerSecond > 0) {
                            if (now - windowStart >= 1000) { windowStart = now; windowCount = 0; }
                            if (++windowCount > maxPerSecond) throw new IOException("too many messages");
                        }
                        if (inboxSize.get() >= MAX_INBOX) { // nobody is reading: drop the oldest
                            if (inbox.poll() != null) { inboxSize.decrementAndGet(); dropped.incrementAndGet(); }
                        }
                        NetStats.received(head[4], 5 + data.length);
                        Tap t = tap;
                        if (t != null) {
                            boolean taken = false;
                            try { taken = t.take(head[4], data); } catch (Throwable ignored) { /* the game gets it instead */ }
                            if (taken) continue;
                        }
                        inbox.add(new Packet(head[4], data));
                        inboxSize.incrementAndGet();
                    }
                } catch (IOException e) {
                    close("connection lost");
                } catch (RuntimeException e) {
                    close("connection error: " + e);
                }
            }
        }, "Wingman-reader");
        reader.setDaemon(true);
        reader.start();

        Thread writer = new Thread(new Runnable() {
            public void run() {
                try {
                    while (!closed) {
                        Packet p = outbox.poll(250, TimeUnit.MILLISECONDS);
                        if (p == null) {
                            // Nothing to say for a second: say so, so the other side can tell a quiet link from
                            // a dead one. Done here, on the network thread, because the game thread stops
                            // running our code while the game is loading.
                            if (System.currentTimeMillis() - lastWroteAt < 1000) continue;
                            p = new Packet(Msg.PING, Msg.EMPTY);
                        }
                        lastWroteAt = System.currentTimeMillis();
                        writeStartedAt = lastWroteAt;
                        // Everything that is waiting goes out in one write. A burst of small messages (a salvo
                        // of missile launches, say) then travels as a few full packets instead of one tiny
                        // packet each, which is less overhead and less for a slow link to choke on.
                        int used = 0;
                        while (p != null) {
                            int size = 5 + p.data.length;
                            if (size > wbuf.length) { // too big to share a write: send what is gathered, then this alone
                                if (used > 0) { emit(wbuf, used); used = 0; }
                                byte[] big = new byte[size];
                                frame(big, 0, p);
                                emit(big, size);
                            } else {
                                if (used + size > wbuf.length) { emit(wbuf, used); used = 0; }
                                frame(wbuf, used, p);
                                used += size;
                            }
                            NetStats.sent(p.type, size);
                            if (p.type == Msg.ZIP_ON) { // this message itself goes plain; everything after it is compressed
                                emit(wbuf, used);
                                used = 0;
                                deflater = new Zip.Packer();
                            }
                            p = outbox.poll();
                        }
                        if (used > 0) emit(wbuf, used);
                        writeStartedAt = 0;
                    }
                } catch (IOException e) {
                    close("connection lost");
                } catch (InterruptedException e) {
                    close("interrupted");
                } catch (RuntimeException e) {
                    close("connection error: " + e);
                }
            }
        }, "Wingman-writer");
        writer.setDaemon(true);
        writer.start();
    }

    private void readFully(byte[] dst, int len) throws IOException {
        int got = 0;
        while (got < len) {
            if (rpos == rlim) {
                int n;
                if (inflater == null) {
                    n = io.read(rbuf, 0, rbuf.length);
                    if (n < 0) throw new IOException("closed");
                    NetStats.wireIn(n);
                } else {
                    n = 0;
                    while (n == 0) {
                        if (inflater.needsInput()) {
                            int c = io.read(zin, 0, zin.length);
                            if (c < 0) throw new IOException("closed");
                            NetStats.wireIn(c);
                            inflater.input(zin, c);
                        }
                        n = inflater.output(rbuf);
                    }
                }
                rpos = 0;
                rlim = n;
                continue;
            }
            int take = Math.min(len - got, rlim - rpos);
            System.arraycopy(rbuf, rpos, dst, got, take);
            rpos += take;
            got += take;
        }
    }

    private long windowStart, windowCount, lastWroteAt;
    private final byte[] wbuf = new byte[32 * 1024]; // writer-thread buffer
    private final AtomicInteger dropped = new AtomicInteger();

    // Compression of the whole stream, each direction by itself. A side that can read a compressed stream says
    // so (ZIP_OK); the other then sends ZIP_ON and compresses everything after it. Because it is one continuous
    // stream, each message is compressed knowing all that went before, which is what makes it worthwhile for
    // messages as small and as alike as these.
    /** Off: this side neither offers nor uses compression (for comparing, or if it ever causes trouble). */
    public static volatile boolean compression = true;
    /** Off: UDP is neither offered nor used for updates and controls; everything travels by this connection. */
    public static volatile boolean udp = true;
    private volatile boolean zipOffered;
    /** This side has said it can read a compressed stream (ZIP_OK sent); before that, ZIP_ON is refused. */
    private volatile boolean zipInvited;
    private volatile Zip.Packer deflater;   // used by the writer thread only
    private volatile Zip.Unpacker inflater; // used by the reader thread only
    private final byte[] zout = new byte[16 * 1024], zin = new byte[16 * 1024];

    /** Tell the other side it may send us a compressed stream. Call once the hello has gone out. */
    public void offerCompression() {
        if (closed || !compression || !Zip.available()) return;
        zipInvited = true;
        outbox.add(new Packet(Msg.ZIP_OK, Msg.EMPTY));
    }

    /** What this link is doing about compression, for the log. */
    public String compressionState() {
        return Zip.refused() ? "not available (refused by the game)" : !compression ? "switched off"
                : (deflater != null ? "sending compressed" : "sending plain") + ", " + (inflater != null ? "receiving compressed" : "receiving plain");
    }

    private void emit(byte[] b, int len) throws IOException {
        if (len <= 0) return;
        if (deflater == null) { io.write(b, 0, len); NetStats.wireOut(len); return; }
        deflater.input(b, len);
        int n;
        do {
            n = deflater.output(zout);
            if (n > 0) { io.write(zout, 0, n); NetStats.wireOut(n); }
        } while (n == zout.length);
    }

    private static void frame(byte[] dst, int at, Packet p) {
        int len = p.data.length + 1;
        dst[at] = (byte) (len >>> 24);
        dst[at + 1] = (byte) (len >>> 16);
        dst[at + 2] = (byte) (len >>> 8);
        dst[at + 3] = (byte) len;
        dst[at + 4] = p.type;
        System.arraycopy(p.data, 0, dst, at + 5, p.data.length);
    }

    /**
     * How many received messages were thrown away since the last call because nobody was reading (a long
     * freeze on the game's side). Some of them may have been ones that mattered; the caller should ask for a
     * fresh picture.
     */
    public int takeDropped() { return dropped.getAndSet(0); }

    /** How long since anything at all (keep-alives included) arrived from the other side. */
    public long idleMillis() { return System.currentTimeMillis() - lastHeardAt; }

    /** True once at least one whole message (keep-alives included) has arrived. */
    public boolean heardAnything() { return heardFrames; }
    private volatile boolean heardFrames;

    /** For connections from players the host doesn't know yet: small messages only, and not many of them. */
    public void restrict(int maxFrameBytes, int maxMessagesPerSecond) {
        maxFrame = maxFrameBytes;
        maxPerSecond = maxMessagesPerSecond;
    }

    /** Queue a message that must arrive. */
    public void send(byte type, byte[] data) {
        if (closed) return;
        if (outbox.size() > MAX_OUTBOX) { close("too far behind"); return; }
        outbox.add(new Packet(type, data));
    }

    /** Queue a message that may be skipped if the link is backed up (snapshots, inputs). */
    public boolean sendDroppable(byte type, byte[] data) {
        if (closed) return false;
        // Backed up means messages are not getting out, not that many were queued at once: a salvo of forty
        // missile launches in one frame is forty messages waiting for a few milliseconds, and that is fine.
        long now = System.currentTimeMillis();
        Packet head = outbox.peek();
        long stuck = writeStartedAt;
        if ((head != null && now - head.at > BACKED_UP_MS) || (stuck != 0 && now - stuck > BACKED_UP_MS) || outbox.size() > 5000) {
            NetStats.skipped();
            return false;
        }
        outbox.add(new Packet(type, data));
        return true;
    }

    /** Next received message, or null. Call from the game thread. */
    public Packet poll() {
        Packet p = inbox.poll();
        if (p != null) inboxSize.decrementAndGet();
        return p;
    }

    public boolean isClosed() { return closed; }
    public String getCloseReason() { return closeReason; }
    public String getRemote() { return io.remote(); }

    public void close(String why) {
        if (closed) return;
        closeReason = why;
        closed = true;
        io.close();
    }
}

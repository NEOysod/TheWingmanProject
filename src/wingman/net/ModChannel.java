package wingman.net;

import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Messages other mods send each other over Wingman's link (see wingman.api.WingmanAPI). On the wire: Msg.MOD,
 * UTF channel, int length, the bytes. Wingman does not read them; it only carries them and caps their size and
 * number, so a mod (or a hostile joiner) can't flood the link or the host's memory.
 *
 * Arrivals wait here until the game thread hands them to listeners (WingmanAPI.deliver). On a joiner they are
 * taken off the connection by its network thread; on the host, by HostSession.pump.
 */
public final class ModChannel {
    private ModChannel() {}

    /** Longest channel name, and largest payload, in one message. */
    public static final int MAX_CHANNEL = 64, MAX_BYTES = 60 * 1024;
    /** Messages waiting for the game thread; beyond this, new ones are dropped (and counted). */
    public static final int MAX_WAITING = 2000;
    /** Most messages one joiner may send the host in a second (the link itself allows 300 of everything). */
    public static final int PER_PLAYER_PER_SECOND = 100;
    /** The host's id in "from": joiners hear everything from the host. */
    public static final int HOST_ID = 0;
    /** Channels Wingman itself posts on, on the host, with an empty message: a joiner came in / left. */
    public static final String JOINED = "wingman:joined", LEFT = "wingman:left";

    public static final class Message {
        /** Sender's player name, and their id on the host's session (HOST_ID for the host). */
        public final String from;
        public final int playerId;
        public final String channel;
        public final byte[] data;
        Message(String from, int playerId, String channel, byte[] data) {
            this.from = from; this.playerId = playerId; this.channel = channel; this.data = data;
        }
    }

    private static final ConcurrentLinkedQueue<Message> inbox = new ConcurrentLinkedQueue<Message>();
    private static final AtomicInteger waiting = new AtomicInteger();
    /** Dropped for being over the caps, or malformed; for the log. */
    public static final AtomicInteger dropped = new AtomicInteger(), malformed = new AtomicInteger();

    public static boolean validChannel(String channel) {
        return channel != null && !channel.isEmpty() && channel.length() <= MAX_CHANNEL;
    }

    /** The payload of a Msg.MOD. Throws IllegalArgumentException if the channel or size is out of bounds. */
    public static byte[] encode(String channel, byte[] data) {
        if (!validChannel(channel)) throw new IllegalArgumentException("channel must be 1 to " + MAX_CHANNEL + " characters");
        if (data == null) data = Msg.EMPTY;
        if (data.length > MAX_BYTES) throw new IllegalArgumentException("message over " + MAX_BYTES + " bytes");
        Msg.W w = new Msg.W();
        try {
            w.out.writeUTF(channel);
            w.out.writeInt(data.length);
            w.out.write(data);
        } catch (IOException e) { throw new IllegalStateException(e); }
        return w.done();
    }

    /** A Msg.MOD arrived (any thread). Returns false if it was malformed; one over the caps is dropped quietly. */
    public static boolean received(String from, int playerId, byte[] payload) {
        String channel;
        byte[] data;
        try {
            In in = Msg.reader(payload);
            channel = in.readUTF();
            int n = in.readInt();
            if (!validChannel(channel) || n < 0 || n > MAX_BYTES) { malformed.incrementAndGet(); return false; }
            data = new byte[n];
            in.readFully(data);
        } catch (IOException e) {
            malformed.incrementAndGet();
            return false;
        }
        post(new Message(from, playerId, channel, data));
        return true;
    }

    /** Wingman's own notices to listeners (a player joined or left, on the host). */
    public static void post(Message m) {
        if (waiting.incrementAndGet() > MAX_WAITING) {
            waiting.decrementAndGet();
            dropped.incrementAndGet();
            return;
        }
        inbox.add(m);
    }

    public static void post(String from, int playerId, String channel) { post(new Message(from, playerId, channel, Msg.EMPTY)); }

    /** Next waiting message, or null. */
    public static Message poll() {
        Message m = inbox.poll();
        if (m != null) waiting.decrementAndGet();
        return m;
    }

    public static int waiting() { return waiting.get(); }
}

package wingman.net;

import java.io.IOException;

/**
 * Picks the socket technology. Starsector's sandbox decides class by class what a mod may load, and the
 * only way to find out is to try. So before the first connection we ask for every class each option needs:
 * classic sockets if they are all allowed, channels if not.
 */
public final class Transport {
    private Transport() {}

    public static final int AUTO = 0, STREAMS = 1, CHANNELS = 2;
    private static volatile int mode = AUTO;
    private static volatile String blocked = "";

    /** For tests: force one technology. */
    public static void force(int m) { mode = m; }

    private static final String[] NAMES = {
        "java.net.Socket", "java.net.ServerSocket", "java.net.InetSocketAddress", "java.io.InputStream",
        "java.io.OutputStream",
        "java.nio.ByteBuffer", "java.nio.channels.SocketChannel", "java.nio.channels.ServerSocketChannel",
        "java.net.StandardSocketOptions",
        "java.util.concurrent.LinkedBlockingQueue", "java.util.concurrent.ConcurrentLinkedQueue",
        "java.util.concurrent.TimeUnit", "java.util.concurrent.atomic.AtomicInteger",
    };
    private static final int STREAM_FROM = 0, STREAM_TO = 5, CHANNEL_FROM = 5, CHANNEL_TO = 9;

    /** Makes the game's class loader load class number i, which is where the sandbox says yes or no. */
    private static Class<?> touch(int i) {
        switch (i) {
            case 0: return java.net.Socket.class;
            case 1: return java.net.ServerSocket.class;
            case 2: return java.net.InetSocketAddress.class;
            case 3: return java.io.InputStream.class;
            case 4: return java.io.OutputStream.class;
            case 5: return java.nio.ByteBuffer.class;
            case 6: return java.nio.channels.SocketChannel.class;
            case 7: return java.nio.channels.ServerSocketChannel.class;
            case 8: return java.net.StandardSocketOptions.class;
            case 9: return java.util.concurrent.LinkedBlockingQueue.class;
            case 10: return java.util.concurrent.ConcurrentLinkedQueue.class;
            case 11: return java.util.concurrent.TimeUnit.class;
            default: return java.util.concurrent.atomic.AtomicInteger.class;
        }
    }

    private static boolean allowed(int from, int to) {
        boolean ok = true;
        for (int i = from; i < to; i++) {
            try {
                touch(i);
            } catch (Throwable t) {
                ok = false;
                if (!blocked.contains(NAMES[i])) blocked += (blocked.isEmpty() ? "" : ", ") + NAMES[i];
            }
        }
        return ok;
    }

    private static synchronized void decide() {
        if (mode != AUTO) return;
        allowed(CHANNEL_TO, NAMES.length); // only to get them into the report
        if (allowed(STREAM_FROM, STREAM_TO)) mode = STREAMS;
        else if (allowed(CHANNEL_FROM, CHANNEL_TO)) mode = CHANNELS;
        else throw new IllegalStateException("the game refuses every networking class this mod can use: " + blocked);
    }

    /** Which technology is in use and what the game refused; logged once so a log file tells the whole story. */
    public static String describe() {
        return "using " + (mode == CHANNELS ? "channels" : mode == STREAMS ? "streams" : "nothing yet")
                + (blocked.isEmpty() ? ", no classes refused" : ", refused by the game: " + blocked);
    }

    static RawIO.Listener listen(int port) throws IOException {
        decide();
        return mode == CHANNELS ? ChannelIO.listen(port) : StreamIO.listen(port);
    }

    static RawIO connect(String address, int port) throws IOException {
        decide();
        return mode == CHANNELS ? ChannelIO.connect(address, port) : StreamIO.connect(address, port);
    }
}

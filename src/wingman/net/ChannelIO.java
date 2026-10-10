package wingman.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

/** The same thing over java.nio channels, which touch no java.io stream classes. Used if StreamIO is blocked. */
final class ChannelIO implements RawIO {
    private final SocketChannel ch;
    private final String remote; // noted while the link is open: a closed channel no longer says who it was

    ChannelIO(SocketChannel ch) throws IOException {
        this.ch = ch;
        ch.configureBlocking(true);
        ch.setOption(StandardSocketOptions.TCP_NODELAY, Boolean.TRUE);
        String r;
        try { r = String.valueOf(ch.getRemoteAddress()); } catch (IOException e) { r = "?"; }
        remote = r;
    }

    static RawIO connect(String address, int port) throws IOException {
        SocketChannel c = SocketChannel.open();
        c.connect(new InetSocketAddress(address, port));
        return new ChannelIO(c);
    }

    public int read(byte[] b, int off, int len) throws IOException { return ch.read(ByteBuffer.wrap(b, off, len)); }
    public void write(byte[] b, int off, int len) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(b, off, len);
        while (bb.hasRemaining()) ch.write(bb);
    }
    public void close() { try { ch.close(); } catch (IOException ignored) {} }
    public String remote() { return remote; }

    static RawIO.Listener listen(int port) throws IOException {
        final ServerSocketChannel server = ServerSocketChannel.open();
        server.bind(new InetSocketAddress(port));
        return new RawIO.Listener() {
            public RawIO accept() throws IOException { return new ChannelIO(server.accept()); }
            public void close() { try { server.close(); } catch (IOException ignored) {} }
            public boolean isClosed() { return !server.isOpen(); }
        };
    }
}

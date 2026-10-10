package wingman.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/** Connections over classic java.net sockets and their streams. First choice. */
final class StreamIO implements RawIO {
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    StreamIO(Socket s) throws IOException {
        socket = s;
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
        in = s.getInputStream();
        out = s.getOutputStream();
    }

    static RawIO connect(String address, int port) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(address, port), 5000);
        return new StreamIO(s);
    }

    public int read(byte[] b, int off, int len) throws IOException { return in.read(b, off, len); }
    public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); out.flush(); }
    public void close() { try { socket.close(); } catch (IOException ignored) {} }
    public String remote() { return String.valueOf(socket.getRemoteSocketAddress()); }

    static RawIO.Listener listen(int port) throws IOException {
        final ServerSocket server = new ServerSocket(port);
        return new RawIO.Listener() {
            public RawIO accept() throws IOException { return new StreamIO(server.accept()); }
            public void close() { try { server.close(); } catch (IOException ignored) {} }
            public boolean isClosed() { return server.isClosed(); }
        };
    }
}

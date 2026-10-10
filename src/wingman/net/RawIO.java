package wingman.net;

import java.io.IOException;

/** The bare minimum the mod needs from a connection, so the socket technology underneath can be swapped. */
interface RawIO {
    /** Blocks until at least one byte is read. Returns -1 when the other side has closed. */
    int read(byte[] b, int off, int len) throws IOException;
    void write(byte[] b, int off, int len) throws IOException;
    void close();
    String remote();

    interface Listener {
        RawIO accept() throws IOException;
        void close();
        boolean isClosed();
    }
}

package wingman.net;

import java.io.IOException;

/**
 * The only class that touches java.util.zip. The game decides class by class what a mod may load; keeping the
 * compression classes behind this one means that if the game ever refuses them, only this class fails to load
 * (which available() catches) and connections carry on uncompressed.
 */
final class Zip {
    private Zip() {}

    private static volatile int state; // 0 not tried, 1 available, 2 refused

    static boolean available() {
        if (state == 0) {
            try {
                new Packer().end();
                new Unpacker().end();
                state = 1;
            } catch (Throwable t) {
                state = 2;
            }
        }
        return state == 1;
    }

    static boolean refused() { return state == 2; }

    /** Compresses one side's outgoing stream. Every call's output can be read by the other side at once. */
    static final class Packer {
        private final java.util.zip.Deflater d = new java.util.zip.Deflater(java.util.zip.Deflater.BEST_SPEED, true);

        void input(byte[] b, int len) { d.setInput(b, 0, len); }

        /** Next piece of compressed output; call until it returns less than the buffer holds. */
        int output(byte[] out) { return d.deflate(out, 0, out.length, java.util.zip.Deflater.SYNC_FLUSH); }

        void end() { d.end(); }
    }

    static final class Unpacker {
        private final java.util.zip.Inflater i = new java.util.zip.Inflater(true);

        boolean needsInput() { return i.needsInput(); }

        /** The array must be left alone until needsInput() is true again. */
        void input(byte[] b, int len) { i.setInput(b, 0, len); }

        /** Unpacked bytes put into out; 0 if more input is needed first. */
        int output(byte[] out) throws IOException {
            try {
                int n = i.inflate(out, 0, out.length);
                if (n == 0 && (i.finished() || i.needsDictionary())) throw new IOException("bad compressed data");
                return n;
            } catch (java.util.zip.DataFormatException e) {
                throw new IOException("bad compressed data");
            }
        }

        void end() { i.end(); }
    }
}

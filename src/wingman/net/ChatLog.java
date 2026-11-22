package wingman.net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The co-op text chat (Msg.CHAT): the lines said since this game started, on this PC. A joiner sends what they type
 * to the host, the host hands every line (its own too) to everyone. Only text: nothing in it is ever acted on.
 */
public final class ChatLog {
    private ChatLog() {}

    public static final int MAX_LINE = 200, KEPT = 100;

    public static final class Line {
        public final int slot;
        public final String name, text;
        public final long at;
        Line(int slot, String name, String text, long at) { this.slot = slot; this.name = name; this.text = text; this.at = at; }
    }

    private static final List<Line> lines = new ArrayList<Line>();
    /** Lines added since the chat was last looked at (shown open). */
    private static volatile int unread;
    /** Bumped on every change, so a screen can tell its copy is stale. */
    private static volatile int version;

    /** What can be said: one line, printable, at most MAX_LINE characters; "" for nothing. */
    public static String clean(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < MAX_LINE; i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') c = ' ';
            if (c >= ' ' && c != 127 && !Character.isISOControl(c)) b.append(c);
        }
        return b.toString().trim();
    }

    public static synchronized void add(int slot, String name, String text) {
        text = clean(text);
        if (text.isEmpty()) return;
        lines.add(new Line(slot, name == null ? "?" : name, text, System.currentTimeMillis()));
        while (lines.size() > KEPT) lines.remove(0);
        unread++;
        version++;
    }

    public static synchronized List<Line> last(int n) {
        return new ArrayList<Line>(lines.subList(Math.max(0, lines.size() - n), lines.size()));
    }

    public static synchronized int size() { return lines.size(); }

    public static int unread() { return unread; }
    public static void seen() { unread = 0; }
    public static int version() { return version; }

    public static synchronized void clear() { lines.clear(); unread = 0; version++; }

    /** Host -> joiners: who said it, and what. */
    public static byte[] encode(int slot, String name, String text) {
        Msg.W w = new Msg.W();
        try { w.out.writeByte(slot); w.out.writeUTF(name); w.out.writeUTF(clean(text)); } catch (IOException e) { throw new IllegalStateException(e); }
        return w.done();
    }

    /** A line from the host (joiner side). */
    public static void onMessage(byte[] data) {
        try {
            In in = Msg.reader(data);
            int slot = (in.readByte() & 0xFF);
            String name = in.readUTF(), text = in.readUTF();
            if (name.length() <= 64) add(slot, name, text);
        } catch (IOException e) { /* garbled: skipped */ }
    }
}

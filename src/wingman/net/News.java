package wingman.net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The host's campaign clock and what the campaign has told the host lately (Msg.NEWS, host -> joiners, only while
 * the host is on the campaign map): the date as the host's game prints it, and the notifications (new intel,
 * intel updates, messages) that appeared since the last one sent. Joiners keep the latest few for the lobby.
 */
public final class News {
    public String date = "";
    public List<String> items = new ArrayList<String>();
    public static final int MAX_ITEMS = 20;

    private static String cap(String s) { return s == null ? "" : s.length() > 160 ? s.substring(0, 160) : s; }

    public byte[] encode() {
        Msg.W w = new Msg.W();
        try {
            w.out.writeUTF(cap(date));
            int n = Math.min(items.size(), MAX_ITEMS);
            w.out.writeByte(n);
            for (int i = 0; i < n; i++) w.out.writeUTF(cap(items.get(i)));
        } catch (IOException ex) { throw new IllegalStateException(ex); }
        return w.done();
    }

    public static News decode(byte[] data) throws IOException {
        In in = Msg.reader(data);
        News n = new News();
        n.date = Roster.cleanLong(in.readUTF());
        int count = in.readByte() & 0xFF;
        if (count > MAX_ITEMS) throw new IOException("too many news items");
        for (int i = 0; i < count; i++) n.items.add(Roster.cleanLong(in.readUTF()));
        return n;
    }
}

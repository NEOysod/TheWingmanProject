package wingman.net;

import java.io.IOException;

/**
 * Friend refit (Msg.REFIT_RESULT, host -> joiner): what became of a refit request. ACCEPTED: done in the host's
 * campaign; REFUSED: the host couldn't do it (the text says why).
 */
public final class RefitResult {
    public static final int ACCEPTED = 0, REFUSED = 1;
    public static final int MAX_TEXT = 1000;

    public int status;
    /** Why, or what to know: lines separated by '\n'. */
    public String text = "";

    public RefitResult() {}
    public RefitResult(int status, String text) { this.status = status; this.text = text == null ? "" : text; }

    public byte[] encode() {
        Msg.W w = new Msg.W();
        try {
            w.out.writeByte(status);
            w.out.writeUTF(text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text);
        } catch (IOException ex) { throw new IllegalStateException(ex); }
        return w.done();
    }

    public static RefitResult decode(byte[] data) throws IOException {
        In in = Msg.reader(data);
        RefitResult r = new RefitResult();
        r.status = in.readByte();
        if (r.status < ACCEPTED || r.status > REFUSED) throw new IOException("unknown refit result");
        String t = in.readUTF();
        if (t.length() > MAX_TEXT) throw new IOException("text too long");
        // one line each, printable characters only
        StringBuilder sb = new StringBuilder();
        for (String line : t.split("\n", 20)) sb.append(sb.length() == 0 ? "" : "\n").append(Roster.cleanLong(line));
        r.text = sb.toString();
        return r;
    }
}

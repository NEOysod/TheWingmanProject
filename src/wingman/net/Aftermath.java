package wingman.net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * What the last battle changed in the host's fleet (Msg.AFTERMATH, host -> joiners): sent once the host is back
 * on the campaign map with the battle's dialog closed, since salvage, recovered ships and losses are only
 * settled there.
 */
public final class Aftermath {
    public float credits;
    public List<String> shipsLost = new ArrayList<String>(), shipsGained = new ArrayList<String>();
    /** Cargo that changed: name and amount (+ gained, - spent or lost), biggest changes first. */
    public List<String> cargoNames = new ArrayList<String>();
    public List<Float> cargoChanges = new ArrayList<Float>();
    public static final int MAX_SHIPS = 20, MAX_CARGO = 12;

    private static String cap(String s) { return s == null ? "" : s.length() > 80 ? s.substring(0, 80) : s; }

    public boolean isEmpty() { return credits == 0f && shipsLost.isEmpty() && shipsGained.isEmpty() && cargoNames.isEmpty(); }

    public byte[] encode() {
        Msg.W w = new Msg.W();
        try {
            w.out.writeFloat(credits);
            for (List<String> l : new List[] { shipsLost, shipsGained }) {
                w.out.writeByte(Math.min(l.size(), MAX_SHIPS));
                for (int i = 0; i < l.size() && i < MAX_SHIPS; i++) w.out.writeUTF(cap(l.get(i)));
            }
            int n = Math.min(Math.min(cargoNames.size(), cargoChanges.size()), MAX_CARGO);
            w.out.writeByte(n);
            for (int i = 0; i < n; i++) { w.out.writeUTF(cap(cargoNames.get(i))); w.out.writeFloat(cargoChanges.get(i)); }
        } catch (IOException ex) { throw new IllegalStateException(ex); }
        return w.done();
    }

    public static Aftermath decode(byte[] data) throws IOException {
        In in = Msg.reader(data);
        Aftermath a = new Aftermath();
        a.credits = in.readFloat();
        if (a.credits != a.credits || Float.isInfinite(a.credits)) throw new IOException("impossible credits");
        for (List<String> l : new List[] { a.shipsLost, a.shipsGained }) {
            int n = in.readByte() & 0xFF;
            if (n > MAX_SHIPS) throw new IOException("too many ships");
            for (int i = 0; i < n; i++) l.add(Roster.clean(in.readUTF()));
        }
        int n = in.readByte() & 0xFF;
        if (n > MAX_CARGO) throw new IOException("too many cargo lines");
        for (int i = 0; i < n; i++) {
            a.cargoNames.add(Roster.clean(in.readUTF()));
            float v = in.readFloat();
            if (v != v || Float.isInfinite(v)) throw new IOException("impossible cargo change");
            a.cargoChanges.add(v);
        }
        return a;
    }
}

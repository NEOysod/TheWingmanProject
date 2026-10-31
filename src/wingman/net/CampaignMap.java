package wingman.net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * What the host's campaign map shows right now (Msg.MAP, host -> joiners, only while the host is on the campaign
 * map), for the joiner lobby's CAMPAIGN window. A read-only picture: in a star system every body, in hyperspace only
 * what lies near the host; fleets only as far as the host's sensors see them. Coordinates are the host location's
 * own; the view (centre, radius) is picked by the host so the joiner just scales it into its panel.
 */
public final class CampaignMap {
    public static final int FORMAT = 1;
    public static final int MAX_BODIES = 256, MAX_FLEETS = 128, MAX_NAME = 40;
    /** The smallest view a host may send: anything less would blow the joiner's map scale up to infinity. */
    public static final float MIN_VIEW = 100f;

    /** Body kinds. */
    public static final byte STAR = 0, PLANET = 1, STATION = 2, JUMP_POINT = 3, SYSTEM = 4;
    /** How much the host's sensors tell about a fleet. */
    public static final byte BLIP = 0, SHAPE = 1, KNOWN = 2;

    public static final class Body {
        public byte kind;
        public float x, y, radius;
        public String name = "";
        public int color; // 0x00RRGGBB faction colour, 0 = the default for the kind
    }

    public static final class Fleet {
        public byte detail;
        public boolean hostile;
        public float x, y, vx, vy;
        public int ships;
        public String name = "";
        public int color;
    }

    public String location = "";
    public boolean hyperspace, paused;
    public float viewX, viewY, viewRadius = 1f;
    public float playerX, playerY, playerVx, playerVy;
    public final List<Body> bodies = new ArrayList<Body>();
    public final List<Fleet> fleets = new ArrayList<Fleet>();

    private static String cap(String s) { return s == null ? "" : s.length() > MAX_NAME ? s.substring(0, MAX_NAME) : s; }

    public byte[] encode() {
        Msg.W w = new Msg.W();
        try {
            w.out.writeByte(FORMAT);
            w.out.writeUTF(cap(location));
            w.out.writeBoolean(hyperspace);
            w.out.writeBoolean(paused);
            w.out.writeFloat(viewX); w.out.writeFloat(viewY); w.out.writeFloat(viewRadius);
            w.out.writeFloat(playerX); w.out.writeFloat(playerY); w.out.writeFloat(playerVx); w.out.writeFloat(playerVy);
            int nb = Math.min(bodies.size(), MAX_BODIES);
            w.out.writeShort(nb);
            for (int i = 0; i < nb; i++) {
                Body b = bodies.get(i);
                w.out.writeByte(b.kind);
                w.out.writeFloat(b.x); w.out.writeFloat(b.y); w.out.writeFloat(b.radius);
                w.out.writeUTF(cap(b.name));
                w.out.writeInt(b.color);
            }
            int nf = Math.min(fleets.size(), MAX_FLEETS);
            w.out.writeShort(nf);
            for (int i = 0; i < nf; i++) {
                Fleet f = fleets.get(i);
                w.out.writeByte(f.detail);
                w.out.writeBoolean(f.hostile);
                w.out.writeFloat(f.x); w.out.writeFloat(f.y); w.out.writeFloat(f.vx); w.out.writeFloat(f.vy);
                w.out.writeShort(Math.max(0, Math.min(f.ships, Short.MAX_VALUE)));
                w.out.writeUTF(cap(f.name));
                w.out.writeInt(f.color);
            }
        } catch (IOException ex) { throw new IllegalStateException(ex); }
        return w.done();
    }

    private static float coord(In in) throws IOException {
        float v = in.readFloat();
        if (Float.isNaN(v) || Float.isInfinite(v) || Math.abs(v) > 1e7f) throw new IOException("bad map number");
        return v;
    }

    private static String name(In in) throws IOException {
        String s = Roster.clean(in.readUTF());
        return s.length() > MAX_NAME ? s.substring(0, MAX_NAME) : s;
    }

    public static CampaignMap decode(byte[] data) throws IOException {
        In in = Msg.reader(data);
        if (in.readByte() != FORMAT) throw new IOException("unknown map format");
        CampaignMap m = new CampaignMap();
        m.location = name(in);
        m.hyperspace = in.readBoolean();
        m.paused = in.readBoolean();
        m.viewX = coord(in); m.viewY = coord(in); m.viewRadius = coord(in);
        if (m.viewRadius < MIN_VIEW) throw new IOException("bad map view");
        m.playerX = coord(in); m.playerY = coord(in); m.playerVx = coord(in); m.playerVy = coord(in);
        int nb = in.readShort() & 0xFFFF;
        if (nb > MAX_BODIES) throw new IOException("too many map bodies");
        for (int i = 0; i < nb; i++) {
            Body b = new Body();
            b.kind = in.readByte();
            if (b.kind < STAR || b.kind > SYSTEM) throw new IOException("bad body kind");
            b.x = coord(in); b.y = coord(in); b.radius = Math.abs(coord(in));
            b.name = name(in);
            b.color = in.readInt() & 0xFFFFFF;
            m.bodies.add(b);
        }
        int nf = in.readShort() & 0xFFFF;
        if (nf > MAX_FLEETS) throw new IOException("too many map fleets");
        for (int i = 0; i < nf; i++) {
            Fleet f = new Fleet();
            f.detail = in.readByte();
            if (f.detail < BLIP || f.detail > KNOWN) throw new IOException("bad fleet detail");
            f.hostile = in.readBoolean();
            f.x = coord(in); f.y = coord(in); f.vx = coord(in); f.vy = coord(in);
            f.ships = in.readShort() & 0x7FFF;
            f.name = name(in);
            f.color = in.readInt() & 0xFFFFFF;
            m.fleets.add(f);
        }
        return m;
    }
}

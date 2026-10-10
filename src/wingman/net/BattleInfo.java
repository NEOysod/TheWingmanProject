package wingman.net;

import java.io.IOException;

/** The host's battlefield: size and everything that makes up the backdrop. */
public final class BattleInfo {
    public float width = 40000f, height = 40000f;
    public String background = "";
    /** Colours as ARGB; the "has" flags say whether the host has one set. */
    public boolean hasBackgroundColor, hasGlowColor, glowNonAdditive;
    public int backgroundColor, glowColor;
    public boolean renderStarfield = true;
    public boolean hyperspace;
    /** The planet drawn behind the battle, if the host is fighting near one. */
    public boolean hasPlanet;
    public String planetType = "";
    public float planetRadius, planetYOffset, planetBgWidth, planetBgHeight;
    /**
     * The host's objectives at the start of the battle, kind and place. Objectives can only be made while a
     * mission is being set up, so the joiner's mission makes these same ones then, of whatever kind a mod
     * brings.
     */
    public java.util.List<String> objectiveTypes = new java.util.ArrayList<String>();
    public java.util.List<Float> objectiveX = new java.util.ArrayList<Float>(), objectiveY = new java.util.ArrayList<Float>();
    public static final int MAX_OBJECTIVES = 64;

    public byte[] encode() {
        Msg.W w = new Msg.W();
        try {
            w.out.writeFloat(width);
            w.out.writeFloat(height);
            w.out.writeUTF(background);
            w.out.writeBoolean(hasBackgroundColor);
            w.out.writeInt(backgroundColor);
            w.out.writeBoolean(hasGlowColor);
            w.out.writeInt(glowColor);
            w.out.writeBoolean(glowNonAdditive);
            w.out.writeBoolean(renderStarfield);
            w.out.writeBoolean(hyperspace);
            w.out.writeBoolean(hasPlanet);
            w.out.writeUTF(planetType);
            w.out.writeFloat(planetRadius);
            w.out.writeFloat(planetYOffset);
            w.out.writeFloat(planetBgWidth);
            w.out.writeFloat(planetBgHeight);
            int n = Math.min(objectiveTypes.size(), MAX_OBJECTIVES);
            w.out.writeByte(n);
            for (int i = 0; i < n; i++) {
                w.out.writeUTF(objectiveTypes.get(i));
                w.out.writeFloat(objectiveX.get(i));
                w.out.writeFloat(objectiveY.get(i));
            }
        } catch (IOException e) { throw new IllegalStateException(e); }
        return w.done();
    }

    public static BattleInfo decode(byte[] data) throws IOException {
        In in = Msg.reader(data);
        BattleInfo b = new BattleInfo();
        b.width = in.readFloat();
        b.height = in.readFloat();
        b.background = in.readUTF();
        b.hasBackgroundColor = in.readBoolean();
        b.backgroundColor = in.readInt();
        b.hasGlowColor = in.readBoolean();
        b.glowColor = in.readInt();
        b.glowNonAdditive = in.readBoolean();
        b.renderStarfield = in.readBoolean();
        b.hyperspace = in.readBoolean();
        b.hasPlanet = in.readBoolean();
        b.planetType = in.readUTF();
        b.planetRadius = in.readFloat();
        b.planetYOffset = in.readFloat();
        b.planetBgWidth = in.readFloat();
        b.planetBgHeight = in.readFloat();
        int n = in.readByte() & 0xFF;
        if (n > MAX_OBJECTIVES) throw new IOException("too many objectives: " + n);
        for (int i = 0; i < n; i++) {
            String type = in.readUTF();
            float x = in.readFloat(), y = in.readFloat();
            if (type.isEmpty() || type.length() > 80 || Snapshot.bad(x) || Snapshot.bad(y)) throw new IOException("objective with impossible values");
            b.objectiveTypes.add(type);
            b.objectiveX.add(x);
            b.objectiveY.add(y);
        }
        // Guard rail: a map with an impossible size is refused, not built.
        if (Snapshot.bad(b.width) || Snapshot.bad(b.height) || b.width < 1f || b.height < 1f || Snapshot.bad(b.planetRadius)
                || Snapshot.bad(b.planetYOffset) || Snapshot.bad(b.planetBgWidth) || Snapshot.bad(b.planetBgHeight))
            throw new IOException("battlefield with impossible numbers");
        return b;
    }
}

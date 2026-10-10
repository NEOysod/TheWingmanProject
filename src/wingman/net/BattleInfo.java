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

    // ---- The host's campaign, as far as mod scripts in a battle may look at it ----
    // A joiner's game has no campaign loaded. Some mods' battle scripts read the player fleet, its cargo, the
    // player's skills or the campaign's flags anyway, and crash when they find nothing. The joiner sets up a
    // stand-in from these before its battle is built (see CampaignStandIn).
    public boolean hasCampaign;
    public float credits;
    public int storyPoints, playerLevel;
    public String playerFirst = "", playerLast = "", playerPortrait = "";
    public java.util.List<String> skillIds = new java.util.ArrayList<String>(), cargoIds = new java.util.ArrayList<String>();
    public java.util.List<Float> skillLevels = new java.util.ArrayList<Float>(), cargoAmounts = new java.util.ArrayList<Float>();
    /** Simple campaign flags: key, kind (1 true/false, 2 number, 3 text) and value. */
    public java.util.List<String> flagKeys = new java.util.ArrayList<String>(), flagTexts = new java.util.ArrayList<String>();
    public java.util.List<Byte> flagKinds = new java.util.ArrayList<Byte>();
    public java.util.List<Double> flagNumbers = new java.util.ArrayList<Double>();
    public static final int MAX_SKILLS = 120, MAX_CARGO = 300, MAX_FLAGS = 600;

    /** Add one campaign flag; false if it isn't a simple value (or there are enough already). */
    public boolean addFlag(String key, Object v) {
        if (key == null || key.length() > 80 || flagKeys.size() >= MAX_FLAGS) return false;
        byte kind; double num = 0; String text = "";
        if (v instanceof Boolean) { kind = 1; num = ((Boolean) v) ? 1 : 0; }
        else if (v instanceof Number) { kind = 2; num = ((Number) v).doubleValue(); if (num != num || Double.isInfinite(num)) return false; }
        else if (v instanceof String && ((String) v).length() <= 64) { kind = 3; text = (String) v; }
        else return false;
        flagKeys.add(key); flagKinds.add(kind); flagNumbers.add(num); flagTexts.add(text);
        return true;
    }

    /** One flag's value as the host had it: Boolean, Double or String. */
    public Object flagValue(int i) {
        byte k = flagKinds.get(i);
        return k == 1 ? (Object) Boolean.valueOf(flagNumbers.get(i) != 0) : k == 2 ? (Object) flagNumbers.get(i) : flagTexts.get(i);
    }

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
            w.out.writeBoolean(hasCampaign);
            if (hasCampaign) {
                w.out.writeFloat(credits);
                w.out.writeInt(storyPoints);
                w.out.writeInt(playerLevel);
                w.out.writeUTF(playerFirst);
                w.out.writeUTF(playerLast);
                w.out.writeUTF(playerPortrait);
                int s = Math.min(skillIds.size(), MAX_SKILLS);
                w.out.writeShort(s);
                for (int i = 0; i < s; i++) { w.out.writeUTF(skillIds.get(i)); w.out.writeFloat(skillLevels.get(i)); }
                int c = Math.min(cargoIds.size(), MAX_CARGO);
                w.out.writeShort(c);
                for (int i = 0; i < c; i++) { w.out.writeUTF(cargoIds.get(i)); w.out.writeFloat(cargoAmounts.get(i)); }
                int f = Math.min(flagKeys.size(), MAX_FLAGS);
                w.out.writeShort(f);
                for (int i = 0; i < f; i++) {
                    w.out.writeUTF(flagKeys.get(i));
                    w.out.writeByte(flagKinds.get(i));
                    if (flagKinds.get(i) == 3) w.out.writeUTF(flagTexts.get(i)); else w.out.writeDouble(flagNumbers.get(i));
                }
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
        b.hasCampaign = in.readBoolean();
        if (b.hasCampaign) {
            b.credits = in.readFloat();
            b.storyPoints = in.readInt();
            b.playerLevel = in.readInt();
            b.playerFirst = in.readUTF();
            b.playerLast = in.readUTF();
            b.playerPortrait = in.readUTF();
            if (b.credits != b.credits || Float.isInfinite(b.credits) || b.playerLevel < 0 || b.playerLevel > 1000) throw new IOException("impossible campaign values");
            int s = in.readShort() & 0xFFFF;
            if (s > MAX_SKILLS) throw new IOException("too many skills: " + s);
            for (int i = 0; i < s; i++) {
                String id = in.readUTF(); float lv = in.readFloat();
                if (id.isEmpty() || Snapshot.bad(lv)) throw new IOException("impossible skill");
                b.skillIds.add(id); b.skillLevels.add(lv);
            }
            int c = in.readShort() & 0xFFFF;
            if (c > MAX_CARGO) throw new IOException("too much cargo: " + c);
            for (int i = 0; i < c; i++) {
                String id = in.readUTF(); float amount = in.readFloat();
                if (id.isEmpty() || amount != amount || Float.isInfinite(amount) || amount < 0) throw new IOException("impossible cargo");
                b.cargoIds.add(id); b.cargoAmounts.add(amount);
            }
            int f = in.readShort() & 0xFFFF;
            if (f > MAX_FLAGS) throw new IOException("too many flags: " + f);
            for (int i = 0; i < f; i++) {
                String key = in.readUTF();
                byte kind = in.readByte();
                Object v;
                if (kind == 1) v = in.readDouble() != 0;
                else if (kind == 2) v = in.readDouble();
                else if (kind == 3) v = in.readUTF();
                else throw new IOException("unknown flag kind " + kind);
                if (!b.addFlag(key, v)) throw new IOException("impossible flag");
            }
        }
        // Guard rail: a map with an impossible size is refused, not built.
        if (Snapshot.bad(b.width) || Snapshot.bad(b.height) || b.width < 1f || b.height < 1f || Snapshot.bad(b.planetRadius)
                || Snapshot.bad(b.planetYOffset) || Snapshot.bad(b.planetBgWidth) || Snapshot.bad(b.planetBgHeight))
            throw new IOException("battlefield with impossible numbers");
        return b;
    }
}

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
     * The planets the host's battle actually has, read from its engine (the battle's own creation plugin, a mod's
     * included, decided them): when known, the joiner makes exactly these instead of guessing one from the map.
     */
    public boolean planetsKnown;
    public java.util.List<Planet> planets = new java.util.ArrayList<Planet>();
    public static final int MAX_PLANETS = 32, MAX_SPEC_VALUES = 64, MAX_TEXT = 200;

    /** One planet of the host's battle: where and how big, its turn, and how its look differs from its type's. */
    public static final class Planet {
        public String type = "";
        public float x, y, radius, gravity, angle, cloudAngle, tilt, pitch, scale = 1f;
        public boolean background = true;
        public boolean hasLight;
        public int light;
        /** Look differences (the game's own planet spec diff): field name and value. */
        public java.util.List<String> specKeys = new java.util.ArrayList<String>();
        public java.util.List<SpecValue> specValues = new java.util.ArrayList<SpecValue>();
    }

    /** One value of a planet spec field. Kinds: 0 none, 1 text, 2 number, 3 yes/no, 4 colour (ARGB), 5 three numbers. */
    public static final class SpecValue {
        public byte kind;
        public String text = "";
        public float a, b, c;
        public boolean flag;
        public int argb;
    }
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

    private static String cut(String s) { return s == null ? "" : s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s; }

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
            w.out.writeBoolean(planetsKnown);
            if (planetsKnown) {
                int np = Math.min(planets.size(), MAX_PLANETS);
                w.out.writeByte(np);
                for (int i = 0; i < np; i++) {
                    Planet p = planets.get(i);
                    w.out.writeUTF(cut(p.type));
                    w.out.writeFloat(p.x); w.out.writeFloat(p.y); w.out.writeFloat(p.radius); w.out.writeFloat(p.gravity);
                    w.out.writeFloat(p.angle); w.out.writeFloat(p.cloudAngle); w.out.writeFloat(p.tilt); w.out.writeFloat(p.pitch);
                    w.out.writeFloat(p.scale);
                    w.out.writeBoolean(p.background);
                    w.out.writeBoolean(p.hasLight);
                    w.out.writeInt(p.light);
                    int nv = Math.min(Math.min(p.specKeys.size(), p.specValues.size()), MAX_SPEC_VALUES);
                    w.out.writeByte(nv);
                    for (int k = 0; k < nv; k++) {
                        SpecValue v = p.specValues.get(k);
                        w.out.writeUTF(cut(p.specKeys.get(k)));
                        w.out.writeByte(v.kind);
                        switch (v.kind) {
                            case 1: w.out.writeUTF(cut(v.text)); break;
                            case 2: w.out.writeFloat(v.a); break;
                            case 3: w.out.writeBoolean(v.flag); break;
                            case 4: w.out.writeInt(v.argb); break;
                            case 5: w.out.writeFloat(v.a); w.out.writeFloat(v.b); w.out.writeFloat(v.c); break;
                            default: break;
                        }
                    }
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
        b.planetsKnown = in.readBoolean();
        if (b.planetsKnown) {
            int np = in.readByte() & 0xFF;
            if (np > MAX_PLANETS) throw new IOException("too many planets: " + np);
            for (int i = 0; i < np; i++) {
                Planet p = new Planet();
                p.type = in.readUTF();
                p.x = in.readFloat(); p.y = in.readFloat(); p.radius = in.readFloat(); p.gravity = in.readFloat();
                p.angle = in.readFloat(); p.cloudAngle = in.readFloat(); p.tilt = in.readFloat(); p.pitch = in.readFloat();
                p.scale = in.readFloat();
                p.background = in.readBoolean();
                p.hasLight = in.readBoolean();
                p.light = in.readInt();
                if (p.type.isEmpty() || Snapshot.bad(p.x) || Snapshot.bad(p.y) || Snapshot.bad(p.radius) || Snapshot.bad(p.gravity)
                        || Snapshot.bad(p.angle) || Snapshot.bad(p.cloudAngle) || Snapshot.bad(p.tilt) || Snapshot.bad(p.pitch)
                        || Snapshot.bad(p.scale) || p.radius < 0f) throw new IOException("planet with impossible values");
                int nv = in.readByte() & 0xFF;
                if (nv > MAX_SPEC_VALUES) throw new IOException("too many planet looks: " + nv);
                for (int k = 0; k < nv; k++) {
                    String key = in.readUTF();
                    SpecValue v = new SpecValue();
                    v.kind = in.readByte();
                    switch (v.kind) {
                        case 0: break;
                        case 1: v.text = in.readUTF(); break;
                        case 2: v.a = in.readFloat(); break;
                        case 3: v.flag = in.readBoolean(); break;
                        case 4: v.argb = in.readInt(); break;
                        case 5: v.a = in.readFloat(); v.b = in.readFloat(); v.c = in.readFloat(); break;
                        default: throw new IOException("unknown planet look kind " + v.kind);
                    }
                    if (key.isEmpty() || Snapshot.bad(v.a) || Snapshot.bad(v.b) || Snapshot.bad(v.c)) throw new IOException("impossible planet look");
                    p.specKeys.add(key);
                    p.specValues.add(v);
                }
                b.planets.add(p);
            }
        }
        // Guard rail: a map with an impossible size is refused, not built.
        if (Snapshot.bad(b.width) || Snapshot.bad(b.height) || b.width < 1f || b.height < 1f || Snapshot.bad(b.planetRadius)
                || Snapshot.bad(b.planetYOffset) || Snapshot.bad(b.planetBgWidth) || Snapshot.bad(b.planetBgHeight))
            throw new IOException("battlefield with impossible numbers");
        return b;
    }
}

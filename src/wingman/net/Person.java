package wingman.net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Who a person in the host's battle is, beyond a name and skills: their id (scripts test "is this captain
 * Sirius?"), faction, rank, post, personality, tags and memory flags (plain data only, see Plain). Used for a
 * ship's officer (ShipSpawn) and a fleet's commander (FleetInfo).
 */
public final class Person {
    public String id = "", first = "", last = "", portrait = "", faction = "", rank = "", post = "", personality = "", aiCore = "";
    public int level;
    public List<String> skillIds = new ArrayList<String>();
    public List<Float> skillLevels = new ArrayList<Float>();
    public List<String> tags = new ArrayList<String>();
    public Map<String, Object> memory = new LinkedHashMap<String, Object>();

    public static final int MAX_SKILLS = 120, MAX_TAGS = 100, MAX_MEMORY_BYTES = 16 * 1024;

    private static String cap(String s, int n) { return s == null ? "" : s.length() > n ? s.substring(0, n) : s; }

    void write(Out out) throws IOException {
        for (String s : new String[] { id, first, last, portrait, faction, rank, post, personality, aiCore }) out.writeUTF(cap(s, 200));
        out.writeInt(level);
        int n = Math.min(Math.min(skillIds.size(), skillLevels.size()), MAX_SKILLS);
        out.writeShort(n);
        for (int i = 0; i < n; i++) { out.writeUTF(cap(skillIds.get(i), 100)); out.writeFloat(skillLevels.get(i)); }
        int t = Math.min(tags.size(), MAX_TAGS);
        out.writeShort(t);
        for (int i = 0; i < t; i++) out.writeUTF(cap(tags.get(i), 100));
        Plain.writeMap(out, memory);
    }

    static Person read(In in) throws IOException {
        Person p = new Person();
        p.id = in.readUTF(); p.first = in.readUTF(); p.last = in.readUTF(); p.portrait = in.readUTF(); p.faction = in.readUTF();
        p.rank = in.readUTF(); p.post = in.readUTF(); p.personality = in.readUTF(); p.aiCore = in.readUTF();
        p.level = in.readInt();
        if (p.level < 0 || p.level > 1000) throw new IOException("impossible level");
        int n = in.readShort() & 0xFFFF;
        if (n > MAX_SKILLS) throw new IOException("too many skills");
        for (int i = 0; i < n; i++) {
            String s = in.readUTF();
            float l = in.readFloat();
            if (Snapshot.bad(l)) throw new IOException("impossible skill level");
            p.skillIds.add(s);
            p.skillLevels.add(l);
        }
        int t = in.readShort() & 0xFFFF;
        if (t > MAX_TAGS) throw new IOException("too many tags");
        for (int i = 0; i < t; i++) p.tags.add(in.readUTF());
        p.memory = Plain.readMap(in);
        return p;
    }
}

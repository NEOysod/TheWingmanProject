package wingman.net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A larger copy of the host's campaign for joiners' stand-in campaign (Msg.CAMPAIGN, host -> joiner, once per
 * battle, before the joiner's battle is set up). Everything in it is plain data (see Plain): mods' saved data
 * (the sector's persistent data), the memory of the sector, the player, the player's fleet and faction, the
 * enemy fleet, its commander and faction and the place of the battle, the player's standing with each faction,
 * who the enemy is, and where the battle is. Values that are objects of a mod's own are left out (counted).
 */
public final class CampaignCopy {
    /** Memory scopes, in the order they are sent. */
    public static final String[] SCOPES = { "sector", "player", "playerFleet", "playerFaction", "enemyFleet", "enemyCommander",
            "enemyFaction", "location" };
    public static final int MAX_BYTES = 1500 * 1024;

    public Map<String, Object> persistent = new LinkedHashMap<String, Object>();
    public Map<String, Map<String, Object>> memories = new LinkedHashMap<String, Map<String, Object>>();
    /** Faction id -> standing with the player (-1..1). */
    public Map<String, Float> relations = new LinkedHashMap<String, Float>();

    // the enemy side
    public String enemyFaction = "", enemyFleetName = "", enemyCommanderFirst = "", enemyCommanderLast = "", enemyCommanderPortrait = "",
            enemyCommanderRank = "", enemyCommanderPost = "";
    public int enemyCommanderLevel;
    public String playerFactionName = "";
    /** FleetGoal names ("ATTACK", "ESCAPE"), or "" if unknown. */
    public String playerGoal = "", enemyGoal = "";

    // the place
    public String locationName = "", locationId = "";
    public boolean hyperspace;
    public List<String> locationTags = new ArrayList<String>();
    /** The campaign date, as the clock's timestamp (0 if unknown). */
    public long timestamp;
    public String dateText = "";

    /** How many values were left out because they aren't plain data (for the log), and the first of their keys. */
    public int skipped;
    public List<String> skippedKeys = new ArrayList<String>();
    public static final int MAX_SKIPPED_KEYS = 300;

    public byte[] encode() {
        Msg.W w = new Msg.W();
        try {
            Plain.writeMap(w.out, persistent);
            w.out.writeByte(memories.size());
            for (Map.Entry<String, Map<String, Object>> e : memories.entrySet()) {
                w.out.writeUTF(e.getKey());
                Plain.writeMap(w.out, e.getValue());
            }
            w.out.writeShort(Math.min(relations.size(), 2000));
            int i = 0;
            for (Map.Entry<String, Float> e : relations.entrySet()) {
                if (i++ >= 2000) break;
                w.out.writeUTF(cap(e.getKey()));
                w.out.writeFloat(e.getValue());
            }
            for (String s : new String[] { enemyFaction, enemyFleetName, enemyCommanderFirst, enemyCommanderLast, enemyCommanderPortrait,
                    enemyCommanderRank, enemyCommanderPost, playerFactionName, playerGoal, enemyGoal, locationName, locationId, dateText }) {
                w.out.writeUTF(cap(s));
            }
            w.out.writeInt(enemyCommanderLevel);
            w.out.writeBoolean(hyperspace);
            w.out.writeShort(Math.min(locationTags.size(), 200));
            for (int k = 0; k < locationTags.size() && k < 200; k++) w.out.writeUTF(cap(locationTags.get(k)));
            w.out.writeLong(timestamp);
            w.out.writeInt(skipped);
            w.out.writeShort(Math.min(skippedKeys.size(), MAX_SKIPPED_KEYS));
            for (int k = 0; k < skippedKeys.size() && k < MAX_SKIPPED_KEYS; k++) w.out.writeUTF(cap(skippedKeys.get(k)));
        } catch (IOException ex) { throw new IllegalStateException(ex); }
        return w.done();
    }

    private static String cap(String s) { return s == null ? "" : s.length() > 200 ? s.substring(0, 200) : s; }

    public static CampaignCopy decode(byte[] data) throws IOException {
        In in = Msg.reader(data);
        CampaignCopy c = new CampaignCopy();
        c.persistent = Plain.readMap(in);
        int scopes = in.readByte() & 0xFF;
        if (scopes > 32) throw new IOException("too many memory scopes");
        for (int i = 0; i < scopes; i++) {
            String name = in.readUTF();
            c.memories.put(name, Plain.readMap(in));
        }
        int nr = in.readShort() & 0xFFFF;
        if (nr > 2000) throw new IOException("too many factions");
        for (int i = 0; i < nr; i++) {
            String id = in.readUTF();
            float v = in.readFloat();
            if (Snapshot.bad(v)) throw new IOException("impossible standing");
            c.relations.put(id, Math.max(-1f, Math.min(1f, v)));
        }
        c.enemyFaction = in.readUTF(); c.enemyFleetName = in.readUTF(); c.enemyCommanderFirst = in.readUTF();
        c.enemyCommanderLast = in.readUTF(); c.enemyCommanderPortrait = in.readUTF(); c.enemyCommanderRank = in.readUTF();
        c.enemyCommanderPost = in.readUTF(); c.playerFactionName = in.readUTF(); c.playerGoal = in.readUTF(); c.enemyGoal = in.readUTF();
        c.locationName = in.readUTF(); c.locationId = in.readUTF(); c.dateText = in.readUTF();
        c.enemyCommanderLevel = in.readInt();
        c.hyperspace = in.readBoolean();
        int nt = in.readShort() & 0xFFFF;
        if (nt > 200) throw new IOException("too many tags");
        for (int i = 0; i < nt; i++) c.locationTags.add(in.readUTF());
        c.timestamp = in.readLong();
        c.skipped = in.readInt();
        int ns = in.readShort() & 0xFFFF;
        if (ns > MAX_SKIPPED_KEYS) throw new IOException("too many keys");
        for (int i = 0; i < ns; i++) c.skippedKeys.add(in.readUTF());
        return c;
    }

    /** How many values it carries, all scopes together (for the log). */
    public int count() {
        int n = persistent.size() + relations.size();
        for (Map<String, Object> m : memories.values()) n += m.size();
        return n;
    }
}

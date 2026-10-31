package wingman.net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Friend refit (Msg.REFIT_DATA, host -> one joiner): what a joiner's refit screen needs while the host is docked
 * at a market, built only from the host's campaign. The ship being refitted as it stands (with its limits worked
 * out on the host, with the host's skills), what the host can fit to it (cargo, storage, each submarket's weapons
 * and fighter LPCs), credits and story points, and the hull mods the host knows. Sent when the joiner opens the
 * refit screen (Msg.REFIT_ASK) and again whenever any of it changes. See docs/FRIEND_REFIT_DESIGN.md.
 */
public final class RefitData {
    public static final int WEAPON = 0, WING = 1;
    public static final int MAX_ITEMS = 300, MAX_MARKETS = 8, MAX_MODS = 600, MAX_SLOTS = 160, MAX_BAYS = 20, MAX_VALUES = 2000;
    /** The most of one item a stack may say it holds, and the most credits or story points believed. */
    static final int MAX_COUNT = 1000000;
    static final float MAX_CREDITS = 1e12f;

    /** A weapon or a fighter LPC, and how many. */
    public static final class Item {
        public int kind;
        public String id = "";
        public int count;
        public Item() {}
        public Item(int kind, String id, int count) { this.kind = kind; this.id = id; this.count = count; }
        public String key() { return RefitData.key(kind, id); }
    }

    /** A submarket the host could trade with here (storage is apart: see storage). */
    public static final class Market {
        public String id = "", name = "";
        /** Fraction of value added to what is bought and taken from what is sold. */
        public float tariff;
        /** The host may trade here; if not, why (as the game says it: "Requires: ... - favorable"). */
        public boolean usable = true;
        public String reason = "";
        public List<Item> stock = new ArrayList<Item>();
        /** Items (by key) this submarket won't sell, and won't buy, under its own rules. */
        public List<String> cantBuy = new ArrayList<String>(), cantSell = new ArrayList<String>();
    }

    /** A weapon group as the ship's variant has it: its slots in order, linked or alternating, autofire by default. */
    public static final class Group {
        public boolean alternating, autofire;
        public List<String> slots = new ArrayList<String>();
        public Group() {}
        public Group(boolean alternating, boolean autofire) { this.alternating = alternating; this.autofire = autofire; }
        public Group copy() { Group g = new Group(alternating, autofire); g.slots.addAll(slots); return g; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Group)) return false;
            Group g = (Group) o;
            return alternating == g.alternating && autofire == g.autofire && slots.equals(g.slots);
        }
        @Override public int hashCode() { return slots.hashCode() * 4 + (alternating ? 2 : 0) + (autofire ? 1 : 0); }
    }

    /** The most groups the codec carries; the game's own limit (RefitPlan.MAX_GROUPS) is checked apart. */
    public static final int MAX_GROUPS_WIRE = 20;

    // ---- the ship ----
    public String market = "", memberId = "", hullId = "", hullSize = "";
    /** Ordnance points the ship has and uses, with the host's skills. */
    public int opMax, opUsed;
    public int vents, caps, maxVents, maxCaps;
    /** S-mods it may have, with the host's skills. */
    public int maxSMods;
    /** Weapons by slot, as fitted (built-ins and decorations left out). */
    public List<String> fitSlots = new ArrayList<String>(), fitWeapons = new ArrayList<String>();
    /** Wings by bay, "" for an empty bay. */
    public List<String> wings = new ArrayList<String>();
    /** Hull mods fitted (not built into the hull); S-mods (built into this ship by story points); other permanent ones (D-mods). */
    public List<String> hullmods = new ArrayList<String>(), sMods = new ArrayList<String>(), permaMods = new ArrayList<String>();

    // ---- what the host has ----
    public float credits;
    public int storyPoints;
    public List<Item> cargo = new ArrayList<Item>();
    public boolean hasStorage;
    public List<Item> storage = new ArrayList<Item>();
    public List<Market> markets = new ArrayList<Market>();
    public List<String> knownHullmods = new ArrayList<String>();
    /** Base value per unit, by item key: prices are this plus (buying) or minus (selling) the submarket's tariff. */
    public Map<String, Float> values = new LinkedHashMap<String, Float>();
    /** Ordnance points each item named costs on this ship, with the host's skills, by item key. */
    public Map<String, Integer> opCosts = new LinkedHashMap<String, Integer>();
    /** Ordnance points each hull mod (known or fitted) costs on this hull size. */
    public Map<String, Integer> modCosts = new LinkedHashMap<String, Integer>();
    /** Hull mods that can't be built in (tagged no_build_in). */
    public List<String> noBuildIn = new ArrayList<String>();
    /** The ship's weapon groups; hasGroups is false when the host's build didn't send them (an older Wingman). */
    public boolean hasGroups;
    public List<Group> groups = new ArrayList<Group>();
    /** The hull in each station module slot (display only; sent after the groups). */
    public List<String> moduleSlots = new ArrayList<String>(), moduleHulls = new ArrayList<String>();
    public List<String> modulePictures = new ArrayList<String>();
    public String systemId = "";
    /** Host-derived slot descriptions, including empty slots. */
    public List<Slot> slots = new ArrayList<Slot>();

    public static final class Slot {
        public String place = "", size = "", type = "", builtInWeapon = "";
        public boolean editable, builtIn, hardpoint;
        public float x, y, angle, arc;
    }

    public Slot slot(String place) {
        for (Slot s : slots) if (s.place.equals(place)) return s;
        return null;
    }

    private static boolean validSlot(Slot s) {
        return s.place != null && !s.place.isEmpty() && s.place.length() <= 100
                && java.util.Arrays.asList("SMALL", "MEDIUM", "LARGE").contains(s.size)
                && java.util.Arrays.asList("BALLISTIC", "ENERGY", "MISSILE", "LAUNCH_BAY", "UNIVERSAL", "HYBRID",
                    "SYNERGY", "COMPOSITE", "BUILT_IN", "DECORATIVE", "SYSTEM", "STATION_MODULE").contains(s.type)
                && s.builtInWeapon != null && s.builtInWeapon.length() <= 100
                && s.editable != s.builtIn && Float.isFinite(s.x) && Math.abs(s.x) <= 1000000f
                && Float.isFinite(s.y) && Math.abs(s.y) <= 1000000f && Float.isFinite(s.angle) && Math.abs(s.angle) <= 1000000f
                && Float.isFinite(s.arc) && s.arc >= 0f && s.arc <= 360f;
    }

    public static String key(int kind, String id) { return (kind == WING ? "f:" : "w:") + id; }

    /** What one costs to buy from that submarket, or what selling one there brings. */
    public float buyPrice(Market m, String key) { Float v = values.get(key); return v == null ? 0f : Math.round(v * (1f + m.tariff)); }
    public float sellPrice(Market m, String key) { Float v = values.get(key); return v == null ? 0f : Math.round(v * Math.max(0f, 1f - m.tariff)); }

    private static String cap(String s, int n) { return s == null ? "" : s.length() > n ? s.substring(0, n) : s; }

    private static void writeStrings(Out out, List<String> l, int max, int len) throws IOException {
        int n = Math.min(l.size(), max);
        out.writeVarInt(n);
        for (int i = 0; i < n; i++) out.writeUTF(cap(l.get(i), len));
    }

    private static void writeInts(Out out, Map<String, Integer> m, int max) throws IOException {
        int n = Math.min(m.size(), max);
        out.writeVarInt(n);
        int i = 0;
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (i++ >= n) break;
            out.writeUTF(cap(e.getKey(), 102));
            out.writeVarInt(Math.max(0, Math.min(100000, e.getValue())));
        }
    }

    private static void readInts(In in, Map<String, Integer> m, int max) throws IOException {
        int n = count(in, max, "costs");
        for (int i = 0; i < n; i++) {
            String k = id(in, 102);
            int v = in.readVarInt();
            if (v < 0 || v > 100000) throw new IOException("impossible cost");
            m.put(k, v);
        }
    }

    private static void writeItems(Out out, List<Item> l) throws IOException {
        int n = Math.min(l.size(), MAX_ITEMS);
        out.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            Item it = l.get(i);
            out.writeByte(it.kind);
            out.writeUTF(cap(it.id, 100));
            out.writeVarInt(Math.max(0, Math.min(MAX_COUNT, it.count)));
        }
    }

    /** Weapon groups, appended after the older fields so a peer that doesn't know them ignores them. */
    static void writeGroups(Out o, List<Group> l) throws IOException {
        int n = Math.min(l.size(), MAX_GROUPS_WIRE);
        o.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            Group g = l.get(i);
            o.writeByte((g.alternating ? 1 : 0) | (g.autofire ? 2 : 0));
            writeStrings(o, g.slots, MAX_SLOTS, 100);
        }
    }

    static List<Group> readGroups(In in) throws IOException {
        int n = count(in, MAX_GROUPS_WIRE, "weapon groups");
        List<Group> l = new ArrayList<Group>(n);
        for (int i = 0; i < n; i++) {
            int f = in.readByte();
            if ((f & ~3) != 0) throw new IOException("unknown weapon group flags");
            Group g = new Group((f & 1) != 0, (f & 2) != 0);
            readStrings(in, g.slots, MAX_SLOTS, 100, "grouped weapons");
            l.add(g);
        }
        return l;
    }

    public byte[] encode() {
        Msg.W w = new Msg.W();
        try {
            Out o = w.out;
            o.writeUTF(cap(market, 80));
            o.writeUTF(cap(memberId, 64));
            o.writeUTF(cap(hullId, 100));
            o.writeUTF(cap(hullSize, 20));
            for (int v : new int[] { opMax, opUsed, vents, caps, maxVents, maxCaps, maxSMods }) o.writeVarInt(Math.max(0, Math.min(100000, v)));
            if (fitSlots.size() != fitWeapons.size() || fitSlots.size() > MAX_SLOTS) throw new IOException("invalid fitted weapons");
            int nf = fitSlots.size();
            o.writeVarInt(nf);
            for (int i = 0; i < nf; i++) {
                String place = fitSlots.get(i), weapon = fitWeapons.get(i);
                if (place == null || place.isEmpty() || place.length() > 100 || weapon == null || weapon.isEmpty() || weapon.length() > 100)
                    throw new IOException("invalid fitted weapon");
                o.writeUTF(place); o.writeUTF(weapon);
            }
            writeStrings(o, wings, MAX_BAYS, 100);
            writeStrings(o, hullmods, MAX_MODS, 100);
            writeStrings(o, sMods, MAX_MODS, 100);
            writeStrings(o, permaMods, MAX_MODS, 100);

            o.writeFloat(Math.max(-MAX_CREDITS, Math.min(MAX_CREDITS, credits)));
            o.writeVarInt(Math.max(0, Math.min(MAX_COUNT, storyPoints)));
            writeItems(o, cargo);
            o.writeBoolean(hasStorage);
            writeItems(o, storage);
            int nm = Math.min(markets.size(), MAX_MARKETS);
            o.writeByte(nm);
            for (int i = 0; i < nm; i++) {
                Market m = markets.get(i);
                o.writeUTF(cap(m.id, 64));
                o.writeUTF(cap(m.name, 60));
                o.writeFloat(Math.max(0f, Math.min(10f, m.tariff)));
                o.writeBoolean(m.usable);
                o.writeUTF(cap(m.reason, 200));
                writeItems(o, m.stock);
                writeStrings(o, m.cantBuy, MAX_ITEMS, 102);
                writeStrings(o, m.cantSell, MAX_ITEMS, 102);
            }
            writeStrings(o, knownHullmods, MAX_MODS, 100);
            int nv = Math.min(values.size(), MAX_VALUES);
            o.writeVarInt(nv);
            int i = 0;
            for (Map.Entry<String, Float> e : values.entrySet()) {
                if (i++ >= nv) break;
                o.writeUTF(cap(e.getKey(), 102));
                o.writeFloat(Math.max(0f, Math.min(MAX_CREDITS, e.getValue())));
            }
            writeInts(o, opCosts, MAX_VALUES);
            writeInts(o, modCosts, MAX_MODS);
            writeStrings(o, noBuildIn, MAX_MODS, 100);
            if (hasGroups) {
                writeGroups(o, groups);
                if (moduleSlots.size() != moduleHulls.size() || moduleSlots.size() != modulePictures.size() || moduleSlots.size() > MAX_SLOTS) throw new IOException("invalid modules");
                int nmod = moduleSlots.size();
                o.writeVarInt(nmod);
                for (int k = 0; k < nmod; k++) {
                    String place = moduleSlots.get(k), hull = moduleHulls.get(k);
                    if (place == null || place.isEmpty() || place.length() > 100 || hull == null || hull.isEmpty() || hull.length() > 100)
                        throw new IOException("invalid module");
                    o.writeUTF(place); o.writeUTF(hull);
                    String picture = modulePictures.get(k);
                    if (picture == null || picture.length() > 500) throw new IOException("invalid module picture");
                    o.writeUTF(picture);
                }
                if (slots.size() > MAX_SLOTS) throw new IOException("too many display slots");
                o.writeVarInt(slots.size());
                java.util.Set<String> places = new java.util.HashSet<String>();
                for (Slot s : slots) {
                    if (!validSlot(s) || !places.add(s.place))
                        throw new IOException("invalid display slot");
                    o.writeUTF(s.place); o.writeUTF(s.size); o.writeUTF(s.type); o.writeUTF(s.builtInWeapon);
                    o.writeByte((s.editable ? 1 : 0) | (s.builtIn ? 2 : 0) | (s.hardpoint ? 4 : 0));
                    o.writeFloat(s.x); o.writeFloat(s.y); o.writeFloat(s.angle); o.writeFloat(s.arc);
                }
                if (systemId == null || systemId.length() > 100) throw new IOException("invalid ship system");
                o.writeUTF(systemId);
            }
        } catch (IOException ex) { throw new IllegalStateException(ex); }
        return w.done();
    }

    /** An id from the host: a short one, used only to look things up in this game's own specs. */
    private static String id(In in, int max) throws IOException {
        String s = in.readUTF();
        if (s.length() > max) throw new IOException("id too long");
        return s;
    }

    private static int count(In in, int max, String what) throws IOException {
        int n = in.readVarInt();
        if (n < 0 || n > max) throw new IOException("too many " + what);
        return n;
    }

    private static void readStrings(In in, List<String> l, int max, int len, String what) throws IOException {
        int n = count(in, max, what);
        for (int i = 0; i < n; i++) l.add(id(in, len));
    }

    private static void readItems(In in, List<Item> l) throws IOException {
        int n = count(in, MAX_ITEMS, "items");
        for (int i = 0; i < n; i++) {
            Item it = new Item();
            it.kind = in.readByte();
            if (it.kind != WEAPON && it.kind != WING) throw new IOException("unknown kind of item");
            it.id = id(in, 100);
            it.count = in.readVarInt();
            if (it.count < 0 || it.count > MAX_COUNT) throw new IOException("impossible count");
            l.add(it);
        }
    }

    private static float number(In in, float min, float max) throws IOException {
        float f = in.readFloat();
        if (f != f || f < min || f > max) throw new IOException("impossible number");
        return f;
    }

    public static RefitData decode(byte[] data) throws IOException {
        In in = Msg.reader(data);
        RefitData d = new RefitData();
        d.market = Roster.clean(in.readUTF());
        d.memberId = id(in, 64);
        d.hullId = id(in, 100);
        d.hullSize = id(in, 20);
        int[] v = new int[7];
        for (int i = 0; i < v.length; i++) {
            v[i] = in.readVarInt();
            if (v[i] < 0 || v[i] > 100000) throw new IOException("impossible number");
        }
        d.opMax = v[0]; d.opUsed = v[1]; d.vents = v[2]; d.caps = v[3]; d.maxVents = v[4]; d.maxCaps = v[5]; d.maxSMods = v[6];
        int nf = count(in, MAX_SLOTS, "weapons");
        for (int i = 0; i < nf; i++) { d.fitSlots.add(id(in, 100)); d.fitWeapons.add(id(in, 100)); }
        readStrings(in, d.wings, MAX_BAYS, 100, "bays");
        readStrings(in, d.hullmods, MAX_MODS, 100, "hull mods");
        readStrings(in, d.sMods, MAX_MODS, 100, "hull mods");
        readStrings(in, d.permaMods, MAX_MODS, 100, "hull mods");

        d.credits = number(in, -MAX_CREDITS, MAX_CREDITS);
        d.storyPoints = count(in, MAX_COUNT, "story points");
        readItems(in, d.cargo);
        d.hasStorage = in.readBoolean();
        readItems(in, d.storage);
        int nm = in.readByte() & 0xFF;
        if (nm > MAX_MARKETS) throw new IOException("too many submarkets");
        for (int i = 0; i < nm; i++) {
            Market m = new Market();
            m.id = id(in, 64);
            m.name = Roster.clean(in.readUTF());
            m.tariff = number(in, 0f, 10f);
            m.usable = in.readBoolean();
            m.reason = Roster.cleanLong(in.readUTF());
            readItems(in, m.stock);
            readStrings(in, m.cantBuy, MAX_ITEMS, 102, "items");
            readStrings(in, m.cantSell, MAX_ITEMS, 102, "items");
            d.markets.add(m);
        }
        readStrings(in, d.knownHullmods, MAX_MODS, 100, "hull mods");
        int nv = count(in, MAX_VALUES, "prices");
        for (int i = 0; i < nv; i++) {
            String k = id(in, 102);
            d.values.put(k, number(in, 0f, MAX_CREDITS));
        }
        readInts(in, d.opCosts, MAX_VALUES);
        readInts(in, d.modCosts, MAX_MODS);
        readStrings(in, d.noBuildIn, MAX_MODS, 100, "hull mods");
        if (in.remaining() > 0) { d.groups = readGroups(in); d.hasGroups = true; }
        if (d.hasGroups) {
            int nmod = count(in, MAX_SLOTS, "modules");
            for (int i = 0; i < nmod; i++) { d.moduleSlots.add(id(in, 100)); d.moduleHulls.add(id(in, 100)); d.modulePictures.add(id(in, 500)); }
        }
        if (d.hasGroups) {
            int n = count(in, MAX_SLOTS, "display slots");
            java.util.Set<String> places = new java.util.HashSet<String>();
            for (int i = 0; i < n; i++) {
                Slot s = new Slot();
                s.place = id(in, 100); s.size = id(in, 20); s.type = id(in, 30); s.builtInWeapon = id(in, 100);
                if (s.place.isEmpty() || !places.add(s.place)) throw new IOException("invalid display slot");
                int flags = in.readByte();
                if ((flags & ~7) != 0) throw new IOException("invalid display slot flags");
                s.editable = (flags & 1) != 0; s.builtIn = (flags & 2) != 0; s.hardpoint = (flags & 4) != 0;
                s.x = number(in, -1000000f, 1000000f); s.y = number(in, -1000000f, 1000000f);
                s.angle = number(in, -1000000f, 1000000f); s.arc = number(in, 0f, 360f);
                if (!validSlot(s)) throw new IOException("invalid display slot");
                d.slots.add(s);
            }
            d.systemId = id(in, 100);
        }
        return d;
    }

    /** Every item of one key the host could fit: in cargo, in storage, and for sale in usable submarkets. */
    public int available(String key) {
        int n = count(cargo, key) + count(storage, key);
        for (Market m : markets) if (m.usable && !m.cantBuy.contains(key)) n += count(m.stock, key);
        return n;
    }

    /** The submarket with that id, or null. */
    public Market market(String id) {
        for (Market m : markets) if (m.id.equals(id)) return m;
        return null;
    }

    /** The weapon fitted in a slot, or null. */
    public String weaponIn(String slot) {
        int i = fitSlots.indexOf(slot);
        return i < 0 ? null : fitWeapons.get(i);
    }

    /** A module's weapon slot is "module slot/slot"; this is the hull the place is on: the module's, else the ship's (null if unknown). */
    public String hullOf(String place) {
        int bar = place.indexOf('/');
        if (bar < 0) return hullId;
        int k = moduleSlots.indexOf(place.substring(0, bar));
        return k < 0 || k >= moduleHulls.size() ? null : moduleHulls.get(k);
    }

    /** The slot's id on its own hull. */
    public static String local(String place) { return place.substring(place.indexOf('/') + 1); }

    public static int count(List<Item> l, String key) {
        int n = 0;
        for (Item it : l) if (it.key().equals(key)) n += it.count;
        return n;
    }
}

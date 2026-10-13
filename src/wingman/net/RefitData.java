package wingman.net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Friend refit (Msg.REFIT_DATA, host -> one joiner): what a joiner's refit screen needs while the host is docked
 * at a market, built only from the host's campaign. The joiner's picked ship as it stands (with its limits worked
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

    public byte[] encode() {
        Msg.W w = new Msg.W();
        try {
            Out o = w.out;
            o.writeUTF(cap(market, 80));
            o.writeUTF(cap(memberId, 64));
            o.writeUTF(cap(hullId, 100));
            o.writeUTF(cap(hullSize, 20));
            for (int v : new int[] { opMax, opUsed, vents, caps, maxVents, maxCaps, maxSMods }) o.writeVarInt(Math.max(0, Math.min(100000, v)));
            int nf = Math.min(Math.min(fitSlots.size(), fitWeapons.size()), MAX_SLOTS);
            o.writeVarInt(nf);
            for (int i = 0; i < nf; i++) { o.writeUTF(cap(fitSlots.get(i), 100)); o.writeUTF(cap(fitWeapons.get(i), 100)); }
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
        return d;
    }

    /** Every item of one key the host could fit: in cargo, in storage, and for sale in usable submarkets. */
    public int available(String key) {
        int n = count(cargo, key) + count(storage, key);
        for (Market m : markets) if (m.usable && !m.cantBuy.contains(key)) n += count(m.stock, key);
        return n;
    }

    public static int count(List<Item> l, String key) {
        int n = 0;
        for (Item it : l) if (it.key().equals(key)) n += it.count;
        return n;
    }
}

package wingman.compat;

import java.awt.Color;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.json.JSONArray;
import org.json.JSONObject;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.combat.BattleCreationContext;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.loading.VariantSource;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.graid.GroundRaidObjectivePlugin;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;

import wingman.net.In;
import wingman.net.Out;

/**
 * Exotica Technologies' ship upgrade menu for a joiner's refit (see ExoticaRefit for the messages' flow).
 *
 * The joiner opens Exotica's own menu on a mirror of the host's ship, cargo and market, and what they buy there is
 * recorded as steps (a price paid, a chip used, a method name). The host plays the steps back through Exotica's own
 * purchase methods on the real ship, so Exotica itself checks and charges them. Nothing here writes Exotica's data
 * on the host except through those methods.
 *
 * Exotica is another mod: it is reached by name through method handles, and without it (or with a version laid out
 * differently) this does nothing. Everything above the handles is plain data and runs without the game.
 */
public final class Exotica {
    private Exotica() {}

    public static final String MOD_ID = "exoticatechnologies";
    public static final String CH_ASK = MOD_ID + ":ask", CH_SHIP = MOD_ID + ":ship", CH_SET = MOD_ID + ":set", CH_RESULT = MOD_ID + ":result";
    public static final int VERSION = 1;
    /** A message is never longer than a mod channel allows (wingman.net.ModChannel.MAX_BYTES). */
    public static final int MAX_BYTES = 60 * 1024;
    public static final int MAX_STEPS = 64, MAX_KEY = 64, MAX_DATA = 256, MAX_INDUSTRIES = 64, MAX_COMMODITIES = 256, MAX_SPECIALS = 512;
    private static final int MAX_MODS = 256;
    public static final int BANDWIDTH = 0, UPGRADE = 1, EXOTIC = 2;
    private static final String NORMAL = "NORMAL", UPGRADE_ITEM = "et_upgrade", EXOTIC_ITEM = "et_exotic", CRATE_ITEM = "et_crate";

    // ---- the data of the messages ----

    /** One purchase: what, on which upgrade or exotic, by which of Exotica's methods. */
    public static final class Step {
        public final int kind;
        public final String target, method, exoticType;
        public final int level;
        public Step(int kind, String target, String method, int level, String exoticType) {
            this.kind = kind;
            this.target = target == null ? "" : target;
            this.method = method == null ? "" : method;
            this.level = level;
            this.exoticType = exoticType == null ? "" : exoticType;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Step)) return false;
            Step s = (Step) o;
            return kind == s.kind && target.equals(s.target) && method.equals(s.method) && level == s.level && exoticType.equals(s.exoticType);
        }
        @Override public int hashCode() { return ((kind * 31 + target.hashCode()) * 31 + method.hashCode()) * 31 + level; }
        @Override public String toString() { return kind + ":" + target + ":" + method + (level != 0 ? ":" + level : "") + (exoticType.isEmpty() ? "" : ":" + exoticType); }
    }

    /** An item in a hold that Exotica cares about: its special item id, its data, how many. */
    public static final class Special {
        public final String id, data;
        public final float qty;
        public Special(String id, String data, float qty) { this.id = id; this.data = data == null ? "" : data; this.qty = qty; }
    }

    /** What a hold has that matters here: commodities (what Exotica's resource methods spend) and Exotica's chips. */
    public static final class Cargo {
        public final Map<String, Float> commodities = new LinkedHashMap<String, Float>();
        public final List<Special> specials = new ArrayList<Special>();
        public void addCommodity(String id, float qty) {
            Float had = commodities.get(id);
            commodities.put(id, (had == null ? 0f : had.floatValue()) + qty);
        }
        public void addSpecial(String id, String data, float qty) {
            String d = data == null ? "" : data;
            for (int i = 0; i < specials.size(); i++) {
                Special s = specials.get(i);
                if (s.id.equals(id) && s.data.equals(d)) { specials.set(i, new Special(id, d, s.qty + qty)); return; }
            }
            specials.add(new Special(id, d, qty));
        }
        /** Dropped past the limits a message allows (a hold with that many kinds is not one Exotica would miss). */
        public void limit() {
            while (commodities.size() > MAX_COMMODITIES) {
                Iterator<String> it = commodities.keySet().iterator();
                String last = null;
                while (it.hasNext()) last = it.next();
                commodities.remove(last);
            }
            while (specials.size() > MAX_SPECIALS) specials.remove(specials.size() - 1);
        }
    }

    /** host -> joiner: the ship, and what the host's market and hold have. */
    public static final class ShipMsg {
        public String memberId = "", hullId = "", variantJson = "", modsJson = "", marketFactionId = "";
        public float credits, relToPlayer;
        public int storyPoints;
        public final List<String> industries = new ArrayList<String>();
        public Cargo fleet = new Cargo(), storage = new Cargo();
    }

    /** joiner -> host: what was bought. */
    public static final class SetMsg {
        public String memberId = "";
        public final List<Step> steps = new ArrayList<Step>();
    }

    /** host -> joiner: how it went. */
    public static final class ResultMsg {
        public String memberId = "", text = "";
        public boolean ok;
        public ResultMsg() {}
        public ResultMsg(String memberId, boolean ok, String text) { this.memberId = memberId; this.ok = ok; this.text = text; }
    }

    // ---- the codec: every violation of a limit refuses the whole message (IOException) ----

    private static void key(Out o, String s) throws IOException {
        if (s == null) s = "";
        if (s.length() > MAX_KEY) throw new IOException("key too long");
        o.writeUTF(s);
    }

    private static void text(Out o, String s, int max) throws IOException {
        if (s == null) s = "";
        if (s.length() > max) throw new IOException("text too long");
        o.writeUTF(s);
    }

    private static String readKey(In in) throws IOException {
        String s = in.readUTF();
        if (s.length() > MAX_KEY) throw new IOException("key too long");
        return s;
    }

    private static String readText(In in, int max) throws IOException {
        String s = in.readUTF();
        if (s.length() > max) throw new IOException("text too long");
        return s;
    }

    private static void number(Out o, float f) throws IOException {
        if (Float.isNaN(f) || Float.isInfinite(f)) throw new IOException("not a number");
        o.writeFloat(f);
    }

    private static float readNumber(In in) throws IOException {
        float f = in.readFloat();
        if (Float.isNaN(f) || Float.isInfinite(f)) throw new IOException("not a number");
        return f;
    }

    private static float readQty(In in) throws IOException {
        float f = readNumber(in);
        if (f < 0f) throw new IOException("negative amount");
        return f;
    }

    private static int count(In in, int max) throws IOException {
        int n = in.readVarInt();
        if (n < 0 || n > max) throw new IOException("count out of range");
        return n;
    }

    private static void version(In in) throws IOException {
        int v = in.readByte();
        if (v != VERSION) throw new IOException("unknown version " + v);
    }

    private static byte[] finish(Out o) throws IOException {
        byte[] b = o.toByteArray();
        if (b.length > MAX_BYTES) throw new IOException("ship too large");
        return b;
    }

    public static byte[] encodeAsk(String memberId) throws IOException {
        Out o = new Out();
        o.writeByte(VERSION);
        key(o, memberId);
        return finish(o);
    }

    public static String decodeAsk(byte[] data) throws IOException {
        In in = new In(data);
        version(in);
        return readKey(in);
    }

    private static void writeCargo(Out o, Cargo c) throws IOException {
        if (c.commodities.size() > MAX_COMMODITIES || c.specials.size() > MAX_SPECIALS) throw new IOException("cargo too long");
        o.writeVarInt(c.commodities.size());
        for (Map.Entry<String, Float> e : c.commodities.entrySet()) { key(o, e.getKey()); number(o, e.getValue().floatValue()); }
        o.writeVarInt(c.specials.size());
        for (Special s : c.specials) { key(o, s.id); text(o, s.data, MAX_DATA); number(o, s.qty); }
    }

    private static Cargo readCargo(In in) throws IOException {
        Cargo c = new Cargo();
        int n = count(in, MAX_COMMODITIES);
        for (int i = 0; i < n; i++) { String id = readKey(in); c.commodities.put(id, Float.valueOf(readQty(in))); }
        n = count(in, MAX_SPECIALS);
        for (int i = 0; i < n; i++) {
            String id = readKey(in), data = readText(in, MAX_DATA);
            c.specials.add(new Special(id, data, readQty(in)));
        }
        return c;
    }

    /** Throws IOException ("ship too large") instead of cutting anything. */
    public static byte[] encodeShip(ShipMsg m) throws IOException {
        Out o = new Out();
        o.writeByte(VERSION);
        key(o, m.memberId);
        key(o, m.hullId);
        text(o, m.variantJson, 65535);
        text(o, m.modsJson, 65535);
        number(o, m.credits);
        o.writeInt(m.storyPoints);
        key(o, m.marketFactionId);
        number(o, m.relToPlayer);
        if (m.industries.size() > MAX_INDUSTRIES) throw new IOException("too many industries");
        o.writeVarInt(m.industries.size());
        for (String s : m.industries) key(o, s);
        writeCargo(o, m.fleet);
        writeCargo(o, m.storage);
        return finish(o);
    }

    public static ShipMsg decodeShip(byte[] data) throws IOException {
        In in = new In(data);
        version(in);
        ShipMsg m = new ShipMsg();
        m.memberId = readKey(in);
        m.hullId = readKey(in);
        m.variantJson = readText(in, 65535);
        m.modsJson = readText(in, 65535);
        m.credits = readQty(in);
        m.storyPoints = in.readInt();
        m.marketFactionId = readKey(in);
        m.relToPlayer = readNumber(in);
        int n = count(in, MAX_INDUSTRIES);
        for (int i = 0; i < n; i++) m.industries.add(readKey(in));
        m.fleet = readCargo(in);
        m.storage = readCargo(in);
        return m;
    }

    public static byte[] encodeSet(SetMsg m) throws IOException {
        if (m.steps.size() > MAX_STEPS) throw new IOException("too many steps");
        Out o = new Out();
        o.writeByte(VERSION);
        key(o, m.memberId);
        o.writeVarInt(m.steps.size());
        for (Step s : m.steps) {
            o.writeByte(s.kind);
            key(o, s.target);
            key(o, s.method);
            o.writeInt(s.level);
            key(o, s.exoticType);
        }
        return finish(o);
    }

    public static SetMsg decodeSet(byte[] data) throws IOException {
        In in = new In(data);
        version(in);
        SetMsg m = new SetMsg();
        m.memberId = readKey(in);
        int n = count(in, MAX_STEPS);
        for (int i = 0; i < n; i++) {
            int kind = in.readByte();
            if (kind < BANDWIDTH || kind > EXOTIC) throw new IOException("unknown step kind");
            String target = readKey(in), method = readKey(in);
            int level = in.readInt();
            m.steps.add(new Step(kind, target, method, level, readKey(in)));
        }
        return m;
    }

    public static byte[] encodeResult(ResultMsg m) throws IOException {
        Out o = new Out();
        o.writeByte(VERSION);
        key(o, m.memberId);
        o.writeBoolean(m.ok);
        text(o, m.text, 1000);
        return finish(o);
    }

    public static ResultMsg decodeResult(byte[] data) throws IOException {
        In in = new In(data);
        version(in);
        ResultMsg m = new ResultMsg();
        m.memberId = readKey(in);
        m.ok = in.readBoolean();
        m.text = readText(in, 1000);
        return m;
    }

    // ---- the ship's variant, as far as the mirror needs it (the hull id travels beside it) ----

    public static final class VariantData {
        public final String hullId;
        public final List<String> hullmods = new ArrayList<String>(), perma = new ArrayList<String>(), smods = new ArrayList<String>();
        public VariantData(String hullId) { this.hullId = hullId; }
    }

    public static String variantJson(Collection<String> hullmods, Collection<String> perma, Collection<String> smods) {
        try {
            JSONObject j = new JSONObject();
            j.put("hullmods", new JSONArray(hullmods));
            j.put("perma", new JSONArray(perma));
            j.put("smods", new JSONArray(smods));
            return j.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    public static VariantData parseVariant(String hullId, String json) throws IOException {
        VariantData d = new VariantData(hullId);
        if (json == null || json.isEmpty()) return d;
        try {
            JSONObject j = new JSONObject(json);
            names(j.optJSONArray("hullmods"), d.hullmods);
            names(j.optJSONArray("perma"), d.perma);
            names(j.optJSONArray("smods"), d.smods);
        } catch (Exception e) {
            throw new IOException("bad variant");
        }
        return d;
    }

    private static void names(JSONArray a, List<String> into) {
        if (a == null) return;
        for (int i = 0; i < a.length() && into.size() < MAX_MODS; i++) {
            String s = a.optString(i, "");
            if (!s.isEmpty() && s.length() <= MAX_KEY) into.add(s);
        }
    }

    // ---- Exotica's own record of a ship (its mods JSON), as far as the recorder needs ----

    public static final class ModsData {
        public static final ModsData EMPTY = new ModsData();
        public float baseBandwidth;
        public final Map<String, Integer> upgrades = new TreeMap<String, Integer>();
        public final Map<String, String> exotics = new TreeMap<String, String>();
    }

    /** Upgrades and exotics may be left out when there are none; an exotic is an object with its type, a plain name, or in a list. */
    public static ModsData parseMods(String json) throws IOException {
        if (json == null || json.isEmpty()) return ModsData.EMPTY;
        ModsData m = new ModsData();
        try {
            JSONObject j = new JSONObject(json);
            m.baseBandwidth = (float) j.optDouble("baseBandwidth", 0d);
            JSONObject ups = j.optJSONObject("upgrades");
            if (ups != null) {
                Iterator<?> it = ups.keys();
                while (it.hasNext()) { String k = String.valueOf(it.next()); m.upgrades.put(k, Integer.valueOf(ups.optInt(k, 0))); }
            }
            Object ex = j.opt("exotics");
            if (ex instanceof JSONObject) {
                JSONObject eo = (JSONObject) ex;
                Iterator<?> it = eo.keys();
                while (it.hasNext()) {
                    String k = String.valueOf(it.next());
                    Object v = eo.opt(k);
                    m.exotics.put(k, v instanceof JSONObject ? ((JSONObject) v).optString("type", NORMAL) : NORMAL);
                }
            } else if (ex instanceof JSONArray) {
                JSONArray ea = (JSONArray) ex;
                for (int i = 0; i < ea.length(); i++) {
                    Object v = ea.opt(i);
                    if (v instanceof String) m.exotics.put((String) v, NORMAL);
                    else if (v instanceof JSONObject) {
                        String k = ((JSONObject) v).optString("key", "");
                        if (!k.isEmpty()) m.exotics.put(k, ((JSONObject) v).optString("type", NORMAL));
                    }
                }
            }
        } catch (Exception e) {
            throw new IOException("bad mods");
        }
        return m;
    }

    // ---- the recorder: what changed between two looks at the mirror is what was bought ----

    /** One look: the ship's mods, the credits and story points, the commodities in the hold and market storage, Exotica's chips by item and data. */
    public static final class Snapshot {
        public final float baseBandwidth, credits;
        public final int storyPoints;
        public final Map<String, Integer> upgrades;
        public final Map<String, String> exotics;
        public final Map<String, Float> commodities, chips;

        private Snapshot(float bw, float credits, int sp, Map<String, Integer> up, Map<String, String> ex, Map<String, Float> com, Map<String, Float> chips) {
            this.baseBandwidth = bw; this.credits = credits; this.storyPoints = sp;
            this.upgrades = up; this.exotics = ex; this.commodities = com; this.chips = chips;
        }

        public static Snapshot of(ModsData m, float credits, int sp, Cargo... holds) {
            Map<String, Float> com = new TreeMap<String, Float>(), chips = new TreeMap<String, Float>();
            for (Cargo c : holds) {
                if (c == null) continue;
                for (Map.Entry<String, Float> e : c.commodities.entrySet()) add(com, e.getKey(), e.getValue().floatValue());
                for (Special s : c.specials) if (UPGRADE_ITEM.equals(s.id) || EXOTIC_ITEM.equals(s.id)) add(chips, s.id + "|" + s.data, s.qty);
            }
            return new Snapshot(m.baseBandwidth, credits, sp, new TreeMap<String, Integer>(m.upgrades), new TreeMap<String, String>(m.exotics), com, chips);
        }

        private static void add(Map<String, Float> m, String k, float q) {
            Float had = m.get(k);
            m.put(k, (had == null ? 0f : had.floatValue()) + q);
        }

        /** This one with a step's change to the ship made, and everything else (what was paid, the hold) as in cur. */
        Snapshot after(Step s, Snapshot cur) {
            Map<String, Integer> up = new TreeMap<String, Integer>(upgrades);
            Map<String, String> ex = new TreeMap<String, String>(exotics);
            float bw = baseBandwidth;
            if (s.kind == BANDWIDTH) bw = cur.baseBandwidth;
            else if (s.kind == UPGRADE) {
                Integer l = cur.upgrades.get(s.target);
                if (l == null) up.remove(s.target); else up.put(s.target, l);
            } else {
                String t = cur.exotics.get(s.target);
                if (t == null) ex.remove(s.target); else ex.put(s.target, t);
            }
            return new Snapshot(bw, cur.credits, cur.storyPoints, up, ex, cur.commodities, cur.chips);
        }
    }

    private static float change(Map<String, Float> before, Map<String, Float> now, String prefix) {
        float d = 0f;
        for (Map.Entry<String, Float> e : now.entrySet()) {
            if (!e.getKey().startsWith(prefix)) continue;
            Float b = before.get(e.getKey());
            d += e.getValue().floatValue() - (b == null ? 0f : b.floatValue());
        }
        for (Map.Entry<String, Float> e : before.entrySet()) {
            if (e.getKey().startsWith(prefix) && !now.containsKey(e.getKey())) d -= e.getValue().floatValue();
        }
        return d;
    }

    private static boolean commoditiesFell(Snapshot base, Snapshot cur) {
        for (Map.Entry<String, Float> e : base.commodities.entrySet()) {
            Float c = cur.commodities.get(e.getKey());
            if ((c == null ? 0f : c.floatValue()) < e.getValue().floatValue() - 0.001f) return true;
        }
        return false;
    }

    /** The first change between two looks as a step, or null when there is none to report. Bandwidth, then upgrades, then exotics. */
    public static Step diff(Snapshot base, Snapshot cur) {
        if (cur.baseBandwidth > base.baseBandwidth + 0.001f) return new Step(BANDWIDTH, "", "bandwidth", 0, "");
        for (Map.Entry<String, Integer> e : cur.upgrades.entrySet()) {
            Integer had = base.upgrades.get(e.getKey());
            int lvl = e.getValue().intValue();
            if (had != null && lvl <= had.intValue()) continue;
            String k = e.getKey();
            String how;
            if (change(base.chips, cur.chips, UPGRADE_ITEM + "|" + k + ",") < -0.001f) how = "chip";
            else if (base.credits - cur.credits > 0.5f) how = "credits";
            else if (commoditiesFell(base, cur)) how = "resources";
            else how = "free";
            return new Step(UPGRADE, k, how, "chip".equals(how) ? lvl : 0, "");
        }
        for (String k : base.upgrades.keySet()) if (!cur.upgrades.containsKey(k)) return new Step(UPGRADE, k, "recover", 0, "");
        for (Map.Entry<String, String> e : cur.exotics.entrySet()) {
            if (base.exotics.containsKey(e.getKey())) continue;
            String k = e.getKey(), type = e.getValue();
            if (change(base.chips, cur.chips, EXOTIC_ITEM + "|" + k + ",") < -0.001f || !NORMAL.equals(type)) return new Step(EXOTIC, k, "chipInstall", 0, type);
            return new Step(EXOTIC, k, "install", 0, "");
        }
        for (String k : base.exotics.keySet()) {
            if (cur.exotics.containsKey(k)) continue;
            boolean chipBack = change(base.chips, cur.chips, EXOTIC_ITEM + "|" + k + ",") > 0.001f;
            return new Step(EXOTIC, k, chipBack || cur.storyPoints < base.storyPoints ? "recover" : "destroy", 0, "");
        }
        return null;
    }

    /** Turns looks at the mirror into the steps bought, in order, up to MAX_STEPS. */
    public static final class Recorder {
        private Snapshot base;
        public final List<Step> steps = new ArrayList<Step>();
        /** Set when a step past the limit was seen: nothing more is recorded. */
        public boolean full;
        /** Upgrade levels that fell without the upgrade going: not a purchase, left out. */
        public int ignored;

        public Recorder(Snapshot start) { base = start; }

        public void update(Snapshot cur) {
            if (full) return;
            Step s;
            while ((s = diff(base, cur)) != null) {
                if (steps.size() >= MAX_STEPS) { full = true; return; }
                steps.add(s);
                base = base.after(s, cur);
            }
            for (Map.Entry<String, Integer> e : base.upgrades.entrySet()) {
                Integer c = cur.upgrades.get(e.getKey());
                if (c != null && c.intValue() < e.getValue().intValue()) ignored++;
            }
            base = cur;
        }
    }

    // ---- the handles on Exotica ----

    private static final String P = "exoticatechnologies.";
    private static Class<?> modsC;
    private static MethodHandle loaderGet, loaderSet, factoryGen, modsNew, modsToJson, modsBase, modsSetBw, upgradesG, exoticsG, upMethodsG, exMethodsG,
            upKey, exKey, upCanUse, upApply, exCanUse, exApply, upSetStack, exSetStack, bwMult, bwPrice, bwCanUp, bwStep, bwMax, upChipOf, exChipOf,
            uiNew, uiLayout, uiShow, crateCargo;
    private static Class<?> upChipC, exChipC;
    private static boolean failed;

    /** Whether Exotica is loaded in this game. */
    public static boolean enabled() {
        try {
            return Global.getSettings().getModManager().isModEnabled(MOD_ID);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Whether Exotica is loaded and laid out as expected (looked into once). */
    public static boolean available() {
        if (failed || !enabled()) return false;
        try {
            find();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Class<?> cls(String name) throws ClassNotFoundException {
        return Class.forName(P + name, true, Global.getSettings().getScriptClassLoader());
    }

    // The game forbids its mods reflection: Exotica is reached through method handles, found once.
    private static synchronized void find() throws Throwable {
        if (failed) throw new IllegalStateException("Exotica Technologies is not laid out as Wingman expects");
        if (loaderGet != null) return;
        try {
            MethodHandles.Lookup l = MethodHandles.publicLookup();
            Class<?> mods = cls("modifications.ShipModifications"), loader = cls("modifications.ShipModLoader");
            Class<?> upgrade = cls("modifications.upgrades.Upgrade"), exotic = cls("modifications.exotics.Exotic");
            Class<?> upMeth = cls("ui.impl.shop.upgrades.methods.UpgradeMethod"), exMeth = cls("ui.impl.shop.exotics.methods.ExoticMethod");
            Class<?> bwh = cls("modifications.bandwidth.BandwidthHandler"), bw = cls("modifications.bandwidth.Bandwidth"), util = cls("util.Utilities");
            Class<?> ui = cls("ui.impl.shop.ShipModUIPlugin"), crate = cls("cargo.CrateItemPlugin");
            Class<?> upChip = cls("ui.impl.shop.upgrades.methods.ChipMethod"), exChip = cls("ui.impl.shop.exotics.methods.ChipMethod");
            MethodType fmv = MethodType.methodType(mods, FleetMemberAPI.class, ShipVariantAPI.class);
            MethodHandle get = l.findStatic(loader, "get", fmv);
            loaderSet = l.findStatic(loader, "set", MethodType.methodType(void.class, FleetMemberAPI.class, ShipVariantAPI.class, mods));
            factoryGen = l.findStatic(cls("modifications.ShipModFactory"), "generateForFleetMember", MethodType.methodType(mods, FleetMemberAPI.class));
            modsNew = l.findConstructor(mods, MethodType.methodType(void.class, JSONObject.class));
            modsToJson = l.findVirtual(mods, "toJson", MethodType.methodType(JSONObject.class, FleetMemberAPI.class));
            modsBase = l.findVirtual(mods, "getBaseBandwidth", MethodType.methodType(float.class));
            modsSetBw = l.findVirtual(mods, "setBandwidth", MethodType.methodType(void.class, float.class));
            upgradesG = l.findStaticGetter(cls("modifications.upgrades.UpgradesHandler"), "UPGRADES", Map.class);
            upMethodsG = l.findStaticGetter(cls("modifications.upgrades.UpgradesHandler"), "UPGRADE_METHODS", java.util.Set.class);
            exoticsG = l.findStaticGetter(cls("modifications.exotics.ExoticsHandler"), "EXOTICS", Map.class);
            exMethodsG = l.findStaticGetter(cls("modifications.exotics.ExoticsHandler"), "EXOTIC_METHODS", List.class);
            upKey = l.findVirtual(upMeth, "getKey", MethodType.methodType(String.class));
            exKey = l.findVirtual(exMeth, "getKey", MethodType.methodType(String.class));
            upCanUse = l.findVirtual(upMeth, "canUse", MethodType.methodType(boolean.class, FleetMemberAPI.class, mods, upgrade, MarketAPI.class));
            upApply = l.findVirtual(upMeth, "apply", MethodType.methodType(String.class, FleetMemberAPI.class, ShipVariantAPI.class, mods, upgrade, MarketAPI.class));
            exCanUse = l.findVirtual(exMeth, "canUse", MethodType.methodType(boolean.class, FleetMemberAPI.class, mods, exotic, MarketAPI.class));
            exApply = l.findVirtual(exMeth, "apply", MethodType.methodType(String.class, FleetMemberAPI.class, ShipVariantAPI.class, mods, exotic, MarketAPI.class));
            upSetStack = l.findVirtual(upChip, "setUpgradeChipStack", MethodType.methodType(void.class, CargoStackAPI.class));
            exSetStack = l.findVirtual(exChip, "setChipStack", MethodType.methodType(void.class, CargoStackAPI.class));
            bwMult = l.findStatic(bwh, "getMarketBandwidthMult", MethodType.methodType(float.class, MarketAPI.class));
            bwPrice = l.findStatic(bwh, "getBandwidthUpgradePrice", MethodType.methodType(float.class, FleetMemberAPI.class, float.class, float.class));
            bwCanUp = l.findStatic(bwh, "canUpgrade", MethodType.methodType(boolean.class, mods, FleetMemberAPI.class));
            bwStep = l.findStaticGetter(bw, "BANDWIDTH_STEP", float.class);
            bwMax = l.findStaticGetter(bw, "MAX_BANDWIDTH", float.class);
            upChipOf = l.findStatic(util, "getUpgradeChip", MethodType.methodType(CargoStackAPI.class, CargoAPI.class, String.class, int.class));
            exChipOf = l.findStatic(util, "getExoticChip", MethodType.methodType(CargoStackAPI.class, CargoAPI.class, String.class, String.class));
            uiNew = l.findConstructor(ui, MethodType.methodType(void.class, InteractionDialogAPI.class, CustomPanelAPI.class, float.class, float.class));
            uiLayout = l.findVirtual(ui, "layoutPanels", MethodType.methodType(CustomPanelAPI.class));
            uiShow = l.findVirtual(ui, "showPanel", MethodType.methodType(CustomPanelAPI.class, FleetMemberAPI.class, ShipVariantAPI.class));
            crateCargo = l.findVirtual(crate, "getCargo", MethodType.methodType(CargoAPI.class));
            modsC = mods;
            upChipC = upChip;
            exChipC = exChip;
            loaderGet = get; // last: a half-found set is not used
        } catch (Throwable t) {
            failed = true;
            Global.getLogger(Exotica.class).warn("Wingman: Exotica Technologies is loaded but not as expected; its menu is left out of the friend refit", t);
            throw t;
        }
    }

    // ---- reading a hold ----

    /** The commodities and Exotica chips in a hold, those in crates too. */
    static Cargo readHold(CargoAPI c) {
        Cargo out = new Cargo();
        collect(c, out, 0);
        out.limit();
        return out;
    }

    private static void collect(CargoAPI c, Cargo out, int depth) {
        if (c == null) return;
        for (CargoStackAPI s : c.getStacksCopy()) {
            try {
                if (s.isCommodityStack()) {
                    String id = s.getCommodityId();
                    if (id != null && id.length() <= MAX_KEY && s.getSize() > 0f) out.addCommodity(id, s.getSize());
                } else if (s.isSpecialStack()) {
                    SpecialItemData d = s.getSpecialDataIfSpecial();
                    if (d == null || d.getId() == null) continue;
                    String id = d.getId();
                    if (CRATE_ITEM.equals(id)) {
                        if (depth < 2) collect((CargoAPI) crateCargo.invoke(s.getPlugin()), out, depth + 1);
                    } else if (UPGRADE_ITEM.equals(id) || EXOTIC_ITEM.equals(id)) {
                        String data = d.getData() == null ? "" : d.getData();
                        if (data.length() <= MAX_DATA && s.getSize() > 0f) out.addSpecial(id, data, s.getSize());
                    }
                }
            } catch (Throwable t) { /* that stack is left out */ }
        }
    }

    private static CargoAPI storageOf(MarketAPI market) {
        try {
            SubmarketAPI sm = market == null ? null : market.getSubmarket(Submarkets.SUBMARKET_STORAGE);
            return sm == null ? null : sm.getCargo();
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- the host: the ship's message, and the purchases played back ----

    /** The message for a joiner's ask. Throws IOException ("ship too large") when it would not fit a message. */
    public static byte[] buildShip(FleetMemberAPI m, MarketAPI market) throws Throwable {
        find();
        ShipVariantAPI v = m.getVariant();
        ShipMsg msg = new ShipMsg();
        msg.memberId = m.getId();
        msg.hullId = v.getHullSpec().getHullId();
        List<String> perma = new ArrayList<String>(v.getPermaMods()), smods = new ArrayList<String>(v.getSMods());
        List<String> mods = new ArrayList<String>(v.getNonBuiltInHullmods());
        mods.removeAll(perma);
        msg.variantJson = variantJson(mods, perma, smods);
        Object em = loaderGet.invoke(m, v);
        if (em == null) { // what Exotica's own refit button does: roll the ship's mods once and keep them, so replay sees the same bandwidth
            em = factoryGen.invoke(m);
            loaderSet.invoke(m, v, em);
        }
        msg.modsJson = String.valueOf(modsToJson.invoke(em, m));
        CampaignFleetAPI fleet = Global.getSector().getPlayerFleet();
        msg.credits = fleet.getCargo().getCredits().get();
        msg.storyPoints = Global.getSector().getPlayerStats().getStoryPoints();
        msg.marketFactionId = market.getFactionId();
        msg.relToPlayer = market.getFaction().getRelToPlayer().getRel();
        for (Industry i : market.getIndustries()) if (msg.industries.size() < MAX_INDUSTRIES) msg.industries.add(i.getId());
        msg.fleet = readHold(fleet.getCargo());
        msg.storage = readHold(storageOf(market));
        return encodeShip(msg);
    }

    /** How a replay went: the steps done, and why the next one was not. */
    public static final class Outcome {
        public int applied;
        public String failure;
    }

    private static Object byKey(Object collection, String key, MethodHandle keyOf) throws Throwable {
        if (collection instanceof Map) return ((Map<?, ?>) collection).get(key);
        for (Object o : (Collection<?>) collection) if (key.equals(keyOf.invoke(o))) return o;
        return null;
    }

    private static CargoStackAPI chip(MethodHandle finder, CargoAPI fleetHold, CargoAPI storage, String key, Object arg) throws Throwable {
        CargoStackAPI s = (CargoStackAPI) finder.invoke(fleetHold, key, arg);
        if (s == null && storage != null) s = (CargoStackAPI) finder.invoke(storage, key, arg);
        return s;
    }

    /**
     * Plays a joiner's steps on the host's ship through Exotica's own methods (they check and charge), stopping at the
     * first that can't be done. The ship's variant is replaced by a copy first, as the friend refit does. Game thread.
     */
    public static Outcome replay(FleetMemberAPI m, MarketAPI market, List<Step> steps) throws Throwable {
        find();
        Outcome out = new Outcome();
        memberForJson = m;
        try {
            return replayOn(m, market, steps, out);
        } finally {
            memberForJson = null;
        }
    }

    private static Outcome replayOn(FleetMemberAPI m, MarketAPI market, List<Step> steps, Outcome out) throws Throwable {
        ShipVariantAPI nv = m.getVariant().clone();
        nv.setSource(VariantSource.REFIT);
        m.setVariant(nv, false, true);
        ShipVariantAPI v = m.getVariant();
        Object mods = loaderGet.invoke(m, v);
        if (mods == null) {
            mods = factoryGen.invoke(m);
            loaderSet.invoke(m, v, mods);
        }
        CampaignFleetAPI fleet = Global.getSector().getPlayerFleet();
        CargoAPI hold = fleet.getCargo(), storage = storageOf(market);
        for (Step s : steps) {
            String label = s.kind == BANDWIDTH ? "bandwidth" : s.target + " (" + s.method + ")";
            String why = null;
            if (s.kind == BANDWIDTH) {
                float mult = ((Float) bwMult.invoke(market)).floatValue(), base = ((Float) modsBase.invoke(mods)).floatValue();
                float max = ((Float) bwMax.invoke()).floatValue(), step = ((Float) bwStep.invoke()).floatValue();
                if (!((Boolean) bwCanUp.invoke(mods, m)).booleanValue() || base >= max) why = "can't be raised";
                else {
                    float price = ((Float) bwPrice.invoke(m, base, mult)).floatValue();
                    if (hold.getCredits().get() < price) why = "not enough credits";
                    else {
                        hold.getCredits().subtract(price);
                        modsSetBw.invoke(mods, Math.min(base + step * mult, max));
                        loaderSet.invoke(m, v, mods);
                    }
                }
            } else {
                boolean up = s.kind == UPGRADE;
                Object thing = byKey(up ? upgradesG.invoke() : exoticsG.invoke(), s.target, null);
                Object meth = byKey(up ? upMethodsG.invoke() : exMethodsG.invoke(), s.method, up ? upKey : exKey);
                MethodHandle canUse = up ? upCanUse : exCanUse, apply = up ? upApply : exApply;
                MethodHandle setStack = up ? upSetStack : exSetStack;
                Class<?> chipC = up ? upChipC : exChipC;
                boolean chips = "chip".equals(s.method) && up || "chipInstall".equals(s.method) && !up;
                CargoStackAPI stack = null;
                if (thing == null || meth == null) why = "not known to this game";
                else if (chips && !chipC.isInstance(meth)) why = "not a chip method";
                else {
                    if (up && chips) {
                        // the chip's own checks first (it needs a chip in the hold, and the ship must take it), then the chip itself
                        if (!((Boolean) canUse.invoke(meth, m, mods, thing, market)).booleanValue()) why = "can't be done";
                        else {
                            stack = chip(upChipOf, hold, storage, s.target, Integer.valueOf(s.level));
                            if (stack == null) why = "no such chip";
                            else if (s.level <= currentLevel(mods, s.target)) why = "no better than the ship has";
                            else setStack.invoke(meth, stack);
                        }
                    } else if (chips) {
                        stack = chip(exChipOf, hold, storage, s.target, s.exoticType);
                        if (stack == null) why = "no such chip";
                        else setStack.invoke(meth, stack);
                    }
                    try {
                        if (why == null && !((Boolean) canUse.invoke(meth, m, mods, thing, market)).booleanValue()) why = "can't be done";
                        if (why == null) apply.invoke(meth, m, v, mods, thing, market);
                    } finally {
                        if (chips) setStack.invoke(meth, (Object) null); // the method object is Exotica's, shared: no chip left in it
                    }
                }
                if (why == null) mods = loaderGet.invoke(m, v); // the method has set its own
            }
            if (why != null) { out.failure = label + ": " + why; break; }
            out.applied++;
        }
        m.updateStats();
        return out;
    }

    private static int currentLevel(Object mods, String upgradeKey) throws Throwable {
        ModsData d = parseMods(String.valueOf(modsToJson.invoke(mods, memberForJson)));
        Integer l = d.upgrades.get(upgradeKey);
        return l == null ? 0 : l.intValue();
    }

    /** The member whose mods JSON is being read (toJson wants it for the base bandwidth); set for the length of a replay or look. */
    private static FleetMemberAPI memberForJson;

    // ---- the joiner: a mirror of the host's side, and Exotica's menu on it ----

    /** A stand-in player fleet with the host's ship, hold and credits, and a market with the host's faction and industries; all taken away by remove(). */
    static final class Mirror {
        CampaignFleetAPI fleet;
        LocationAPI place;
        StarSystemAPI system;
        MarketAPI market;
        SectorEntityToken token;
        FleetMemberAPI member;
        ShipVariantAPI variant;
        int storyBefore = -1;
        String relFaction;
        float relBefore;
        boolean placed, removed;

        static Mirror install(ShipMsg msg) throws Throwable {
            SectorAPI sector = Global.getSector();
            if (sector == null || sector.getPlayerFleet() != null) throw new IllegalStateException("a campaign is loaded");
            Mirror mi = new Mirror();
            try {
                mi.fleet = Global.getFactory().createEmptyFleet("player", "Wingman Exotica", true);
                fill(mi.fleet.getCargo(), msg.fleet);
                mi.fleet.getCargo().getCredits().set(msg.credits);
                sector.setPlayerFleet(mi.fleet);
                mi.placed = true;
                mi.system = sector.createStarSystem("Wingman Exotica");
                mi.place = mi.system;
                mi.system.addEntity(mi.fleet);
                mi.fleet.setLocation(0f, 0f);
                try {
                    mi.storyBefore = sector.getPlayerStats().getStoryPoints();
                    sector.getPlayerStats().setStoryPoints(msg.storyPoints);
                } catch (Throwable t) { /* no story points then */ }
                VariantData vd = parseVariant(msg.hullId, msg.variantJson);
                ShipHullSpecAPI spec = Global.getSettings().getHullSpec(vd.hullId);
                if (spec == null) throw new IllegalStateException("hull " + vd.hullId + " is not in this game");
                mi.variant = Global.getSettings().createEmptyVariant(vd.hullId + "_wingman_exotica", spec);
                for (String mod : vd.perma) if (Global.getSettings().getHullModSpec(mod) != null) mi.variant.addPermaMod(mod, vd.smods.contains(mod));
                for (String mod : vd.hullmods) if (Global.getSettings().getHullModSpec(mod) != null) mi.variant.addMod(mod);
                mi.member = Global.getFactory().createFleetMember(FleetMemberType.SHIP, mi.variant);
                mi.member.setId(msg.memberId);
                mi.fleet.getFleetData().addFleetMember(mi.member);
                find();
                Object mods = msg.modsJson.isEmpty() ? factoryGen.invoke(mi.member) : modsNew.invoke(new JSONObject(msg.modsJson));
                loaderSet.invoke(mi.member, mi.variant, mods);
                mi.market = Global.getFactory().createMarket("wingman_exotica", "Host market", 3);
                try { mi.market.setFactionId(msg.marketFactionId); } catch (Throwable t) { /* the player's faction then */ }
                mi.market.addSubmarket(Submarkets.SUBMARKET_STORAGE);
                try { fill(mi.market.getSubmarket(Submarkets.SUBMARKET_STORAGE).getCargo(), msg.storage); } catch (Throwable t) { /* an empty storage then */ }
                mi.token = mi.system.createToken(0f, 0f);
                mi.market.setPrimaryEntity(mi.token);
                mi.token.setMarket(mi.market);
                for (String id : msg.industries) {
                    try { mi.market.addIndustry(id); } catch (Throwable t) { /* one this game lacks */ }
                }
                try {
                    mi.relFaction = msg.marketFactionId;
                    mi.relBefore = sector.getPlayerFaction().getRelationship(mi.relFaction);
                    sector.getPlayerFaction().setRelationship(mi.relFaction, msg.relToPlayer);
                } catch (Throwable t) { mi.relFaction = null; }
            } catch (Throwable t) {
                mi.remove();
                throw t;
            }
            return mi;
        }

        private static void fill(CargoAPI c, Cargo from) {
            for (Map.Entry<String, Float> e : from.commodities.entrySet()) {
                try { c.addCommodity(e.getKey(), e.getValue().floatValue()); } catch (Throwable t) { /* a commodity this game lacks */ }
            }
            for (Special s : from.specials) {
                try { c.addSpecial(new SpecialItemData(s.id, s.data.isEmpty() ? null : s.data), s.qty); } catch (Throwable t) { /* an item this game lacks */ }
            }
        }

        void remove() {
            if (removed) return;
            removed = true;
            SectorAPI sector = Global.getSector();
            if (sector == null) return;
            try {
                if (relFaction != null) sector.getPlayerFaction().setRelationship(relFaction, relBefore);
            } catch (Throwable t) { /* gone with the sector */ }
            try {
                if (storyBefore >= 0) sector.getPlayerStats().setStoryPoints(storyBefore);
            } catch (Throwable t) { /* gone with the sector */ }
            try {
                if (placed && sector.getPlayerFleet() == fleet) sector.setPlayerFleet(null);
            } catch (Throwable t) { /* gone with the sector */ }
            try {
                if (place != null && fleet != null) place.removeEntity(fleet);
            } catch (Throwable t) { /* gone with the sector */ }
            try {
                if (system != null) sector.removeStarSystem(system);
            } catch (Throwable t) { /* gone with the sector */ }
        }
    }

    /** The dialog Exotica's menu looks to for the market it is at: nothing but that. */
    static final class DialogStub implements InteractionDialogAPI {
        private final SectorEntityToken target;
        DialogStub(SectorEntityToken target) { this.target = target; }
        public void setTextWidth(float width) {}
        public void setTextHeight(float height) {}
        public void setXOffset(float xOffset) {}
        public void setYOffset(float yOffset) {}
        public void setPromptText(String promptText) {}
        public void hideTextPanel() {}
        public void showTextPanel() {}
        public float getTextWidth() { return 0f; }
        public float getTextHeight() { return 0f; }
        public float getXOffset() { return 0f; }
        public float getYOffset() { return 0f; }
        public String getPromptText() { return null; }
        public void flickerStatic(float in, float out) {}
        public OptionPanelAPI getOptionPanel() { return null; }
        public TextPanelAPI getTextPanel() { return null; }
        public VisualPanelAPI getVisualPanel() { return null; }
        public SectorEntityToken getInteractionTarget() { return target; }
        public InteractionDialogPlugin getPlugin() { return null; }
        public void setOptionOnEscape(String text, Object optionId) {}
        public void startBattle(BattleCreationContext context) {}
        public void dismiss() {}
        public void dismissAsCancel() {}
        public void showFleetMemberPickerDialog(String title, String okText, String cancelText, int rows, int cols, float iconSize,
                boolean canPickNotReady, boolean canPickMultiple, List<FleetMemberAPI> pool, FleetMemberPickerListener listener) {}
        public void showCustomDialog(float customPanelWidth, float customPanelHeight, CustomDialogDelegate delegate) {}
        public void hideVisualPanel() {}
        public void showCommDirectoryDialog(CommDirectoryAPI dir) {}
        public void setOptionOnConfirm(String text, Object optionId) {}
        public void setOpacity(float opacity) {}
        public void setBackgroundDimAmount(float backgroundDimAmount) {}
        public void setPlugin(InteractionDialogPlugin plugin) {}
        public void setInteractionTarget(SectorEntityToken interactionTarget) {}
        public void showCargoPickerDialog(String title, String okText, String cancelText, boolean small, float textPanelWidth,
                CargoAPI cargo, CargoPickerListener listener) {}
        public void showIndustryPicker(String title, String okText, MarketAPI market, List<Industry> industries, IndustryPickerListener listener) {}
        public void makeOptionOpenCore(String optionId, CoreUITabId tabId, CampaignUIAPI.CoreUITradeMode mode) {}
        public void makeOptionOpenCore(String optionId, CoreUITabId tabId, CampaignUIAPI.CoreUITradeMode mode, boolean onlyShowTargetTabShortcut) {}
        public void setOptionColor(Object optionId, Color color) {}
        public void makeStoryOption(Object optionId, int storyPoints, float bonusXPFraction, String soundId) {}
        public void addOptionSelectedText(Object optionId) {}
        public void addOptionSelectedText(Object optionId, boolean allowPrintingStoryOption) {}
        public void showFleetMemberRecoveryDialog(String title, List<FleetMemberAPI> pool, FleetMemberPickerListener listener) {}
        public void showFleetMemberRecoveryDialog(String title, List<FleetMemberAPI> pool, List<FleetMemberAPI> storyPool, FleetMemberPickerListener listener) {}
        public void showGroundRaidTargetPicker(String title, String okText, MarketAPI market, List<GroundRaidObjectivePlugin> data,
                GroundRaidTargetPickerDelegate listener) {}
        public void showVisualPanel() {}
        public void showCustomProductionPicker(CustomProductionPickerDelegate delegate) {}
        public void showCampaignEntityPicker(String title, String selectedText, String okText, FactionAPI factionForUIColors,
                List<SectorEntityToken> entities, CampaignEntityPickerListener listener) {}
        public boolean isCurrentOptionHadAConfirm() { return false; }
        public void showCustomVisualDialog(float customPanelWidth, float customPanelHeight, CustomVisualDialogDelegate delegate) {}
        public void showCargoPickerDialog(String title, String okText, String cancelText, boolean small, float textPanelWidth,
                float width, float height, CargoAPI cargo, CargoPickerListener listener) {}
    }

    /** What the menu leaves behind when it closes: the ship and what was bought. */
    public static final class Closed {
        public final String memberId;
        public final List<Step> steps;
        public final boolean full;
        Closed(String memberId, List<Step> steps, boolean full) { this.memberId = memberId; this.steps = steps; this.full = full; }
    }

    // The one menu that can be open (the joiner has one refit screen).
    private static Mirror mirror;
    private static Object menuPlugin;
    private static CustomPanelAPI root;
    private static Recorder recorder;
    private static String menuMember = "";
    private static float menuX, menuY, menuW, menuH;
    private static int frames;

    public static synchronized boolean active() { return root != null; }

    /** Where the menu is on screen: x, y (from the bottom left), width, height. */
    public static synchronized float[] rect() { return new float[] { menuX, menuY, menuW, menuH }; }

    /** Opens Exotica's menu on the host's ship. Returns null when it is open, else why not. */
    public static synchronized String open(ShipMsg msg) {
        if (root != null) return "the Exotica menu is already open";
        try {
            if (!enabled()) return "Exotica Technologies is not loaded";
            find();
            mirror = Mirror.install(msg);
            memberForJson = mirror.member;
            float sw = Global.getSettings().getScreenWidth(), sh = Global.getSettings().getScreenHeight();
            menuW = sw * 0.66f;
            menuH = sh * 0.65f;
            menuX = (sw - menuW) / 2f;
            menuY = (sh - menuH) / 2f;
            root = Global.getSettings().createCustom(menuW, menuH, null);
            root.getPosition().setLocation(menuX, menuY);
            menuPlugin = uiNew.invoke(new DialogStub(mirror.token), root, menuW, menuH);
            CustomPanelAPI panel = (CustomPanelAPI) uiLayout.invoke(menuPlugin);
            panel.getPosition().inTL(-1f, 0f);
            uiShow.invoke(menuPlugin, mirror.member, mirror.variant);
            menuMember = msg.memberId;
            recorder = new Recorder(capture());
            frames = 0;
            return null;
        } catch (Throwable t) {
            Global.getLogger(Exotica.class).warn("Wingman: Exotica's menu could not be opened", t);
            teardown();
            return "Exotica's menu could not be opened (see log)";
        }
    }

    private static Snapshot capture() throws Throwable {
        Object mods = loaderGet.invoke(mirror.member, mirror.variant);
        memberForJson = mirror.member;
        ModsData md = mods == null ? ModsData.EMPTY : parseMods(String.valueOf(modsToJson.invoke(mods, mirror.member)));
        CargoAPI hold = mirror.fleet.getCargo();
        return Snapshot.of(md, hold.getCredits().get(), Global.getSector().getPlayerStats().getStoryPoints(), readHold(hold), readHold(storageOf(mirror.market)));
    }

    /** Every frame while open. False if the menu failed and has been taken down. */
    public static synchronized boolean frame(float dt) {
        if (root == null) return false;
        try {
            root.advance(dt);
            if (++frames % 4 == 0) recorder.update(capture());
            return true;
        } catch (Throwable t) {
            Global.getLogger(Exotica.class).warn("Wingman: Exotica's menu failed; closed", t);
            teardown();
            return false;
        }
    }

    /** The mouse and keys, for the menu; the events are used up so what is under it doesn't see them. */
    public static synchronized void input(List<InputEventAPI> events) {
        if (root == null || events == null) return;
        try {
            root.processInput(events);
        } catch (Throwable t) {
            Global.getLogger(Exotica.class).warn("Wingman: Exotica's menu failed; closed", t);
            teardown();
        }
        for (InputEventAPI e : events) {
            try { e.consume(); } catch (Throwable t) { /* already used */ }
        }
    }

    public static synchronized void render() {
        if (root == null) return;
        try {
            root.render(1f);
        } catch (Throwable t) {
            Global.getLogger(Exotica.class).warn("Wingman: Exotica's menu failed; closed", t);
            teardown();
        }
    }

    /** Closes the menu and takes the mirror down; what was bought is returned (null if it was not open). */
    public static synchronized Closed close() {
        if (root == null) return null;
        try {
            recorder.update(capture());
        } catch (Throwable t) { /* what was seen before then */ }
        Closed c = new Closed(menuMember, new ArrayList<Step>(recorder.steps), recorder.full);
        if (recorder.ignored > 0) Global.getLogger(Exotica.class).info("Wingman: " + recorder.ignored + " Exotica change(s) were not purchases and were left out");
        teardown();
        return c;
    }

    private static void teardown() {
        root = null;
        menuPlugin = null;
        recorder = null;
        memberForJson = null;
        if (mirror != null) mirror.remove();
        mirror = null;
    }
}

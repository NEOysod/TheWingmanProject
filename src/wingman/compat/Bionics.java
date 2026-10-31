package wingman.compat;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.CustomVisualDialogDelegate;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.util.FaderUtil;

import wingman.net.In;
import wingman.net.Out;

/**
 * Bionic Alteration's workshop for a joiner's own captain (see BionicsRefit for the messages' flow).
 *
 * The joiner opens the mod's own workshop on a mirror of the host's captain and of the mod's bionic container
 * (a cargo the mod keeps in the sector's persistent data). What the joiner changes there is recorded as steps (a
 * bionic installed on or taken off a limb); the host checks the whole plan, then plays it back through the mod's own
 * install/remove methods, which check the limits and take the item from or return it to its container. The host's
 * data is changed only through the mod's own methods: those two, setUpSkill, compressAllBionics (as the mod's own
 * workshop does when it opens) and setUpListOfficers for a captain the mod has not set up yet.
 *
 * The mod is reached by name through method handles: without it (or with a version laid out differently) this does
 * nothing. Everything above the handles is plain data and runs without the game.
 */
public final class Bionics {
    private Bionics() {}

    public static final String MOD_ID = "pigeonpun_bionicalteration";
    public static final String CH_ASK = MOD_ID + ":ask", CH_SHIP = MOD_ID + ":ship", CH_SET = MOD_ID + ":set", CH_RESULT = MOD_ID + ":result";
    public static final int VERSION = 1;
    /** A message is never longer than a mod channel allows (wingman.net.ModChannel.MAX_BYTES). */
    public static final int MAX_BYTES = 60 * 1024;
    public static final int MAX_ANATOMY = 32, MAX_STACKS = 256, MAX_STEPS = 32, MAX_KEY = 128, MAX_DATA = 256, MAX_TEXT = 600;
    private static final int MAX_SKILLS = 120;
    public static final int INSTALL = 0, REMOVE = 1;
    /** The workshop page the mod's own UI calls "WORKSHOP". */
    private static final String WORKSHOP = "WORKSHOP";
    /** Written on a battle's captain copy (plain text), to give the joiner's ship its bionics (see stage). */
    static final String BATTLE_KEY = "$ba_wingman";

    // ---- the data of the messages ----

    /** One limb and what is on it ("" when nothing). */
    public static final class Anatomy {
        public final String limb, bionic, overclock;
        public Anatomy(String limb, String bionic, String overclock) {
            this.limb = limb == null ? "" : limb;
            this.bionic = bionic == null ? "" : bionic;
            this.overclock = overclock == null ? "" : overclock;
        }
        public boolean occupied() { return !bionic.isEmpty(); }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Anatomy)) return false;
            Anatomy a = (Anatomy) o;
            return limb.equals(a.limb) && bionic.equals(a.bionic) && overclock.equals(a.overclock);
        }
        @Override public int hashCode() { return (limb + "|" + bionic + "|" + overclock).hashCode(); }
        @Override public String toString() { return limb + ":" + bionic + (overclock.isEmpty() ? "" : ":" + overclock); }
    }

    /** A stack of the mod's bionic container: the item, its overclock ("" for none) and how many. */
    public static final class Stack {
        public final String id, data;
        public final float qty;
        public Stack(String id, String data, float qty) { this.id = id; this.data = data == null ? "" : data; this.qty = qty; }
    }

    /** host -> joiner: the captain, what is on them, and what the container holds. */
    public static final class ShipMsg {
        public String officerId = "", first = "", last = "", portrait = "", faction = "", personality = "", variant = "";
        public int level, brmTier;
        public float baseConsciousness, baseBrmLimit;
        public long revision;
        public final List<String> skillIds = new ArrayList<String>();
        public final List<Float> skillLevels = new ArrayList<Float>();
        public final List<Anatomy> anatomy = new ArrayList<Anatomy>();
        public final List<Stack> stacks = new ArrayList<Stack>();
    }

    /** joiner -> host: what was changed there. */
    public static final class Step {
        public final int kind;
        public final String limb, bionic, overclock;
        public Step(int kind, String limb, String bionic, String overclock) {
            this.kind = kind;
            this.limb = limb == null ? "" : limb;
            this.bionic = bionic == null ? "" : bionic;
            this.overclock = overclock == null ? "" : overclock;
        }
        @Override public String toString() { return (kind == INSTALL ? "install " : "remove ") + bionic + " on " + limb; }
    }

    public static final class SetMsg {
        public String officerId = "";
        public long revision;
        public final List<Step> steps = new ArrayList<Step>();
    }

    /** host -> joiner: how it went. */
    public static final class ResultMsg {
        public String officerId = "", text = "";
        public boolean ok;
        public ResultMsg() {}
        public ResultMsg(String officerId, boolean ok, String text) { this.officerId = officerId; this.ok = ok; this.text = text; }
    }

    // ---- the codec: every violation of a limit refuses the whole message (IOException) ----

    private static void key(Out o, String s) throws IOException {
        if (s == null) s = "";
        if (s.length() > MAX_KEY) throw new IOException("key too long");
        o.writeUTF(s);
    }

    private static String readKey(In in) throws IOException {
        String s = in.readUTF();
        if (s.length() > MAX_KEY) throw new IOException("key too long");
        return s;
    }

    private static void text(Out o, String s, int max) throws IOException {
        if (s == null) s = "";
        if (s.length() > max) throw new IOException("text too long");
        o.writeUTF(s);
    }

    private static String readText(In in, int max) throws IOException {
        String s = in.readUTF();
        if (s.length() > max) throw new IOException("text too long");
        return s;
    }

    private static float number(In in) throws IOException {
        float f = in.readFloat();
        if (Float.isNaN(f) || Float.isInfinite(f)) throw new IOException("not a number");
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
        if (b.length > MAX_BYTES) throw new IOException("message too large");
        return b;
    }

    public static byte[] encodeAsk(String memberId, String officerId) throws IOException {
        Out o = new Out();
        o.writeByte(VERSION);
        key(o, memberId);
        key(o, officerId);
        return finish(o);
    }

    /** member id, then officer id. */
    public static String[] decodeAsk(byte[] data) throws IOException {
        In in = new In(data);
        version(in);
        return new String[] { readKey(in), readKey(in) };
    }

    /** Throws IOException instead of cutting anything. */
    public static byte[] encodeShip(ShipMsg m) throws IOException {
        if (m.anatomy.size() > MAX_ANATOMY || m.stacks.size() > MAX_STACKS || m.skillIds.size() > MAX_SKILLS) throw new IOException("too many entries");
        Out o = new Out();
        o.writeByte(VERSION);
        key(o, m.officerId);
        text(o, m.first, 64);
        text(o, m.last, 64);
        text(o, m.portrait, 200);
        text(o, m.faction, 40);
        text(o, m.personality, 40);
        text(o, m.variant, 64);
        o.writeInt(m.level);
        o.writeInt(m.brmTier);
        o.writeFloat(m.baseConsciousness);
        o.writeFloat(m.baseBrmLimit);
        o.writeLong(m.revision);
        o.writeVarInt(m.skillIds.size());
        for (int i = 0; i < m.skillIds.size(); i++) { key(o, m.skillIds.get(i)); o.writeFloat(m.skillLevels.size() > i ? m.skillLevels.get(i).floatValue() : 1f); }
        o.writeVarInt(m.anatomy.size());
        for (Anatomy a : m.anatomy) { key(o, a.limb); key(o, a.bionic); key(o, a.overclock); }
        o.writeVarInt(m.stacks.size());
        for (Stack s : m.stacks) { key(o, s.id); text(o, s.data, MAX_DATA); o.writeFloat(s.qty); }
        return finish(o);
    }

    public static ShipMsg decodeShip(byte[] data) throws IOException {
        In in = new In(data);
        version(in);
        ShipMsg m = new ShipMsg();
        m.officerId = readKey(in);
        m.first = readText(in, 64);
        m.last = readText(in, 64);
        m.portrait = readText(in, 200);
        m.faction = readText(in, 40);
        m.personality = readText(in, 40);
        m.variant = readText(in, 64);
        m.level = in.readInt();
        m.brmTier = in.readInt();
        m.baseConsciousness = number(in);
        m.baseBrmLimit = number(in);
        m.revision = in.readLong();
        int n = count(in, MAX_SKILLS);
        for (int i = 0; i < n; i++) { m.skillIds.add(readKey(in)); m.skillLevels.add(Float.valueOf(number(in))); }
        n = count(in, MAX_ANATOMY);
        for (int i = 0; i < n; i++) m.anatomy.add(new Anatomy(readKey(in), readKey(in), readKey(in)));
        n = count(in, MAX_STACKS);
        for (int i = 0; i < n; i++) {
            String id = readKey(in), d = readText(in, MAX_DATA);
            float qty = number(in);
            if (qty <= 0f) throw new IOException("bad quantity");
            m.stacks.add(new Stack(id, d, qty));
        }
        return m;
    }

    public static byte[] encodeSet(SetMsg m) throws IOException {
        if (m.steps.size() > MAX_STEPS) throw new IOException("too many steps");
        Out o = new Out();
        o.writeByte(VERSION);
        key(o, m.officerId);
        o.writeLong(m.revision);
        o.writeVarInt(m.steps.size());
        for (Step s : m.steps) { o.writeByte(s.kind); key(o, s.limb); key(o, s.bionic); key(o, s.overclock); }
        return finish(o);
    }

    public static SetMsg decodeSet(byte[] data) throws IOException {
        In in = new In(data);
        version(in);
        SetMsg m = new SetMsg();
        m.officerId = readKey(in);
        m.revision = in.readLong();
        int n = count(in, MAX_STEPS);
        for (int i = 0; i < n; i++) {
            int kind = in.readByte();
            if (kind != INSTALL && kind != REMOVE) throw new IOException("unknown step kind");
            m.steps.add(new Step(kind, readKey(in), readKey(in), readKey(in)));
        }
        return m;
    }

    public static byte[] encodeResult(ResultMsg m) throws IOException {
        Out o = new Out();
        o.writeByte(VERSION);
        key(o, m.officerId);
        o.writeBoolean(m.ok);
        text(o, m.text, 1000);
        return finish(o);
    }

    public static ResultMsg decodeResult(byte[] data) throws IOException {
        In in = new In(data);
        version(in);
        ResultMsg m = new ResultMsg();
        m.officerId = readKey(in);
        m.ok = in.readBoolean();
        m.text = readText(in, 1000);
        return m;
    }

    // ---- what changed between two looks at the captain ----

    /** The limbs with a bionic on them, by limb id; an empty limb is not listed. */
    static Map<String, Anatomy> occupied(List<Anatomy> anatomy) {
        Map<String, Anatomy> m = new LinkedHashMap<String, Anatomy>();
        for (Anatomy a : anatomy) if (a.occupied() && !a.limb.isEmpty()) m.put(a.limb, a);
        return m;
    }

    /**
     * The change between two looks, as steps: every limb that lost its bionic first, then every limb that got one.
     * A limb that swapped one bionic for another is a removal and an install, in that order. Not capped: a change
     * longer than one message is refused whole by the caller (Closed.full), never cut to its removals.
     */
    public static List<Step> diff(List<Anatomy> before, List<Anatomy> after) {
        Map<String, Anatomy> b = occupied(before), a = occupied(after);
        List<Step> out = new ArrayList<Step>();
        for (Map.Entry<String, Anatomy> e : b.entrySet()) {
            Anatomy now = a.get(e.getKey());
            if (now == null || !now.bionic.equals(e.getValue().bionic) || !now.overclock.equals(e.getValue().overclock))
                out.add(new Step(REMOVE, e.getKey(), e.getValue().bionic, e.getValue().overclock));
        }
        for (Map.Entry<String, Anatomy> e : a.entrySet()) {
            Anatomy was = b.get(e.getKey());
            if (was == null || !was.bionic.equals(e.getValue().bionic) || !was.overclock.equals(e.getValue().overclock))
                out.add(new Step(INSTALL, e.getKey(), e.getValue().bionic, e.getValue().overclock));
        }
        return out;
    }

    /**
     * Why the steps can't all be played on a captain with that anatomy and that container, or null when they can.
     * A joiner's plan is checked whole before its first step: the mod's install does not check that the limb is the
     * captain's (it would take the item and install nothing), its remove fails on an empty limb, and a swap whose new
     * item is gone from the shared container would leave the limb empty.
     */
    static String planProblem(List<Anatomy> anatomy, List<Step> steps, List<Stack> container) {
        Map<String, Anatomy> limbs = new LinkedHashMap<String, Anatomy>();
        for (Anatomy a : anatomy) if (!a.limb.isEmpty()) limbs.put(a.limb, a);
        Map<String, Float> items = new HashMap<String, Float>();
        for (Stack s : container) more(items, s.id + "|" + s.data, s.qty);
        for (int pass = 0; pass < 2; pass++) {
            for (Step s : steps) {
                if ((s.kind == REMOVE ? 0 : 1) != pass) continue;
                Anatomy on = limbs.get(s.limb);
                if (on == null) return s.bionic + " on " + s.limb + ": the captain has no such limb";
                if (s.kind == REMOVE) {
                    if (!on.occupied() || !on.bionic.equals(s.bionic)) return s.bionic + " on " + s.limb + ": not on that limb";
                    more(items, on.bionic + "|" + on.overclock, 1f); // the mod puts it back in the container, overclock and all
                    limbs.put(s.limb, new Anatomy(s.limb, "", ""));
                } else {
                    if (on.occupied()) return s.bionic + " on " + s.limb + ": the limb isn't free";
                    String key = s.bionic + "|" + s.overclock;
                    Float have = items.get(key);
                    if (have == null || have.floatValue() < 1f)
                        return s.bionic + (s.overclock.isEmpty() ? "" : " (" + s.overclock + ")") + ": not in the host's bionic container";
                    items.put(key, Float.valueOf(have.floatValue() - 1f));
                    limbs.put(s.limb, new Anatomy(s.limb, s.bionic, s.overclock));
                }
            }
        }
        return null;
    }

    private static void more(Map<String, Float> items, String key, float qty) {
        Float had = items.get(key);
        items.put(key, Float.valueOf((had == null ? 0f : had.floatValue()) + qty));
    }

    /**
     * What a host writes on a battle's captain copy (see capture): version, tier, variant, the effective
     * consciousness, then the bionics on limbs as a field of its own. Plain text, so it needs no game.
     */
    static String battleText(int tier, String variant, float consciousness, List<Anatomy> anatomy) {
        StringBuilder limbs = new StringBuilder();
        for (Anatomy a : anatomy) {
            if (!a.occupied()) continue;
            if (limbs.length() > 0) limbs.append(';');
            limbs.append(a); // limb:bionic, and :overclock when there is one
        }
        return "1|" + tier + "|" + (variant == null ? "" : variant) + "|" + consciousness + "|" + limbs;
    }

    /** What {@link #battleText} wrote, or null when the text is not this version's: tier, variant, consciousness, anatomy. */
    static Object[] battleBionics(String text) {
        if (text == null) return null;
        String[] parts = text.split("\\|", 5);
        if (parts.length < 5 || !"1".equals(parts[0])) return null;
        int tier;
        float consciousness;
        try {
            tier = Integer.parseInt(parts[1]);
            consciousness = Float.parseFloat(parts[3]);
        } catch (NumberFormatException e) {
            return null;
        }
        List<Anatomy> anatomy = new ArrayList<Anatomy>();
        for (String entry : parts[4].split(";")) {
            String[] ids = entry.split(":", 3);
            if (ids.length < 2 || ids[0].isEmpty() || ids[1].isEmpty()) continue;
            anatomy.add(new Anatomy(ids[0], ids[1], ids.length > 2 ? ids[2] : ""));
        }
        return new Object[] { Integer.valueOf(tier), parts[2], Float.valueOf(consciousness), anatomy };
    }

    /** What the host compares before applying: a captain changed underneath the joiner is refused, not half-applied. */
    public static long revision(List<Anatomy> anatomy, int brmTier) {
        long h = 1469598103934665603L;
        for (Anatomy a : anatomy) for (char c : a.toString().toCharArray()) h = (h ^ c) * 1099511628211L;
        for (char c : ("#" + brmTier).toCharArray()) h = (h ^ c) * 1099511628211L;
        return h;
    }

    /** The bionics installed on the person, in the mod's own order; empty when they have no bionic data. */
    static List<Anatomy> readAnatomy(PersonAPI person) throws Throwable {
        List<Anatomy> out = new ArrayList<Anatomy>();
        for (Object aug : (List<?>) anatomyOf.invoke(person)) {
            Object limb = limbField.invoke(aug), bionic = bionicField.invoke(aug), overclock = overclockField.invoke(aug);
            if (limb == null) continue;
            String limbId = String.valueOf(limbIdField.invoke(limb));
            String bionicId = bionic == null ? "" : String.valueOf(bionicIdField.invoke(bionic));
            String overclockId = overclock == null ? "" : String.valueOf(idField.invoke(overclock));
            out.add(new Anatomy(limbId, bionicId, overclockId));
        }
        return out;
    }

    // ---- the handles on Bionic Alteration ----

    private static final String P = "pigeonpun.bionicalteration.";
    private static Class<?> bionicC;
    private static MethodHandle getData, saveData, anatomyOf, hasData, ensureOfficers, setUpSkill, updateStats,
            install, remove, getLimb, getBionic, getOverclock, compress, globalData, refreshListPerson,
            uiDefault, uiInit, uiWidth, uiHeight, otherFleets,
            dataNew, augNew, anatomyField, tierGet, tierSet, variantGet, variantSet,
            limbField, bionicField, overclockField, limbIdField, bionicIdField, idField, consCostField,
            consKey, consSource, brmLimitKey, brmLimitSource, brmCurrentKey, brmCurrentSource, containerKey;
    private static boolean failed, stageWarned;

    /** Whether Bionic Alteration is loaded in this game. */
    public static boolean enabled() {
        try {
            return Global.getSettings().getModManager().isModEnabled(MOD_ID);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Whether Bionic Alteration is loaded and laid out as expected (looked into once). */
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

    // The game forbids its mods reflection: Bionic Alteration is reached through method handles, found once.
    private static synchronized void find() throws Throwable {
        if (failed) throw new IllegalStateException("Bionic Alteration is not laid out as Wingman expects");
        if (uiDefault != null) return;
        try {
            MethodHandles.Lookup l = MethodHandles.publicLookup();
            Class<?> officer = cls("ba_officermanager"), bionic = cls("bionic.ba_bionicmanager"), limb = cls("ba_limbmanager");
            Class<?> overclock = cls("overclock.ba_overclockmanager"), inv = cls("inventory.ba_inventoryhandler"), vars = cls("ba_variablemanager");
            Class<?> ui = cls("ui.bionic.ba_uiplugin"), templugin = cls("bionic.ba_bionicitemplugin"), limbc = cls("ba_limbmanager$ba_limb");
            Class<?> oc = cls("overclock.ba_overclock"), data = cls("ba_officermanager$ba_personmemorydata"), aug = cls("ba_officermanager$ba_bionicAugmentedData");
            getData = l.findStatic(officer, "getPersonMemoryData", MethodType.methodType(data, PersonAPI.class));
            saveData = l.findStatic(officer, "savePersonMemoryData", MethodType.methodType(void.class, data, PersonAPI.class));
            anatomyOf = l.findStatic(officer, "getBionicAnatomyList", MethodType.methodType(List.class, PersonAPI.class));
            hasData = l.findStatic(officer, "checkIfPersonHasBionicMemoryData", MethodType.methodType(boolean.class, PersonAPI.class));
            ensureOfficers = l.findStatic(officer, "setUpListOfficers", MethodType.methodType(void.class, List.class));
            setUpSkill = l.findStatic(officer, "setUpSkill", MethodType.methodType(void.class, PersonAPI.class));
            updateStats = l.findStatic(officer, "updatePersonStatsOnInteract", MethodType.methodType(void.class, templugin, limbc, PersonAPI.class, boolean.class));
            install = l.findStatic(officer, "installBionic", MethodType.methodType(boolean.class, templugin, limbc, PersonAPI.class, boolean.class));
            remove = l.findStatic(officer, "removeBionic", MethodType.methodType(boolean.class, templugin, limbc, PersonAPI.class));
            refreshListPerson = l.findStatic(officer, "refreshListPerson", MethodType.methodType(List.class, List.class));
            getLimb = l.findStatic(limb, "getLimb", MethodType.methodType(limbc, String.class));
            getBionic = l.findStatic(bionic, "getBionic", MethodType.methodType(templugin, String.class));
            getOverclock = l.findStatic(overclock, "getOverclock", MethodType.methodType(oc, String.class));
            compress = l.findStatic(inv, "compressAllBionics", MethodType.methodType(void.class));
            globalData = l.findStatic(inv, "getGlobalData", MethodType.methodType(CargoAPI.class));
            uiDefault = l.findStatic(ui, "createDefault", MethodType.methodType(ui));
            uiWidth = l.findStaticGetter(ui, "MAIN_CONTAINER_WIDTH", float.class);
            uiHeight = l.findStaticGetter(ui, "MAIN_CONTAINER_HEIGHT", float.class);
            otherFleets = l.findStaticSetter(ui, "isDisplayingOtherFleets", boolean.class);
            uiInit = l.findVirtual(ui, "init", MethodType.methodType(void.class, CustomPanelAPI.class,
                    CustomVisualDialogDelegate.DialogCallbacks.class, InteractionDialogAPI.class, String.class, List.class));
            dataNew = l.findConstructor(data, MethodType.methodType(void.class, int.class));
            augNew = l.findConstructor(aug, MethodType.methodType(void.class, limbc, templugin, oc));
            anatomyField = l.findSetter(data, "anatomy", List.class);
            tierGet = l.findGetter(data, "BRMTier", int.class);
            tierSet = l.findSetter(data, "BRMTier", int.class);
            variantGet = l.findGetter(data, "variant", String.class);
            variantSet = l.findSetter(data, "variant", String.class);
            limbField = l.findGetter(aug, "limb", limbc);
            bionicField = l.findGetter(aug, "bionicInstalled", templugin);
            overclockField = l.findGetter(aug, "appliedOverclock", oc);
            limbIdField = l.findGetter(limbc, "limbId", String.class);
            bionicIdField = l.findGetter(templugin, "bionicId", String.class);
            idField = l.findGetter(oc, "id", String.class);
            consCostField = l.findGetter(templugin, "consciousnessCost", float.class);
            consKey = l.findStaticGetter(vars, "BA_CONSCIOUSNESS_STATS_KEY", String.class);
            consSource = l.findStaticGetter(vars, "BA_CONSCIOUSNESS_SOURCE_KEY", String.class);
            brmLimitKey = l.findStaticGetter(vars, "BA_BRM_LIMIT_STATS_KEY", String.class);
            brmLimitSource = l.findStaticGetter(vars, "BA_BRM_LIMIT_SOURCE_KEY", String.class);
            brmCurrentKey = l.findStaticGetter(vars, "BA_BRM_CURRENT_STATS_KEY", String.class);
            brmCurrentSource = l.findStaticGetter(vars, "BA_BRM_CURRENT_SOURCE_KEY", String.class);
            containerKey = l.findStaticGetter(vars, "BA_BIONIC_CONTAINER_PERSISTENT_KEY", String.class);
            bionicC = templugin;
        } catch (Throwable t) {
            failed = true;
            Global.getLogger(Bionics.class).warn("Wingman: Bionic Alteration is loaded but not as expected; its workshop is left out", t);
            throw t;
        }
    }

    // ---- the host: the captain's message, and the changes played back ----

    /** What the host sends for a joiner's ask. Throws IOException when it would not fit a message. Game thread. */
    public static byte[] buildShip(PersonAPI person) throws Throwable {
        find();
        if (!((Boolean) hasData.invoke(person)).booleanValue()) ensureOfficers.invoke(Collections.singletonList(person));
        compress.invoke(); // as the mod's own workshop does: bionic items the captain's holder carries go into its container
        ShipMsg m = new ShipMsg();
        m.officerId = person.getId() == null ? "" : person.getId();
        try {
            if (person.getName() != null) {
                m.first = person.getName().getFirst() == null ? "" : person.getName().getFirst();
                m.last = person.getName().getLast() == null ? "" : person.getName().getLast();
            }
            m.portrait = person.getPortraitSprite() == null ? "" : person.getPortraitSprite();
            m.faction = person.getFaction() == null || person.getFaction().getId() == null ? "" : person.getFaction().getId();
            m.personality = person.getPersonalityAPI() == null ? "" : person.getPersonalityAPI().getId();
            m.level = person.getStats().getLevel();
            for (MutableCharacterStatsAPI.SkillLevelAPI sk : person.getStats().getSkillsCopy()) {
                if (sk.getSkill() == null || sk.getLevel() <= 0f || m.skillIds.size() >= MAX_SKILLS) continue;
                m.skillIds.add(sk.getSkill().getId());
                m.skillLevels.add(Float.valueOf(sk.getLevel()));
            }
        } catch (Throwable t) { /* what was read so far is enough */ }
        Object data = getData.invoke(person);
        if (data == null) throw new IllegalStateException("no bionic data for " + m.officerId);
        m.brmTier = ((Integer) tierGet.invoke(data)).intValue();
        Object variant = variantGet.invoke(data);
        m.variant = variant == null ? "" : String.valueOf(variant);
        m.anatomy.addAll(readAnatomy(person));
        m.revision = revision(m.anatomy, m.brmTier);
        // The base values the mod set once, so a remove or install on the joiner's side adds up the same here. The
        // mod subtracts each installed bionic's cost from the consciousness (updateConsciousness), so adding those
        // costs back to what it shows now gives the base. BRM current has no base to send: it starts at 0.
        float consciousness = stat(person, key(consKey));
        for (Anatomy a : m.anatomy) {
            if (!a.occupied()) continue;
            Object bionic = getBionic.invoke(a.bionic);
            if (bionic == null) continue;
            consciousness += ((Float) consCostField.invoke(bionic)).floatValue();
        }
        m.baseConsciousness = consciousness;
        m.baseBrmLimit = stat(person, key(brmLimitKey));
        m.stacks.addAll(readContainer(MAX_STACKS));
        return encodeShip(m);
    }

    /** How a replay went: the steps done, and why the next one was not. */
    public static final class Outcome {
        public int applied;
        public String failure;
    }

    /**
     * Plays a joiner's steps on the host's captain through the mod's own methods (they check the limits and charge
     * or return the item). The whole plan is checked first (planProblem), so a step the captain or the container can't
     * take refuses it before anything changes. Removals go first, so a limb that changed bionic is empty before the
     * new one goes on. Game thread.
     */
    public static Outcome replay(PersonAPI person, long revision, List<Step> steps) throws Throwable {
        find();
        Outcome out = new Outcome();
        compress.invoke();
        if (!((Boolean) hasData.invoke(person)).booleanValue()) ensureOfficers.invoke(Collections.singletonList(person));
        Object data = getData.invoke(person);
        if (data == null) { out.failure = "the captain has no bionic data"; return out; }
        List<Anatomy> now = readAnatomy(person);
        if (revision != revision(now, ((Integer) tierGet.invoke(data)).intValue())) {
            out.failure = "the captain's bionics changed in the meantime";
            return out;
        }
        String why = planProblem(now, steps, readContainer(Integer.MAX_VALUE));
        if (why != null) { out.failure = why; return out; }
        for (int pass = 0; pass < 2; pass++) {
            for (Step s : steps) {
                if ((s.kind == REMOVE ? 0 : 1) != pass) continue;
                why = s.kind == REMOVE ? removeOne(person, s) : installOne(person, s);
                if (why != null) { out.failure = why; return out; }
                out.applied++;
            }
        }
        if (out.applied > 0) setUpSkill.invoke(person); // the mod grants its ship-effects skill only when it first sets a captain up
        return out;
    }

    private static String removeOne(PersonAPI person, Step s) throws Throwable {
        Object bionic = getBionic.invoke(s.bionic), limb = getLimb.invoke(s.limb);
        if (bionic == null || limb == null) return s.bionic + " on " + s.limb + ": not known to this game";
        if (!((Boolean) remove.invoke(bionic, limb, person)).booleanValue()) return s.bionic + " on " + s.limb + ": can't be taken off";
        return null;
    }

    private static String installOne(PersonAPI person, Step s) throws Throwable {
        Object limb = getLimb.invoke(s.limb);
        if (limb == null) return s.bionic + " on " + s.limb + ": not known to this game";
        Object item = null;
        CargoAPI cargo = (CargoAPI) globalData.invoke();
        if (cargo != null) for (CargoStackAPI stack : cargo.getStacksCopy()) {
            try {
                if (!stack.isSpecialStack()) continue;
                SpecialItemData d = stack.getSpecialDataIfSpecial();
                if (d == null || d.getId() == null || !s.bionic.equals(d.getId())) continue;
                String overclock = d.getData() == null ? "" : d.getData();
                if (!overclock.equals(s.overclock)) continue;
                Object plugin = stack.getPlugin();
                if (bionicC.isInstance(plugin)) { item = plugin; break; }
            } catch (Throwable t) { /* that stack is not one of the mod's */ }
        }
        if (item == null && s.overclock.isEmpty()) item = getBionic.invoke(s.bionic); // the plain item: the mod removes it by id
        if (item == null) return s.bionic + (s.overclock.isEmpty() ? "" : " (" + s.overclock + ")") + ": not in the host's bionic container";
        if (!((Boolean) install.invoke(item, limb, person, Boolean.TRUE)).booleanValue()) return s.bionic + " on " + s.limb + ": can't be installed";
        return null;
    }

    /** The mod's bionic items in its container, at most max stacks. */
    private static List<Stack> readContainer(int max) throws Throwable {
        List<Stack> out = new ArrayList<Stack>();
        CargoAPI cargo = (CargoAPI) globalData.invoke();
        if (cargo == null) return out;
        for (CargoStackAPI stack : cargo.getStacksCopy()) {
            if (out.size() >= max) break;
            try {
                if (!stack.isSpecialStack()) continue;
                SpecialItemData d = stack.getSpecialDataIfSpecial();
                if (d == null || d.getId() == null || !bionicC.isInstance(stack.getPlugin())) continue;
                String data = d.getData() == null ? "" : d.getData();
                if (data.length() > MAX_DATA || d.getId().length() > MAX_KEY || stack.getSize() <= 0f) continue;
                out.add(new Stack(d.getId(), data, stack.getSize()));
            } catch (Throwable t) { /* that stack is left out */ }
        }
        return out;
    }

    /** The mod's name for a stat, read once from its own constant. */
    private static String key(MethodHandle handle) throws Throwable {
        return String.valueOf(handle.invoke());
    }

    private static float stat(PersonAPI person, String key) {
        try {
            return person.getStats().getDynamic().getMod(key).computeEffective(0f);
        } catch (Throwable t) {
            return 0f;
        }
    }

    private static void base(PersonAPI person, String key, String source, float value) {
        try {
            person.getStats().getDynamic().getMod(key).modifyFlat(source, value);
        } catch (Throwable t) { /* no such stat */ }
    }

    // ---- the joiner: a mirror of the host's captain, and the mod's workshop on it ----

    /** A stand-in player fleet, an empty system and the mod's container cargo; all taken away by remove(). */
    static final class Mirror {
        CampaignFleetAPI fleet;
        StarSystemAPI system;
        PersonAPI person;
        boolean placed, removed, hadContainer;
        Object containerBefore;

        static Mirror install(ShipMsg m) throws Throwable {
            SectorAPI sector = Global.getSector();
            if (sector == null || sector.getPlayerFleet() != null) throw new IllegalStateException("a campaign is loaded");
            Mirror mi = new Mirror();
            try {
                mi.fleet = Global.getFactory().createEmptyFleet("player", "Wingman Bionic", true);
                sector.setPlayerFleet(mi.fleet);
                mi.placed = true;
                mi.system = sector.createStarSystem("Wingman Bionic");
                mi.system.addEntity(mi.fleet);
                mi.fleet.setLocation(0f, 0f);
                Map<String, Object> data = sector.getPersistentData();
                String key = String.valueOf(containerKey.invoke());
                mi.hadContainer = data.containsKey(key);
                mi.containerBefore = mi.hadContainer ? data.get(key) : null;
                CargoAPI cargo = Global.getFactory().createCargo(true);
                for (Stack s : m.stacks) {
                    try { cargo.addSpecial(new SpecialItemData(s.id, s.data.isEmpty() ? null : s.data), s.qty); } catch (Throwable t) { /* an item this game lacks */ }
                }
                data.put(key, cargo);
                mi.person = person(m);
            } catch (Throwable t) {
                mi.remove();
                throw t;
            }
            return mi;
        }

        private static PersonAPI person(ShipMsg m) throws Throwable {
            PersonAPI p = Global.getFactory().createPerson();
            p.setId(m.officerId);
            p.setName(new FullName(m.first, m.last, FullName.Gender.ANY));
            try { if (!m.portrait.isEmpty()) p.setPortraitSprite(m.portrait); } catch (Throwable t) { /* default face */ }
            try {
                SectorAPI sector = Global.getSector();
                if (!m.faction.isEmpty() && sector != null && sector.getFaction(m.faction) != null) p.setFaction(m.faction);
            } catch (Throwable t) { /* default faction */ }
            try { if (!m.personality.isEmpty()) p.setPersonality(m.personality); } catch (Throwable t) { /* default */ }
            try { p.getStats().setLevel(Math.max(1, m.level)); } catch (Throwable t) { /* level 1 */ }
            for (int i = 0; i < m.skillIds.size(); i++) {
                try {
                    if (Global.getSettings().getSkillSpec(m.skillIds.get(i)) == null) continue;
                    p.getStats().setSkillLevel(m.skillIds.get(i), m.skillLevels.get(i).floatValue());
                } catch (Throwable t) { /* a skill this game lacks */ }
            }
            Object data = dataNew.invoke(m.brmTier);
            List<Object> anatomy = new ArrayList<Object>();
            for (Anatomy a : m.anatomy) {
                Object limb = getLimb.invoke(a.limb);
                if (limb == null) continue;
                Object bionic = a.bionic.isEmpty() ? null : getBionic.invoke(a.bionic);
                Object overclock = a.overclock.isEmpty() ? null : getOverclock.invoke(a.overclock);
                anatomy.add(augNew.invoke(limb, bionic, overclock));
            }
            anatomyField.invoke(data, anatomy);
            variantSet.invoke(data, m.variant);
            saveData.invoke(data, p);
            base(p, String.valueOf(consKey.invoke()), String.valueOf(consSource.invoke()), m.baseConsciousness);
            base(p, String.valueOf(brmLimitKey.invoke()), String.valueOf(brmLimitSource.invoke()), m.baseBrmLimit);
            base(p, String.valueOf(brmCurrentKey.invoke()), String.valueOf(brmCurrentSource.invoke()), 0f);
            for (Anatomy a : m.anatomy) { // what each installed bionic costs, through the mod's own arithmetic
                if (!a.occupied()) continue;
                Object bionic = getBionic.invoke(a.bionic), limb = getLimb.invoke(a.limb);
                if (bionic != null && limb != null) updateStats.invoke(bionic, limb, p, Boolean.TRUE);
            }
            return p;
        }

        void remove() {
            if (removed) return;
            removed = true;
            try { refreshListPerson.invoke(Collections.emptyList()); } catch (Throwable t) { /* nothing listed */ }
            try { otherFleets.invoke(Boolean.FALSE); } catch (Throwable t) { /* flag gone with the game */ }
            SectorAPI sector = Global.getSector();
            if (sector == null) return;
            try {
                Map<String, Object> data = sector.getPersistentData();
                String key = String.valueOf(containerKey.invoke());
                if (hadContainer) data.put(key, containerBefore); else data.remove(key);
            } catch (Throwable t) { /* gone with the sector */ }
            try { if (placed && sector.getPlayerFleet() == fleet) sector.setPlayerFleet(null); } catch (Throwable t) { /* gone with the sector */ }
            try { if (system != null) sector.removeStarSystem(system); } catch (Throwable t) { /* gone with the sector */ }
        }
    }

    /** What the workshop leaves behind when it closes: whose captain, what changed, and whether it fits one message. */
    public static final class Closed {
        public final String officerId;
        public final List<Step> steps;
        public final long revision;
        /** More steps than one message takes: nothing may be sent (a cut plan would be only its removals). */
        public final boolean full;
        Closed(String officerId, List<Step> steps, long revision) {
            this.officerId = officerId; this.steps = steps; this.revision = revision; this.full = steps.size() > MAX_STEPS;
        }
    }

    private static final CustomVisualDialogDelegate.DialogCallbacks CALLBACKS = new CustomVisualDialogDelegate.DialogCallbacks() {
        public void dismissDialog() { /* Escape closes the menu from Wingman's side (ExoticaRefit.close) */ }
        public FaderUtil getPanelFader() { return null; }
    };

    // The one workshop that can be open (the joiner has one lobby).
    private static Mirror mirror;
    private static Object menuPlugin, root;
    private static List<Anatomy> before;
    private static String menuOfficer = "";
    private static long menuRevision;
    private static float menuX, menuY, menuW, menuH;

    public static synchronized boolean active() { return root != null; }

    /** Where the menu is on screen: x, y (from the bottom left), width, height. */
    public static synchronized float[] rect() { return new float[] { menuX, menuY, menuW, menuH }; }

    /** Opens the mod's workshop on the host's captain. Returns null when it is open, else why not. */
    public static synchronized String open(ShipMsg m) {
        if (root != null) return "the bionics menu is already open";
        try {
            if (!enabled()) return "Bionic Alteration is not loaded";
            find();
            mirror = Mirror.install(m);
            menuOfficer = m.officerId;
            menuRevision = m.revision;
            menuPlugin = uiDefault.invoke();
            float w = ((Float) uiWidth.invoke()).floatValue(), h = ((Float) uiHeight.invoke()).floatValue();
            float sw = Global.getSettings().getScreenWidth(), sh = Global.getSettings().getScreenHeight();
            menuW = Math.min(w, sw - 20f);
            menuH = Math.min(h, sh - 20f);
            menuX = (sw - menuW) / 2f;
            menuY = (sh - menuH) / 2f;
            CustomPanelAPI panel = Global.getSettings().createCustom(menuW, menuH, (CustomUIPanelPlugin) menuPlugin);
            root = panel;
            panel.getPosition().setLocation(menuX, menuY);
            List<PersonAPI> people = new ArrayList<PersonAPI>();
            people.add(mirror.person);
            // Straight to the workshop page: the person list is for the fleet, and this menu is one captain's.
            uiInit.invoke(menuPlugin, panel, CALLBACKS, new DialogStub(null), WORKSHOP, people);
            // The mod hides the "Workshop" button on a person list it did not build from a fleet (the list here is
            // one captain): with the flag down, the page's Back button leads to an overview that can return.
            otherFleets.invoke(Boolean.FALSE);
            before = readAnatomy(mirror.person);
            return null;
        } catch (Throwable t) {
            Global.getLogger(Bionics.class).warn("Wingman: Bionic Alteration's workshop could not be opened", t);
            teardown();
            return "Bionic Alteration's workshop could not be opened (see log)";
        }
    }

    /** Every frame while open. False if the menu failed and has been taken down. */
    public static synchronized boolean frame(float dt) {
        if (root == null) return false;
        try {
            ((CustomPanelAPI) root).advance(dt);
            return true;
        } catch (Throwable t) {
            Global.getLogger(Bionics.class).warn("Wingman: Bionic Alteration's workshop failed; closed", t);
            teardown();
            return false;
        }
    }

    /** The mouse and keys, for the menu; the events are used up so what is under it doesn't see them. */
    public static synchronized void input(List<InputEventAPI> events) {
        if (root == null || events == null) return;
        try {
            ((CustomPanelAPI) root).processInput(events);
        } catch (Throwable t) {
            Global.getLogger(Bionics.class).warn("Wingman: Bionic Alteration's workshop failed; closed", t);
            teardown();
        }
        for (InputEventAPI e : events) {
            try { e.consume(); } catch (Throwable t) { /* already used */ }
        }
    }

    public static synchronized void render() {
        if (root == null) return;
        try {
            ((CustomPanelAPI) root).render(1f);
        } catch (Throwable t) {
            Global.getLogger(Bionics.class).warn("Wingman: Bionic Alteration's workshop failed; closed", t);
            teardown();
        }
    }

    /** Closes the menu and takes the mirror down; what changed is returned (null if it was not open). */
    public static synchronized Closed close() {
        if (root == null) return null;
        List<Step> steps = new ArrayList<Step>();
        try {
            steps.addAll(diff(before, readAnatomy(mirror.person)));
        } catch (Throwable t) { /* what was seen before then */ }
        Closed c = new Closed(menuOfficer, steps, menuRevision);
        teardown();
        return c;
    }

    private static void teardown() {
        root = null;
        menuPlugin = null;
        before = null;
        if (mirror != null) mirror.remove();
        mirror = null;
    }

    // ---- battle copies: the joiner's ship gets the same bionics as the host's ----

    /**
     * Host, for a ship's captain being described to the joiners: writes the bionics as plain text into the copy
     * (wingman.net.Person.memory), which the joiner's client reads in {@link #stage}.
     */
    public static void capture(PersonAPI captain, wingman.net.Person copy) {
        if (copy == null || captain == null || !available()) return;
        try {
            List<Anatomy> anatomy = readAnatomy(captain);
            boolean any = false;
            for (Anatomy a : anatomy) if (a.occupied()) any = true;
            if (!any) return;
            Object data = getData.invoke(captain);
            int tier = data == null ? 0 : ((Integer) tierGet.invoke(data)).intValue();
            Object variant = data == null ? null : variantGet.invoke(data);
            copy.memory.put(BATTLE_KEY, battleText(tier, variant == null ? "" : String.valueOf(variant),
                    stat(captain, String.valueOf(consKey.invoke())), anatomy));
        } catch (Throwable t) {
            if (!stageWarned) {
                stageWarned = true;
                Global.getLogger(Bionics.class).warn("Wingman: a captain's bionics were not put on the ship description", t);
            }
        }
    }

    /** Joiner, on a captain built for a battle: gives them the host's bionics, so the mod's effects apply here too. */
    public static void stage(PersonAPI captain) {
        if (captain == null || !available()) return;
        try {
            Object raw = captain.getMemoryWithoutUpdate().get(BATTLE_KEY);
            if (!(raw instanceof String)) return;
            Object[] b = battleBionics((String) raw);
            if (b == null) return; // not this version's text: no bionics, rather than half of them
            Object data = dataNew.invoke(((Integer) b[0]).intValue());
            variantSet.invoke(data, b[1]);
            List<Object> anatomy = new ArrayList<Object>();
            for (Object o : (List<?>) b[3]) { // unknown limb or bionic ids are skipped, as before
                Anatomy a = (Anatomy) o;
                Object limb = getLimb.invoke(a.limb);
                Object bionic = a.bionic.isEmpty() ? null : getBionic.invoke(a.bionic);
                if (limb == null || bionic == null) continue;
                Object overclock = a.overclock.isEmpty() ? null : getOverclock.invoke(a.overclock);
                anatomy.add(augNew.invoke(limb, bionic, overclock));
            }
            anatomyField.invoke(data, anatomy);
            saveData.invoke(data, captain);
            base(captain, String.valueOf(consKey.invoke()), String.valueOf(consSource.invoke()), ((Float) b[2]).floatValue());
        } catch (Throwable t) {
            if (!stageWarned) {
                stageWarned = true;
                Global.getLogger(Bionics.class).warn("Wingman: a joiner's captain was not given the host's bionics", t);
            }
        }
    }
}
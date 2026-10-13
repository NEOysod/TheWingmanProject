package wingman;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;

import wingman.net.Msg;
import wingman.net.Plain;

/**
 * Mods' LunaLib settings, the host's for the length of a battle (Msg.LUNA, host -> joiner).
 *
 * LunaLib keeps every mod's settings on each PC, and mods read them in battle: whether an effect applies at all,
 * and sometimes its numbers (Special Hullmod Upgrades' "custom stats", armaa's repair rules). A joiner's copies
 * of the host's ships should follow the host's choices, so the joiner takes the host's values while the battle
 * runs and gets its own back when it ends. Nothing is written to disk.
 *
 * Keys are the exception: each player keeps their own bindings. A joiner tells the host theirs (Msg.LUNA_KEYS),
 * so that when a script on the host asks for the host's key of a setting, the joiner's key for the same setting
 * counts (see AgentLink.keyDown).
 *
 * LunaLib is another mod: it is reached by name, and without it (or with a version laid out differently) this
 * does nothing.
 */
final class LunaCopy {
    private LunaCopy() {}

    private static final String LOADER = "lunalib.backend.ui.settings.LunaSettingsLoader";
    private static final String SETTINGS = "lunalib.lunaSettings.LunaSettings";
    /** Settings that are not values: labels and headings in LunaLib's menu. */
    private static final String[] NOT_VALUES = { "Header", "Text" };
    static final int MAX_KEYS = wingman.net.LunaKeys.MAX_KEYS;

    /** One setting LunaLib knows of: which mod, which field, what type. */
    private static final class Field {
        final String mod, id, type;
        Field(String mod, String id, String type) { this.mod = mod; this.id = id; this.type = type; }
    }

    private static Class<?> loaderClass() throws ClassNotFoundException {
        return Class.forName(LOADER, true, Global.getSettings().getScriptClassLoader());
    }

    // The game forbids its mods java.lang.reflect: LunaLib is reached through method handles, found once.
    private static MethodHandle instanceH, hasLoadedH, loadH, settingsH, dataH, reportH;
    private static final Map<Class<?>, MethodHandle[]> dataGetters = new HashMap<Class<?>, MethodHandle[]>();

    private static synchronized void find() throws Throwable {
        if (settingsH != null) return;
        Class<?> c = loaderClass();
        MethodHandles.Lookup l = MethodHandles.publicLookup();
        instanceH = l.findStaticGetter(c, "INSTANCE", c);
        hasLoadedH = l.findVirtual(c, "getHasLoaded", MethodType.methodType(boolean.class));
        loadH = l.findVirtual(c, "load", MethodType.methodType(void.class));
        dataH = l.findStatic(c, "getSettingsData", MethodType.methodType(List.class));
        settingsH = l.findStatic(c, "getSettings", MethodType.methodType(Map.class));
    }

    /** LunaLib's settings: mod id -> its JSONObject of values. Loaded first if LunaLib hasn't yet. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> settings() throws Throwable {
        find();
        Object instance = instanceH.invoke();
        if (!((Boolean) hasLoadedH.invoke(instance))) loadH.invoke(instance);
        return (Map<String, Object>) settingsH.invoke();
    }

    private static List<Field> fields() throws Throwable {
        find();
        Object list = dataH.invoke();
        List<Field> out = new ArrayList<Field>();
        if (!(list instanceof List)) return out;
        for (Object d : (List<?>) list) {
            try {
                MethodHandle[] g = dataGetters.get(d.getClass());
                if (g == null) {
                    MethodHandles.Lookup l = MethodHandles.publicLookup();
                    MethodType str = MethodType.methodType(String.class);
                    g = new MethodHandle[] { l.findVirtual(d.getClass(), "getModID", str), l.findVirtual(d.getClass(), "getFieldID", str),
                            l.findVirtual(d.getClass(), "getFieldType", str) };
                    dataGetters.put(d.getClass(), g);
                }
                String mod = (String) g[0].invoke(d), id = (String) g[1].invoke(d), type = (String) g[2].invoke(d);
                if (mod != null && id != null) out.add(new Field(mod, id, type == null ? "" : type));
            } catch (Throwable t) { /* that one is left out */ }
        }
        return out;
    }

    private static Object opt(Object json, String key) {
        if (!(json instanceof org.json.JSONObject)) return null;
        Object v = ((org.json.JSONObject) json).opt(key);
        return v == null || "null".equals(String.valueOf(v)) && !(v instanceof String) ? null : v;
    }

    private static void put(Object json, String key, Object value) throws Exception {
        if (!(json instanceof org.json.JSONObject)) return;
        if (value == null) ((org.json.JSONObject) json).remove(key);
        else ((org.json.JSONObject) json).put(key, value);
    }

    /**
     * The key a setting stands for, or 0 if it isn't one: LunaLib's own key type (a key code), or text that
     * names a key in a field whose name says it is one ("...Key", "...Hotkey"), as Special Hullmod Upgrades and
     * armaa keep theirs.
     */
    static int keyOf(String type, String id, Object value) {
        if (value == null) return 0;
        try {
            if ("Keycode".equals(type)) return value instanceof Number ? ((Number) value).intValue() : Integer.parseInt(String.valueOf(value).trim());
            if (!"String".equals(type) || id.toLowerCase().indexOf("key") < 0) return 0;
            String name = String.valueOf(value).trim().toUpperCase();
            if (name.isEmpty() || name.length() > 20) return 0;
            int k = org.lwjgl.input.Keyboard.getKeyIndex(name);
            if (k <= 0 && name.contains(" ")) k = org.lwjgl.input.Keyboard.getKeyIndex(name.replace(' ', '_'));
            return k > 0 && k < 256 ? k : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean isValue(String type) {
        for (String t : NOT_VALUES) if (t.equals(type)) return false;
        return true;
    }

    // ---- host ----

    /** The host's settings for joiners (mod -> field -> value), keys left out; null without LunaLib. */
    static byte[] encodeHost() {
        try {
            Map<String, Object> all = settings();
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            int n = 0;
            for (Field f : fields()) {
                if (!isValue(f.type) || "Keycode".equals(f.type)) continue;
                Object json = all.get(f.mod);
                if (json == null) continue;
                Object v = opt(json, f.id);
                if (!(v instanceof Boolean || v instanceof Number || v instanceof String)) continue;
                if (keyOf(f.type, f.id, v) > 0) continue; // a player's own key: theirs to keep
                if (v instanceof String && ((String) v).length() > 1000) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> mod = (Map<String, Object>) out.get(f.mod);
                if (mod == null) { mod = new LinkedHashMap<String, Object>(); out.put(f.mod, mod); }
                if (mod.size() >= Plain.MAX_ITEMS || n >= Plain.MAX_NODES / 2) continue;
                mod.put(f.id, v instanceof Number && !(v instanceof Integer) ? (Object) Double.valueOf(((Number) v).doubleValue()) : v);
                n++;
            }
            if (out.isEmpty()) return null;
            Msg.W w = new Msg.W();
            Plain.writeMap(w.out, out);
            Global.getLogger(LunaCopy.class).info("Wingman: LunaLib settings for joiners: " + n + " values of " + out.size() + " mods (keys left out)");
            return w.done();
        } catch (ClassNotFoundException e) {
            return null; // no LunaLib
        } catch (Throwable t) {
            Global.getLogger(LunaCopy.class).warn("Wingman: could not read LunaLib's settings for joiners; they keep their own", t);
            return null;
        }
    }

    /** The host's own key for each setting that is a key ("mod/field" -> key code), read once per battle. */
    private static Map<String, Integer> hostKeys;
    private static long hostKeysAt;

    /**
     * Is a joiner holding the key that stands, on their PC, for the setting whose key on the host is hostKey?
     * (A script on the host asks for the host's key; the joiner pressed their own for the same setting.)
     */
    static boolean heldByBinding(int hostKey, Map<String, Integer> joinerKeys, wingman.net.InputState input) {
        if (joinerKeys == null || joinerKeys.isEmpty()) return false;
        Map<String, Integer> mine = ownKeys();
        for (Map.Entry<String, Integer> e : mine.entrySet()) {
            if (e.getValue().intValue() != hostKey) continue;
            Integer theirs = joinerKeys.get(e.getKey());
            if (theirs != null && input.keyHeld(theirs.intValue())) return true;
        }
        return false;
    }

    private static synchronized Map<String, Integer> ownKeys() {
        long now = System.currentTimeMillis();
        if (hostKeys == null || now - hostKeysAt > 30000) {
            hostKeysAt = now;
            hostKeys = readKeys();
        }
        return hostKeys;
    }

    /** This PC's keys for LunaLib settings ("mod/field" -> key code); empty without LunaLib. */
    static Map<String, Integer> readKeys() {
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        try {
            Map<String, Object> all = settings();
            for (Field f : fields()) {
                if (out.size() >= MAX_KEYS) break;
                Object json = all.get(f.mod);
                if (json == null) continue;
                int k = keyOf(f.type, f.id, opt(json, f.id));
                if (k > 0) out.put(f.mod + "/" + f.id, k);
            }
        } catch (Throwable t) { /* no LunaLib, or not as expected: none */ }
        return out;
    }

    // ---- joiner ----

    /** This PC's keys for the host (Msg.LUNA_KEYS), or null if there are none. */
    static byte[] encodeKeys() {
        Map<String, Integer> keys = readKeys();
        return keys.isEmpty() ? null : wingman.net.LunaKeys.encode(keys);
    }

    /** The host's settings heard last (Msg.LUNA), put in place by apply(). */
    private static volatile byte[] heard;
    /** What apply() changed: mod -> field -> this PC's own value (null: there was none). */
    private static final Map<String, Map<String, Object>> undo = new LinkedHashMap<String, Map<String, Object>>();
    private static boolean warned;

    static void remember(byte[] data) { heard = data; }

    /** Put the host's settings in place for a battle (joiner side). Undone by restore(). */
    static synchronized void apply() {
        byte[] data = heard;
        if (data == null) return;
        restore(); // never two layers
        appliedAt = System.currentTimeMillis();
        try {
            Map<String, Object> host = Plain.readMap(Msg.reader(data));
            Map<String, Object> all = settings();
            int changed = 0, mods = 0;
            List<String> touched = new ArrayList<String>();
            for (Map.Entry<String, Object> m : host.entrySet()) {
                Object json = all.get(m.getKey());
                if (json == null || !(m.getValue() instanceof Map)) continue; // a mod this PC hasn't got
                Map<String, Object> saved = new LinkedHashMap<String, Object>();
                for (Map.Entry<?, ?> f : ((Map<?, ?>) m.getValue()).entrySet()) {
                    String id = String.valueOf(f.getKey());
                    Object want = f.getValue(), mine = opt(json, id);
                    if (want == null || sameValue(want, mine)) continue;
                    if (keyOf("String", id, mine) > 0) continue; // this PC's key for it stays
                    saved.put(id, mine);
                    put(json, id, want);
                    changed++;
                }
                if (!saved.isEmpty()) { undo.put(m.getKey(), saved); touched.add(m.getKey()); mods++; }
            }
            for (String mod : touched) settingsChanged(mod);
            Global.getLogger(LunaCopy.class).info("Wingman: LunaLib settings: " + (changed == 0 ? "this PC's already match the host's"
                    : changed + " values in " + mods + " mods follow the host's for this battle (" + join(touched) + ")"));
        } catch (ClassNotFoundException e) {
            // no LunaLib here
        } catch (Throwable t) {
            if (!warned) { warned = true; Global.getLogger(LunaCopy.class).warn("Wingman: could not take the host's LunaLib settings; this PC's own are used", t); }
        }
    }

    private static long appliedAt;

    /**
     * On the title screen: if the host's settings are still in place from a battle left another way, put this
     * PC's back. (The join mission takes them on the title screen a moment before its battle starts: only older
     * than that counts as left behind.)
     */
    static void restoreIfLeft() {
        if (!undo.isEmpty() && System.currentTimeMillis() - appliedAt > 15000) restore();
    }

    /** This PC's own settings back (after a battle). Safe to call any time. */
    static synchronized void restore() {
        if (undo.isEmpty()) return;
        try {
            Map<String, Object> all = settings();
            for (Map.Entry<String, Map<String, Object>> m : undo.entrySet()) {
                Object json = all.get(m.getKey());
                if (json == null) continue;
                for (Map.Entry<String, Object> f : m.getValue().entrySet()) {
                    try { put(json, f.getKey(), f.getValue()); } catch (Throwable t) { /* that one stays */ }
                }
            }
            for (String mod : undo.keySet()) settingsChanged(mod);
        } catch (Throwable t) {
            Global.getLogger(LunaCopy.class).warn("Wingman: could not put this PC's LunaLib settings back; restarting the game does", t);
        }
        undo.clear();
    }

    /** Tell mods that keep their own copy of their settings (LunaLib's listeners) to read them again. */
    private static void settingsChanged(String mod) {
        try {
            if (reportH == null) reportH = MethodHandles.publicLookup().findStatic(Class.forName(SETTINGS, true, Global.getSettings().getScriptClassLoader()),
                    "reportSettingsChanged", MethodType.methodType(void.class, String.class));
            reportH.invoke(mod);
        } catch (Throwable t) { /* their copy stays as it was */ }
    }

    private static boolean sameValue(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof Number && b instanceof Number) return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        return String.valueOf(a).equals(String.valueOf(b));
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size() && i < 12; i++) sb.append(i == 0 ? "" : ", ").append(l.get(i));
        if (l.size() > 12) sb.append(", ...");
        return sb.toString();
    }
}

package wingman;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;

/**
 * Ship Mastery System on a joiner's PC (that mod is reached by name: without it, all of this does nothing).
 *
 * The mod keeps the player's masteries in its own kind of object in the campaign's saved data, and only reads
 * them, and works out which mastery effects each hull has, when a campaign is loaded. A joiner's game never
 * loads one, so the copies of the host's ships had no mastery effects at all. Here:
 *  - the host sends its mastery table as plain data, in the campaign copy (see addTo);
 *  - the joiner rebuilds the table as the mod's own objects, and has the mod load it and work out its masteries
 *    (from the host's seed, which comes with the campaign copy) as it does on loading a campaign (install);
 *  - each copy of a ship is introduced to the mod, so its hull mod knows the ship's fleet and commander and
 *    applies that commander's masteries: the host's for the player's ships, a fleet commander's for others (track).
 * Afterwards the mod is left to redo all of it the next time this PC loads a campaign of its own (uninstall).
 *
 * The mod's internals are not an interface it promises: each step is tried on its own, and one that doesn't
 * fit this version of the mod switches the rest off for the session, with a line in the log.
 */
final class ShipMasteryBridge {
    private ShipMasteryBridge() {}

    /** Where the mod keeps its table in saved data, and the plain copy of it in the campaign copy. */
    private static final String TABLE_KEY = "shipmastery_Mastery", COPY_KEY = "wingman_shipmastery";
    private static final String RANDOM_MODE_KEY = "$sms_IsRandomMode";
    private static final String MOD = "shipmastery.ShipMastery", TABLE = "shipmastery.ShipMastery$SaveDataTable",
            SAVE_DATA = "shipmastery.data.SaveData", LOOKUP = "shipmastery.util.VariantLookup", PLUGIN = "shipmastery.plugin.ModPlugin";
    static final int MAX_HULLS = 2000, MAX_BYTES = 400 * 1024;

    private static boolean broken, installed;
    private static Object previousTable;
    private static boolean hadTable;
    private static int tracked;

    private static Class<?> cls(String name) throws ClassNotFoundException {
        return Class.forName(name, true, Global.getSettings().getScriptClassLoader());
    }

    // The game forbids its mods java.lang.reflect: the mod is reached through method handles.
    private static final MethodHandles.Lookup PUBLIC = MethodHandles.publicLookup();
    private static MethodHandle addInfo;

    /** A public field's getter, trying each of the types it may be declared as. */
    private static MethodHandle getter(Class<?> c, String name, Class<?>... types) throws Throwable {
        Throwable last = null;
        for (Class<?> t : types) {
            try { return PUBLIC.findGetter(c, name, t); } catch (Throwable e) { last = e; }
        }
        throw last;
    }

    private static void fail(String step, Throwable t) {
        if (broken) return;
        broken = true;
        Global.getLogger(ShipMasteryBridge.class).warn("Wingman: Ship Mastery System's masteries can't be copied here (" + step
                + " didn't fit this version of it); the copies of ships go without them", t);
    }

    // ---- host ----

    /**
     * The host's mastery table as plain data, into the campaign copy's saved data (if Ship Mastery is in use).
     * Returns about how many bytes it takes there.
     */
    static int addTo(Map<String, Object> saved, Map<String, Object> copy) {
        Object table = saved == null ? null : saved.get(TABLE_KEY);
        if (!(table instanceof Map)) return 0;
        try {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) table).entrySet()) {
                if (out.size() >= MAX_HULLS) break;
                Object d = e.getValue();
                if (!(e.getKey() instanceof String) || d == null) continue;
                Class<?> c = d.getClass();
                float points = ((Number) getter(c, "points", float.class).invoke(d)).floatValue();
                int level = ((Number) getter(c, "level", int.class).invoke(d)).intValue();
                Map<String, Object> active = new LinkedHashMap<String, Object>();
                Object levels = getter(c, "activeLevels", java.util.NavigableMap.class, java.util.SortedMap.class, Map.class).invoke(d);
                if (levels instanceof Map) for (Map.Entry<?, ?> a : ((Map<?, ?>) levels).entrySet()) active.put(String.valueOf(a.getKey()), String.valueOf(a.getValue()));
                if (level <= 0 && active.isEmpty()) continue; // nothing to apply
                Map<String, Object> hull = new LinkedHashMap<String, Object>();
                hull.put("points", Float.valueOf(points));
                hull.put("level", Integer.valueOf(level));
                hull.put("active", active);
                out.put((String) e.getKey(), hull);
            }
            wingman.net.Msg.W w = new wingman.net.Msg.W();
            wingman.net.Plain.write(w.out, out);
            int bytes = w.done().length;
            if (bytes > MAX_BYTES) {
                Global.getLogger(ShipMasteryBridge.class).warn("Wingman: Ship Mastery System's masteries are too many to send joiners (" + bytes / 1024 + " KB)");
                return 0;
            }
            copy.put(COPY_KEY, out);
            Global.getLogger(ShipMasteryBridge.class).info("Wingman: Ship Mastery System: masteries of " + out.size() + " hulls go to joiners with the campaign copy");
            return bytes + 64;
        } catch (Throwable t) {
            Global.getLogger(ShipMasteryBridge.class).warn("Wingman: could not copy Ship Mastery System's masteries for joiners", t);
            return 0;
        }
    }

    // ---- joiner ----

    /**
     * After the stand-in campaign is in place (its saved data holds the host's plain copy): rebuild the mod's
     * table, have the mod load it and work out the masteries.
     */
    @SuppressWarnings("unchecked")
    static void install() {
        if (broken || installed) return;
        Map<String, Object> pd;
        try { pd = Global.getSector().getPersistentData(); } catch (Throwable t) { return; }
        Object copy = pd == null ? null : pd.get(COPY_KEY);
        if (!(copy instanceof Map)) return;
        Class<?> mod;
        try { mod = cls(MOD); } catch (Throwable t) { return; } // not installed here
        int hulls = 0;
        try {
            Class<?> tableClass = cls(TABLE), dataClass = cls(SAVE_DATA);
            Map<String, Object> table = (Map<String, Object>) PUBLIC.findConstructor(tableClass, MethodType.methodType(void.class)).invoke();
            MethodHandle dataNew = PUBLIC.findConstructor(dataClass, MethodType.methodType(void.class, float.class, int.class));
            MethodHandle activate = PUBLIC.findVirtual(dataClass, "activateLevel", MethodType.methodType(boolean.class, int.class, String.class));
            for (Map.Entry<String, Object> e : ((Map<String, Object>) copy).entrySet()) {
                if (!(e.getValue() instanceof Map)) continue;
                Map<String, Object> h = (Map<String, Object>) e.getValue();
                float points = h.get("points") instanceof Number ? ((Number) h.get("points")).floatValue() : 0f;
                int level = h.get("level") instanceof Number ? ((Number) h.get("level")).intValue() : 0;
                Object d = dataNew.invoke(points, level);
                if (h.get("active") instanceof Map) {
                    for (Map.Entry<String, Object> a : ((Map<String, Object>) h.get("active")).entrySet()) {
                        int lv;
                        try { lv = Integer.parseInt(a.getKey()); } catch (NumberFormatException ignored) { continue; } // not a level
                        boolean changed = (Boolean) activate.invoke(d, lv, String.valueOf(a.getValue()));
                    }
                }
                table.put(e.getKey(), d);
                hulls++;
            }
            hadTable = pd.containsKey(TABLE_KEY);
            previousTable = pd.get(TABLE_KEY);
            pd.put(TABLE_KEY, table);
            installed = true;
        } catch (Throwable t) { fail("rebuilding the table", t); return; }
        try { PUBLIC.findStatic(mod, "loadMasteryTable", MethodType.methodType(void.class)).invoke(); } catch (Throwable t) { fail("loading the table", t); return; }
        try {
            boolean random = Boolean.TRUE.equals(pd.get(RANDOM_MODE_KEY));
            long started = System.nanoTime();
            PUBLIC.findStatic(mod, "initMasteries", MethodType.methodType(void.class, boolean.class)).invoke(random);
            Global.getLogger(ShipMasteryBridge.class).info("Wingman: Ship Mastery System: the host's masteries of " + hulls + " hulls are in place ("
                    + (random ? "random" : "set") + " masteries worked out in " + (System.nanoTime() - started) / 1000000L + " ms)");
        } catch (Throwable t) { fail("working out the masteries", t); return; }
        try {
            if (lookup() == null) PUBLIC.findConstructor(cls(LOOKUP), MethodType.methodType(void.class)).invoke(); // it registers itself (needed by track)
            addInfo = PUBLIC.findStatic(cls(LOOKUP), "addVariantInfo", MethodType.methodType(void.class, ShipVariantAPI.class, ShipVariantAPI.class, FleetMemberAPI.class));
        } catch (Throwable t) { fail("its ship lookup", t); return; }
        forgetLoadedCampaign();
        tracked = 0;
    }

    private static Object lookup() throws Throwable {
        Class<?> c = cls(LOOKUP);
        return PUBLIC.findStatic(c, "getInstance", MethodType.methodType(c)).invoke();
    }

    /**
     * A copy of a ship, about to be built: introduced to the mod (with its modules) so that its mastery hull mod
     * finds the ship's fleet and commander, as on the host. The member must be in its fleet already.
     */
    static void track(FleetMemberAPI member) {
        if (!installed || broken || member == null) return;
        try {
            ShipVariantAPI root = member.getVariant();
            addAll(addInfo, root, root, member, 0);
            tracked++;
        } catch (Throwable t) { fail("introducing a ship", t); }
    }

    private static void addAll(MethodHandle add, ShipVariantAPI v, ShipVariantAPI root, FleetMemberAPI member, int depth) throws Throwable {
        if (v == null || depth > 4) return;
        add.invoke(v, root, member);
        List<String> slots = v.getModuleSlots();
        if (slots != null) for (String slot : slots) addAll(add, v.getModuleVariant(slot), root, member, depth + 1);
    }

    /** After the battle: the stand-in's saved data as it was, and the mod made to redo its work next campaign load. */
    static void uninstall() {
        if (!installed) return;
        installed = false;
        try {
            Map<String, Object> pd = Global.getSector().getPersistentData();
            if (hadTable) pd.put(TABLE_KEY, previousTable); else pd.remove(TABLE_KEY);
        } catch (Throwable t) { /* the stand-in goes anyway */ }
        previousTable = null;
        forgetLoadedCampaign();
        if (tracked > 0) Global.getLogger(ShipMasteryBridge.class).info("Wingman: Ship Mastery System: " + tracked + " ship copies had masteries applied");
    }

    /**
     * The mod works its masteries out again on loading a campaign only if that campaign isn't the last one it
     * loaded (ModPlugin.lastSaveId). Forgotten, so that a campaign of this PC's own, loaded after a co-op
     * battle, gets its own masteries back rather than the host's.
     */
    private static void forgetLoadedCampaign() {
        try {
            Class<?> c = cls(PLUGIN);
            MethodHandle pli = MethodHandles.lookup().findStatic(MethodHandles.class, "privateLookupIn",
                    MethodType.methodType(MethodHandles.Lookup.class, Class.class, MethodHandles.Lookup.class));
            MethodHandles.Lookup l = (MethodHandles.Lookup) pli.invoke(c, MethodHandles.lookup());
            l.findStaticSetter(c, "lastSaveId", String.class).invoke((String) null);
        } catch (Throwable t) {
            Global.getLogger(ShipMasteryBridge.class).warn("Wingman: Ship Mastery System could not be told to redo its masteries on the next campaign load; "
                    + "if your own campaign's masteries look wrong after co-op, restart the game before loading it", t);
        }
    }
}

package wingman;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;

import lunalib.backend.ui.settings.FakeJson;
import lunalib.backend.ui.settings.LunaSettingsData;
import lunalib.backend.ui.settings.LunaSettingsLoader;
import wingman.net.InputState;
import wingman.net.LunaKeys;
import wingman.net.Msg;
import wingman.net.Plain;

/**
 * LunaCopy and ShipMasteryBridge against stand-ins for LunaLib and Ship Mastery System laid out as those mods
 * are (test/modfakes): the host's side, the joiner's, and putting things back.
 */
public class BridgeTest {
    static int failed, passed;

    static void check(boolean ok, String what) {
        if (ok) passed++; else failed++;
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
    }

    static final Map<String, Object> saved = new HashMap<String, Object>();

    @SuppressWarnings("unchecked")
    static <T> T fake(Class<T> type, final Map<String, Object> answers) {
        return (T) Proxy.newProxyInstance(BridgeTest.class.getClassLoader(), new Class<?>[] { type }, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                if (answers.containsKey(m.getName())) return answers.get(m.getName());
                Class<?> r = m.getReturnType();
                if (r == boolean.class) return false;
                if (r == int.class) return 0;
                if (r == float.class) return 0f;
                return null;
            }
        });
    }

    static void setting(String mod, String field, String type, Object value) {
        LunaSettingsLoader.getSettingsData().add(new LunaSettingsData(mod, field, type));
        FakeJson j = (FakeJson) LunaSettingsLoader.getSettings().get(mod);
        if (j == null) { j = new FakeJson(); LunaSettingsLoader.getSettings().put(mod, j); }
        if (value != null) j.put(field, value);
    }

    static Object value(String mod, String field) { return ((FakeJson) LunaSettingsLoader.getSettings().get(mod)).opt(field); }

    public static void main(String[] args) throws Exception {
        Map<String, Object> settingsAnswers = new HashMap<String, Object>();
        settingsAnswers.put("getScriptClassLoader", BridgeTest.class.getClassLoader());
        Global.setSettings(fake(SettingsAPI.class, settingsAnswers));
        Map<String, Object> sectorAnswers = new HashMap<String, Object>();
        sectorAnswers.put("getPersistentData", saved);
        Global.setSector(fake(SectorAPI.class, sectorAnswers));

        // ---- LunaLib: the host's side ----
        setting("shu", "head", "Header", "Hullmod Subsystem Hotkey");
        setting("shu", "customStats", "Boolean", Boolean.TRUE);
        setting("shu", "armorBonus", "Double", 2.5);
        setting("shu", "pool", "Int", 7);
        setting("shu", "shu_subsystemHotkey_x", "String", "LMENU");
        setting("shu", "abilityKey", "Keycode", 30);
        setting("shu", "label", "String", "just words");
        byte[] host = LunaCopy.encodeHost();
        Map<String, Object> sent = Plain.readMap(Msg.reader(host));
        Map<?, ?> shu = (Map<?, ?>) sent.get("shu");
        check(shu != null && Boolean.TRUE.equals(shu.get("customStats")) && Double.valueOf(2.5).equals(shu.get("armorBonus"))
                && Integer.valueOf(7).equals(shu.get("pool")) && "just words".equals(shu.get("label")), "luna: the host's values are sent: " + shu);
        check(shu != null && !shu.containsKey("head") && !shu.containsKey("shu_subsystemHotkey_x") && !shu.containsKey("abilityKey"),
                "luna: headings and keys are not");
        Map<String, Integer> hostKeys = LunaCopy.readKeys();
        check(Integer.valueOf(56).equals(hostKeys.get("shu/shu_subsystemHotkey_x")) && Integer.valueOf(30).equals(hostKeys.get("shu/abilityKey")),
                "luna: the host's own keys are found (key names and key codes): " + hostKeys);

        // A joiner who bound the same ability to X: holding X counts when the host's script asks for LMENU.
        Map<String, Integer> theirs = new HashMap<String, Integer>();
        theirs.put("shu/shu_subsystemHotkey_x", 45);
        Map<String, Integer> back = LunaKeys.decode(LunaKeys.encode(theirs));
        InputState holdingX = new InputState(); holdingX.keys = new int[] { 45 };
        InputState holdingNothing = new InputState();
        check(back.equals(theirs), "luna: a joiner's keys travel");
        check(LunaCopy.heldByBinding(56, back, holdingX) && !LunaCopy.heldByBinding(56, back, holdingNothing) && !LunaCopy.heldByBinding(30, back, holdingX),
                "luna: the joiner's key for the same setting counts as the host's key, and only that one");

        // ---- LunaLib: the joiner's side ----
        LunaSettingsLoader.getSettings().clear();
        LunaSettingsLoader.getSettingsData().clear();
        setting("shu", "customStats", "Boolean", Boolean.FALSE);
        setting("shu", "armorBonus", "Double", 1.0);
        setting("shu", "pool", "Int", 7);
        setting("shu", "shu_subsystemHotkey_x", "String", "X");
        setting("shu", "label", "String", "just words");
        LunaCopy.remember(host);
        LunaCopy.apply();
        check(Boolean.TRUE.equals(value("shu", "customStats")) && Double.valueOf(2.5).equals(value("shu", "armorBonus")),
                "luna: the joiner takes the host's values for the battle");
        check("X".equals(value("shu", "shu_subsystemHotkey_x")), "luna: the joiner's own key stays");
        check(lunalib.lunaSettings.LunaSettings.told.equals(Arrays.asList("shu")), "luna: the mod is told its settings changed: " + lunalib.lunaSettings.LunaSettings.told);
        LunaCopy.restore();
        check(Boolean.FALSE.equals(value("shu", "customStats")) && Double.valueOf(1.0).equals(value("shu", "armorBonus")) && Integer.valueOf(7).equals(value("shu", "pool")),
                "luna: the joiner's own values come back after the battle");
        LunaCopy.restore();
        check(Boolean.FALSE.equals(value("shu", "customStats")), "luna: putting back twice is harmless");

        // ---- Ship Mastery: the host's side ----
        shipmastery.ShipMastery.SaveDataTable table = new shipmastery.ShipMastery.SaveDataTable();
        shipmastery.data.SaveData a = new shipmastery.data.SaveData(10f, 2);
        a.activateLevel(1, "A1");
        a.activateLevel(2, "B2");
        table.put("hull_a", a);
        table.put("hull_b", new shipmastery.data.SaveData(3f, 0)); // nothing to apply
        Map<String, Object> hostSaved = new HashMap<String, Object>();
        hostSaved.put("shipmastery_Mastery", table);
        Map<String, Object> copy = new HashMap<String, Object>();
        int bytes = ShipMasteryBridge.addTo(hostSaved, copy);
        Map<?, ?> plain = (Map<?, ?>) copy.get("wingman_shipmastery");
        check(bytes > 0 && plain != null && plain.size() == 1 && plain.containsKey("hull_a") && Plain.isPlain(plain), "mastery: the host's table goes as plain data: " + plain);

        // ---- Ship Mastery: the joiner's side (the copy has been through the network) ----
        Object travelled = Plain.read(Msg.reader(encode(copy.get("wingman_shipmastery"))));
        saved.put("wingman_shipmastery", travelled);
        saved.put("$sms_IsRandomMode", Boolean.TRUE);
        ShipMasteryBridge.install();
        Object rebuilt = shipmastery.ShipMastery.table;
        shipmastery.data.SaveData got = rebuilt instanceof Map ? ((Map<String, shipmastery.data.SaveData>) rebuilt).get("hull_a") : null;
        check(rebuilt instanceof shipmastery.ShipMastery.SaveDataTable && got != null && got.level == 2 && got.points == 10f
                && "A1".equals(got.activeLevels.get(1)) && "B2".equals(got.activeLevels.get(2)), "mastery: the mod loads the host's table, as its own objects");
        check(shipmastery.ShipMastery.worked && shipmastery.ShipMastery.random, "mastery: the mod works its masteries out (random mode as the host's)");
        check(shipmastery.util.VariantLookup.getInstance() != null, "mastery: the mod's ship lookup is there");
        check(shipmastery.plugin.ModPlugin.lastSaveId() == null, "mastery: the mod will redo its work on this PC's next campaign load");

        Map<String, Object> moduleAnswers = new HashMap<String, Object>();
        moduleAnswers.put("getModuleSlots", new ArrayList<String>());
        ShipVariantAPI module = fake(ShipVariantAPI.class, moduleAnswers);
        Map<String, Object> rootAnswers = new HashMap<String, Object>();
        List<String> slots = new ArrayList<String>(Arrays.asList("WS0001"));
        rootAnswers.put("getModuleSlots", slots);
        rootAnswers.put("getModuleVariant", module);
        ShipVariantAPI root = fake(ShipVariantAPI.class, rootAnswers);
        Map<String, Object> memberAnswers = new HashMap<String, Object>();
        memberAnswers.put("getVariant", root);
        ShipMasteryBridge.track(fake(FleetMemberAPI.class, memberAnswers));
        check(shipmastery.util.VariantLookup.added == 2, "mastery: a ship and its module are introduced to the mod (" + shipmastery.util.VariantLookup.added + ")");

        ShipMasteryBridge.uninstall();
        check(!saved.containsKey("shipmastery_Mastery"), "mastery: the table is taken out of the stand-in's saved data afterwards");

        System.out.println(failed == 0 ? "BRIDGES PASSED (" + passed + " checks)" : "BRIDGES FAILED (" + failed + " of " + (passed + failed) + ")");
        if (failed > 0) System.exit(1);
    }

    static byte[] encode(Object v) throws Exception {
        Msg.W w = new Msg.W();
        Plain.write(w.out, v);
        return w.done();
    }
}

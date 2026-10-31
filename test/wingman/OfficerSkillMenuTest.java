package wingman;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.characters.SkillSpecAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.rpg.OfficerData;
import com.fs.starfarer.rpg.Person;

/** Headless checks for the native menu's intent-only bridge; does not claim to render a game dialog. */
public final class OfficerSkillMenuTest {
    private static int checks;
    private static final Class<?> DATA = nested("NativeOfficerData");
    private static final Class<?> SESSION = nested("Session");

    public static void main(String[] args) throws Exception {
        choices();
        nativeSignatures();
        lifecycle();
        System.out.println("Officer skill menu: " + checks + " checks passed (native signatures, offers, intent and cancellation).");
    }

    private static void choices() throws Exception {
        SkillSpecAPI spec = (SkillSpecAPI) Proxy.newProxyInstance(SkillSpecAPI.class.getClassLoader(),
                new Class<?>[] { SkillSpecAPI.class }, (p, m, a) -> {
                    if (m.getName().equals("isCombatOfficerSkill")) return false; // mod-offered choices are host-owned
                    throw new AssertionError("Unexpected skill call: " + m.getName());
                });
        Global.setSettings((SettingsAPI) Proxy.newProxyInstance(SettingsAPI.class.getClassLoader(),
                new Class<?>[] { SettingsAPI.class }, (p, m, a) -> {
                    if (m.getName().equals("getSkillSpec")) return "missing".equals(a[0]) ? null : spec;
                    throw new AssertionError("Unexpected settings call: " + m.getName());
                }));
        Method checked = OfficerSkillMenu.class.getDeclaredMethod("checkedChoices", List.class);
        checked.setAccessible(true);
        List<String> source = new ArrayList<String>();
        for (int i = 0; i < 32; i++) source.add("mod_skill_" + i);
        @SuppressWarnings("unchecked") List<String> offered = (List<String>) checked.invoke(null, source);
        check(offered.equals(source), "preserves more than six host/mod offers");
        source.clear();
        check(offered.size() == 32, "offer list is detached from caller changes");
        try { offered.add("extra"); throw new AssertionError("mutable offer list"); }
        catch (UnsupportedOperationException expected) { checks++; }
        List<String> oversized = new ArrayList<String>();
        for (int i = 0; i <= wingman.net.Roster.MAX_SKILL_CHOICES; i++) oversized.add("mod_skill_" + i);
        try { checked.invoke(null, oversized); throw new AssertionError("oversized offer list was accepted"); }
        catch (java.lang.reflect.InvocationTargetException expected) {
            check(expected.getCause() instanceof IllegalArgumentException, "oversized offers fail closed rather than truncate");
        }
    }

    private static void nativeSignatures() throws Exception {
        String ownerName = constant("NATIVE_OWNER");
        Class<?> owner = Class.forName(ownerName, false, OfficerSkillMenu.class.getClassLoader());
        Method opener = owner.getDeclaredMethod(constant("NATIVE_OWNER_OPEN_SKILLS"));
        check(opener.getReturnType() == void.class, "verified vanilla opener is a no-argument void method");
        Method privateLookup = OfficerSkillMenu.class.getDeclaredMethod("privateLookup", Class.class);
        privateLookup.setAccessible(true);
        java.lang.invoke.MethodHandles.Lookup lookup = (java.lang.invoke.MethodHandles.Lookup) privateLookup.invoke(null, owner);
        check(lookup.findVirtual(owner, opener.getName(), java.lang.invoke.MethodType.methodType(void.class)) != null,
                "native private opener is accessible through the actual method-handle seam");
        Class<?> picker = Class.forName("com.fs.starfarer.coreui.CaptainPickerDialog", false, owner.getClassLoader());
        Class<?> fleet = Class.forName("com.fs.starfarer.campaign.fleet.CampaignFleet", false, owner.getClassLoader());
        Class<?> member = Class.forName("com.fs.starfarer.campaign.fleet.FleetMember", false, owner.getClassLoader());
        Class<?> parent = Class.forName("com.fs.starfarer.ui.interfacenew", false, owner.getClassLoader());
        Class<?> delegate = Class.forName("com.fs.starfarer.ui.oo0O$o", false, owner.getClassLoader());
        check(picker.getDeclaredConstructor(fleet, member, parent, delegate) != null, "native owner parent constructor matches");
        check(owner.getDeclaredConstructor(picker, fleet, member, OfficerData.class, boolean.class, boolean.class) != null,
                "native officer row constructor matches");
    }

    private static void lifecycle() throws Exception {
        List<String> selections = new ArrayList<String>();
        OfficerData data = data();
        Object session = session(data, selections);
        check(OfficerSkillMenu.isOpen(), "session opens");
        check(data.canLevelUp() && data.canLevelUp(true), "native patches see the host's eligible level-up state");
        check(data.getSkillPicks().equals(Arrays.asList("a", "b")), "native data returns host choices");
        data.makeSkillPicks();
        data.makeSkillPicks(new java.util.Random(1));
        check(data.getSkillPicks().equals(Arrays.asList("a", "b")), "native data cannot reroll offers");
        data.levelUp("forged");
        check(field(session, "selected").get(session) == null, "unoffered skill emits no intent");
        data.levelUp("a");
        data.levelUp("b");
        check("a".equals(field(session, "selected").get(session)), "only first valid intent is retained");
        check(!data.canLevelUp() && !data.canLevelUp(true), "confirmation cannot open another local level-up");
        check(selections.isEmpty(), "selection is not sent before native dismissal");
        field(session, "dismissed").setBoolean(session, true);
        OfficerSkillMenu.advance(0.1f);
        OfficerSkillMenu.advance(0.1f);
        check(selections.equals(Collections.singletonList("a")), "native confirmation emits exactly once");
        check(!OfficerSkillMenu.isOpen(), "confirmation releases native tree");

        Object cancelled = session(data(), selections);
        field(cancelled, "dismissed").setBoolean(cancelled, true);
        OfficerSkillMenu.advance(0.1f);
        check(selections.size() == 1 && !OfficerSkillMenu.isOpen(), "native cancel emits nothing and releases tree");

        OfficerData discarded = data();
        session(discarded, selections);
        discarded.levelUp("b");
        OfficerSkillMenu.close();
        OfficerSkillMenu.advance(0.1f);
        check(selections.size() == 1 && !OfficerSkillMenu.isOpen(), "explicit close discards pending intent");

        session(data(), selections);
        final boolean[] consumed = { false };
        InputEventAPI event = (InputEventAPI) Proxy.newProxyInstance(InputEventAPI.class.getClassLoader(),
                new Class<?>[] { InputEventAPI.class }, (p, m, a) -> {
                    if (m.getName().equals("consume")) { consumed[0] = true; return null; }
                    if (m.getName().equals("isConsumed")) return consumed[0];
                    throw new AssertionError("Unexpected input call: " + m.getName());
                });
        OfficerSkillMenu.input(Collections.singletonList(event));
        check(consumed[0], "modal consumes input before lobby can handle it");
        OfficerSkillMenu.close();
    }

    private static OfficerData data() throws Exception {
        Constructor<?> constructor = DATA.getDeclaredConstructor(Person.class, List.class);
        constructor.setAccessible(true);
        // No real Person is needed: the override must never touch stats or invoke vanilla levelUp.
        return (OfficerData) constructor.newInstance(null, Arrays.asList("a", "b"));
    }

    private static Object session(OfficerData data, List<String> selections) throws Exception {
        CustomPanelAPI root = (CustomPanelAPI) Proxy.newProxyInstance(CustomPanelAPI.class.getClassLoader(),
                new Class<?>[] { CustomPanelAPI.class }, (p, m, a) -> {
                    if (m.getName().equals("advance") || m.getName().equals("processInput") || m.getName().equals("render")) return null;
                    throw new AssertionError("Unexpected native tree call: " + m.getName());
                });
        Constructor<?> constructor = SESSION.getDeclaredConstructor(CustomPanelAPI.class, DATA, Object.class, OfficerSkillMenu.Callback.class);
        constructor.setAccessible(true);
        Object session = constructor.newInstance(root, data, new Object(), (OfficerSkillMenu.Callback) selections::add);
        Field current = OfficerSkillMenu.class.getDeclaredField("session");
        current.setAccessible(true);
        current.set(null, session);
        return session;
    }

    private static String constant(String name) throws Exception {
        Field value = OfficerSkillMenu.class.getDeclaredField(name);
        value.setAccessible(true);
        return (String) value.get(null);
    }

    private static Field field(Object value, String name) throws Exception {
        Field field = value.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Class<?> nested(String name) {
        try { return Class.forName(OfficerSkillMenu.class.getName() + "$" + name); }
        catch (ClassNotFoundException e) { throw new AssertionError(e); }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
}

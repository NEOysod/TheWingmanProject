package wingman.client;

import java.awt.Color;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.Pbuffer;
import org.lwjgl.opengl.PixelFormat;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponSize;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;

import wingman.net.RefitData;
import wingman.net.RefitRequest;
import wingman.Panel;

/** Built-in tiles share weapon presentation, but never enter editable slots or requests. */
public final class RefitBuiltInWeaponTest {
    private static final Map<String, WeaponSlotAPI> slots = new HashMap<String, WeaponSlotAPI>();
    private static final HashMap<String, String> builtIns = new HashMap<String, String>();
    private static final List<String> rendered = new ArrayList<String>(), pictures = new ArrayList<String>(), unexpected = new ArrayList<String>();
    private static final Map<String, Color> colors = new HashMap<String, Color>();
    private static int checks;
    private static boolean missingHull, missingWeapon;

    public static void main(String[] args) throws Exception {
        slots.put("regular", slot("regular", false, false, false, false, false, WeaponSize.SMALL));
        slots.put("builtin", slot("builtin", true, false, false, false, false, WeaponSize.LARGE));
        slots.put("builtin2", slot("builtin2", true, false, false, false, false, WeaponSize.MEDIUM));
        slots.put("decoration", slot("decoration", true, true, false, false, false, WeaponSize.SMALL));
        slots.put("hidden", slot("hidden", true, false, true, false, false, WeaponSize.SMALL));
        slots.put("system", slot("system", true, false, false, true, false, WeaponSize.SMALL));
        slots.put("module", slot("module", true, false, false, false, true, WeaponSize.LARGE));
        slots.put("unfitted", slot("unfitted", true, false, false, false, false, WeaponSize.SMALL));
        for (String id : Arrays.asList("builtin", "builtin2", "decoration", "hidden", "system", "module")) builtIns.put(id, "fixed_gun");
        ShipHullSpecAPI hull = proxy(ShipHullSpecAPI.class, (p, m, a) -> {
            if (m.getName().equals("getAllWeaponSlotsCopy")) return new ArrayList<WeaponSlotAPI>(slots.values());
            if (m.getName().equals("getWeaponSlotAPI")) return slots.get(a[0]);
            if (m.getName().equals("getBuiltInWeapons")) return builtIns;
            if (m.getName().equals("getBuiltInWings")) return Arrays.asList("fixed_wing");
            return unexpected("hull " + m.getName());
        });
        Global.setSettings(proxy(SettingsAPI.class, (p, m, a) -> {
            String method = m.getName();
            if (method.equals("getHullSpec")) return missingHull ? null : hull;
            if (method.equals("getWeaponSpec")) {
                if (missingWeapon) throw new IllegalArgumentException("missing modded weapon");
                return proxy(WeaponSpecAPI.class, (wp, wm, wa) -> {
                    if (wm.getName().equals("getWeaponName")) return "Fixed Gun";
                    if (wm.getName().equals("getTurretSpriteName") || wm.getName().equals("getHardpointSpriteName")) return "graphics/fixed_gun.png";
                    return unexpected("weapon " + wm.getName());
                });
            }
            if (method.equals("getFighterWingSpec")) return proxy(FighterWingSpecAPI.class, (fp, fm, fa) -> {
                if (fm.getName().equals("getWingName")) return "Fixed Wing";
                if (fm.getName().equals("getVariant")) return null;
                return unexpected("wing " + fm.getName());
            });
            if (method.equals("getSprite")) { pictures.add((String) a[0]); return null; }
            if (method.equals("loadTexture")) return null;
            if (method.equals("getString") && a[0].equals("defaultFont")) return "test-font";
            if (method.equals("createLabel")) return label((String) a[0]);
            if (method.equals("getMouseX")) return 20;
            if (method.equals("getMouseY")) return 20;
            return unexpected("settings " + method);
        }));
        try {
            exercise();
            check(unexpected.isEmpty(), "no unexpected API calls: " + unexpected);
            System.out.println("Refit built-in weapons: " + checks + " checks passed (rows, read-only input, names, icons and tooltips).");
        } finally {
            Global.setSettings(null);
        }
    }

    private static void exercise() throws Exception {
        LobbyScreen lobby = new LobbyScreen();
        lobby.body = "test-font";
        RefitScreen screen = lobby.refit;
        RefitData data = new RefitData();
        data.hullId = "test_hull";
        data.fitSlots.add("regular");
        data.fitWeapons.add("normal_gun");
        for (String id : Arrays.asList("regular", "builtin", "builtin2")) {
            RefitData.Slot s = new RefitData.Slot();
            s.place = id;
            s.size = slots.get(id).getSlotSize().name();
            s.type = "ENERGY";
            s.builtIn = !id.equals("regular");
            s.editable = !s.builtIn;
            s.builtInWeapon = s.builtIn ? "fixed_gun" : "";
            data.slots.add(s);
        }
        data.wings.add("fixed_wing");
        RefitRequest request = new RefitRequest();
        field(RefitScreen.class, "data").set(screen, data);
        field(RefitScreen.class, "req").set(screen, request);
        List<?> rows = (List<?>) call(screen, "rows");
        Object builtin = find(rows, "builtin"), builtin2 = find(rows, "builtin2"), regular = find(rows, "regular"), bay = find(rows, "0");
        check(builtin != null && builtin2 != null && regular != null, "both fixed mounts and editable slot listed");
        check(rows.indexOf(builtin) < rows.indexOf(builtin2) && rows.indexOf(builtin2) < rows.indexOf(regular), "weapons remain ordered largest first");
        check(kind(builtin) != kind(regular), "read-only rows distinct from editable slots");
        check(((List<?>) call(screen, "slots")).equals(Arrays.asList("regular")), "ship preview edit rings still exclude built-ins");
        for (String id : Arrays.asList("decoration", "hidden", "system", "module", "unfitted")) check(find(rows, id) == null, id + " excluded");
        wingman.net.RefitNames names = new wingman.net.RefitNames(data);
        for (boolean availableOnly : new boolean[] { true, false }) {
            field(RefitScreen.class, "availableOnly").setBoolean(screen, availableOnly);
            check(((List<?>) call(screen, "build", builtin, names, true)).isEmpty(), "built-in has no choices, availableOnly=" + availableOnly);
        }
        check(((List<?>) call(screen, "build", regular, names, false)).size() == 1, "editable empty-slot choice preserved");
        check(((List<?>) call(screen, "build", bay, names, false)).isEmpty(), "built-in fighter remains read-only");
        call(screen, "mark", rows, rows.indexOf(builtin));
        for (int key : new int[] { 28, 203, 205, 31, 44, 48, 211 }) screen.key(key, '\0', false, false);
        check(request.changes.isEmpty() && request.addMods.isEmpty() && request.removeMods.isEmpty() && request.buildIn.isEmpty(), "keyboard cannot alter fixed weapon");
        call(screen, "pick", builtin, null);
        check(request.changes.isEmpty(), "direct pick path rejects fixed rows before touching choice");

        Pbuffer buffer = new Pbuffer(128, 128, new PixelFormat(), null, null);
        try {
            buffer.makeCurrent();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glLoadIdentity();
            GL11.glOrtho(0, 512, 0, 512, -1, 1);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glLoadIdentity();
            call(screen, "tile", builtin, null, names, 0f, 0f, 300f, 44f);
            check(rendered.contains("Fixed Gun"), "built-in name resolves from local hull mapping, not host fitSlots");
            check(colors.get("Fixed Gun").equals(Panel.DIM), "fixed weapon name dimmed like built-in fighter");
            check(rendered.stream().anyMatch(s -> s.contains("(built in)")), "fixed weapon marked built in");
            check(pictures.contains("graphics/fixed_gun.png"), "normal weapon icon path used");
            check(field(Tips.class, "hoverKey").get(null).equals("w:fixed_gun"), "fixed weapon tile triggers normal weapon tooltip");
            rendered.clear();
            call(screen, "side", builtin, null, names, 0f, 400f, 300f, 0f);
            check(rendered.contains("This weapon is built into the hull."), "selected fixed row explains read-only state");
            check(((List<?>) field(RefitScreen.class, "hits").get(screen)).isEmpty(), "fixed row adds no replacement/removal buttons");
            rendered.clear();
            call(screen, "tile", bay, null, names, 0f, 0f, 300f, 44f);
            check(colors.get("Fixed Wing").equals(Panel.DIM), "built-in fighter styling preserved");
            rendered.clear();
            missingWeapon = true;
            call(screen, "tile", builtin, null, names, 0f, 0f, 300f, 44f);
            check(rendered.contains("fixed_gun"), "missing mod weapon falls back to ID");
            missingWeapon = false;
            check(GL11.glGetError() == GL11.GL_NO_ERROR, "no OpenGL errors in tile/sidebar paths");
        } finally {
            buffer.destroy();
        }
        check(request.changes.isEmpty(), "drawing never mutates request");
        missingHull = true;
        check(find((List<?>) call(screen, "rows"), "regular") != null, "missing hull retains transmitted editable slots");
        check(find((List<?>) call(screen, "rows"), "builtin") != null, "missing hull retains transmitted built-ins");
        missingHull = false;
    }

    private static WeaponSlotAPI slot(String id, boolean builtin, boolean decorative, boolean hidden, boolean system, boolean module, WeaponSize size) {
        return proxy(WeaponSlotAPI.class, (p, m, a) -> {
            if (m.getName().equals("getId")) return id;
            if (m.getName().equals("getSlotSize")) return size;
            if (m.getName().equals("getWeaponType")) return WeaponType.ENERGY;
            if (m.getName().equals("isWeaponSlot")) return !builtin && !decorative && !system && !module;
            if (m.getName().equals("isBuiltIn")) return builtin;
            if (m.getName().equals("isDecorative")) return decorative;
            if (m.getName().equals("isHidden")) return hidden;
            if (m.getName().equals("isSystemSlot")) return system;
            if (m.getName().equals("isStationModule")) return module;
            if (m.getName().equals("isHardpoint")) return false;
            return unexpected("slot " + m.getName());
        });
    }

    private static LabelAPI label(String text) {
        final Color[] color = { Color.WHITE };
        PositionAPI position = proxy(PositionAPI.class, (p, m, a) -> {
            if (m.getName().equals("getHeight")) return 16f;
            if (m.getName().equals("setLocation")) return p;
            return unexpected("position " + m.getName());
        });
        return proxy(LabelAPI.class, (p, m, a) -> {
            if (m.getName().equals("setColor")) { color[0] = (Color) a[0]; return null; }
            if (m.getName().equals("autoSizeToWidth")) return null;
            if (m.getName().equals("getPosition")) return position;
            if (m.getName().equals("computeTextWidth")) return ((String) a[0]).length() * 7f;
            if (m.getName().equals("render")) { rendered.add(text); colors.put(text, color[0]); return null; }
            return unexpected("label " + m.getName());
        });
    }

    private static Object find(List<?> rows, String id) throws Exception {
        for (Object row : rows) if (field(row.getClass(), "id").get(row).equals(id)) return row;
        return null;
    }

    private static int kind(Object row) throws Exception { return field(row.getClass(), "kind").getInt(row); }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Object call(Object target, String name, Object... args) throws Exception {
        for (Method method : target.getClass().getDeclaredMethods()) if (method.getName().equals(name) && method.getParameterTypes().length == args.length) {
            method.setAccessible(true);
            return method.invoke(target, args);
        }
        throw new AssertionError("Method not found: " + name);
    }

    private static Object unexpected(String message) { unexpected.add(message); throw new AssertionError(message); }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler));
    }

    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}

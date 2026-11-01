package wingman.compat;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;

/** The Virtuous loadout cycle done on a variant as Diable's master hull mod would do it on the ship. */
public final class DiableAvionicsTest {
    private static int checks;

    /** A variant stand-in: hull, hull mods, weapons by slot, and how often weapon groups were regenerated. */
    private static final class Variant {
        ShipHullSpecAPI hull;
        final Set<String> mods = new LinkedHashSet<String>();
        final Map<String, String> weapons = new HashMap<String, String>();
        int regrouped;
        final ShipVariantAPI api = proxy(ShipVariantAPI.class, (p, m, a) -> {
            switch (m.getName()) {
                case "getHullSpec": return hull;
                case "setHullSpecAPI": hull = (ShipHullSpecAPI) a[0]; return null;
                case "getHullMods": return mods;
                case "addMod": mods.add((String) a[0]); return null;
                case "getWeaponId": return weapons.get(a[0]);
                case "clearSlot": weapons.remove(a[0]); return null;
                case "addWeapon": weapons.put((String) a[0], (String) a[1]); return null;
                case "autoGenerateWeaponGroups": regrouped++; return null;
                default: throw new AssertionError("variant " + m.getName());
            }
        });
    }

    public static void main(String[] args) {
        Map<String, ShipHullSpecAPI> hulls = new HashMap<String, ShipHullSpecAPI>();
        for (String id : DiableAvionics.CONFIGS) hulls.put(id, hull(id, DiableAvionics.CONFIGS.get(0)));
        hulls.put("other", hull("other", "other"));
        Global.setSettings(proxy(SettingsAPI.class, (p, m, a) -> {
            if (m.getName().equals("getHullSpec")) return hulls.get(a[0]);
            throw new AssertionError("settings " + m.getName());
        }));

        // A full loadout: nothing changes, the weapon groups stay.
        Variant v = virtuous(hulls.get("diableavionics_virtuous_skirmisher"), "skirmisher", "scicle", "roar", "headB");
        DiableAvionics.settle(v.api);
        check(v.mods.size() == 4 && v.regrouped == 0 && v.hull.getHullId().equals("diableavionics_virtuous_skirmisher"), "full loadout untouched");

        // Left kit taken off: the next kit, its hull mod and weapon; the right side stays.
        v = virtuous(hulls.get("diableavionics_virtuous_skirmisher"), "skirmisher", "scicle", "roar", "headB");
        v.mods.remove("diableavionics_virtuous_scicle_L");
        DiableAvionics.settle(v.api);
        check(v.mods.contains("diableavionics_virtuous_snowblast_L") && "diableavionics_virtuous_snowblast_L".equals(v.weapons.get("LEFT")), "left kit steps on");
        check("diableavionics_virtuous_roar_R".equals(v.weapons.get("RIGHT")) && v.regrouped == 1, "right kit kept, groups regenerated");

        // The last kit wraps around to the first.
        v = virtuous(hulls.get("diableavionics_virtuous_skirmisher"), "skirmisher", "scicle", "roar", "headB");
        v.mods.remove("diableavionics_virtuous_roar_R");
        DiableAvionics.settle(v.api);
        check("diableavionics_virtuous_scicle_R".equals(v.weapons.get("RIGHT")) && v.mods.contains("diableavionics_virtuous_scicle_R"), "right kit wraps");

        // Head: D wraps to A; none fitted gives A.
        v = virtuous(hulls.get("diableavionics_virtuous_skirmisher"), "skirmisher", "scicle", "roar", "headD");
        v.mods.remove("diableavionics_virtuous_headD");
        DiableAvionics.settle(v.api);
        check("diableavionics_virtuous_headA".equals(v.weapons.get("HEAD")) && v.mods.contains("diableavionics_virtuous_headA"), "head wraps");
        v = virtuous(hulls.get("diableavionics_virtuous_skirmisher"), "skirmisher", "scicle", "roar", "headB");
        v.mods.remove("diableavionics_virtuous_headB");
        v.weapons.remove("HEAD");
        DiableAvionics.settle(v.api);
        check("diableavionics_virtuous_headA".equals(v.weapons.get("HEAD")), "no head gives the first");

        // Hull config: skirmisher -> brawler (the skin hull), scout -> skirmisher; weapons untouched.
        v = virtuous(hulls.get("diableavionics_virtuous_skirmisher"), "skirmisher", "scicle", "roar", "headB");
        v.mods.remove("diableavionics_virtuous_skirmisher");
        DiableAvionics.settle(v.api);
        check(v.hull.getHullId().equals("diableavionics_virtuous_brawler") && v.mods.contains("diableavionics_virtuous_brawler"), "config steps on");
        check(v.regrouped == 0 && "diableavionics_virtuous_scicle_L".equals(v.weapons.get("LEFT")), "config switch leaves weapons");
        v = virtuous(hulls.get("diableavionics_virtuous_scout"), "scout", "scicle", "roar", "headB");
        v.mods.remove("diableavionics_virtuous_scout");
        DiableAvionics.settle(v.api);
        check(v.hull.getHullId().equals("diableavionics_virtuous_skirmisher") && v.mods.contains("diableavionics_virtuous_skirmisher"), "config wraps");

        // Any other ship: nothing, even with no selectors at all.
        v = new Variant();
        v.hull = hulls.get("other");
        DiableAvionics.settle(v.api);
        check(v.mods.isEmpty() && v.weapons.isEmpty() && v.regrouped == 0, "other ships untouched");

        // Joiner notes: selectors only.
        check(ModCompat.switchNote("diableavionics_virtuous_beast_L") != null && ModCompat.switchNote("diableavionics_virtuous_headC") != null
                && ModCompat.switchNote("diableavionics_virtuous_defender") != null, "selectors noted");
        check(ModCompat.switchNote("diableavionics_virtuousMasterHullmod") == null && ModCompat.switchNote("diableavionics_virtuous_beast") == null
                && ModCompat.switchNote("heavyarmor") == null && ModCompat.switchNote(null) == null, "others not noted");

        System.out.println("DiableAvionicsTest: " + checks + " checks passed");
    }

    private static Variant virtuous(ShipHullSpecAPI hull, String config, String left, String right, String head) {
        Variant v = new Variant();
        v.hull = hull;
        String p = "diableavionics_virtuous_";
        v.mods.addAll(Arrays.asList(p + config, p + left + "_L", p + right + "_R", p + head));
        v.weapons.put("LEFT", p + left + "_L");
        v.weapons.put("RIGHT", p + right + "_R");
        v.weapons.put("HEAD", p + head);
        return v;
    }

    private static ShipHullSpecAPI hull(String id, String base) {
        return proxy(ShipHullSpecAPI.class, (p, m, a) -> {
            if (m.getName().equals("getHullId")) return id;
            if (m.getName().equals("getBaseHullId")) return base;
            throw new AssertionError("hull " + m.getName());
        });
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        checks++;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler h) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, h);
    }
}

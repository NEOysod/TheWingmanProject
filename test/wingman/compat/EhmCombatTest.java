package wingman.compat;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.util.vector.Vector2f;
import wingman.net.ShipSpawn;

public final class EhmCombatTest {
    private static final class Variant {
        final Map<String, String> weapons = new HashMap<String, String>();
        final HashMap<String, String> builtins = new HashMap<String, String>();
        final Map<String, WeaponSlotAPI> slots = new HashMap<String, WeaponSlotAPI>();
        final java.util.Set<String> tags = new java.util.HashSet<String>();
        boolean enabled = true;
        ShipHullSpecAPI hull = proxy(ShipHullSpecAPI.class, (p, m, a) -> {
            switch (m.getName()) {
                case "getBuiltInWeapons": return builtins;
                case "getAllWeaponSlotsCopy": return new ArrayList<WeaponSlotAPI>(slots.values());
                default: throw new AssertionError(m.getName());
            }
        });
        ShipVariantAPI api = proxy(ShipVariantAPI.class, (p, m, a) -> {
            switch (m.getName()) {
                case "hasHullMod": return enabled && "ehm_base".equals(a[0]);
                case "getTags": return tags;
                case "getHullMods": return enabled ? Collections.singleton("ehm_base") : Collections.emptySet();
                case "getHullVariantId": return "module_Hull";
                case "getFittedWeaponSlots": return new ArrayList<String>(weapons.keySet());
                case "getWeaponId": return weapons.get(a[0]);
                case "getHullSpec": return hull;
                case "getSlot": return slots.get(a[0]);
                case "addWeapon": weapons.put((String) a[0], (String) a[1]); return null;
                default: throw new AssertionError(m.getName());
            }
        });
    }

    public static void main(String[] args) throws Exception {
        Variant stock = new Variant();
        WeaponSpecAPI weapon = proxy(WeaponSpecAPI.class, (p, m, a) -> null);
        Global.setSettings(proxy(SettingsAPI.class, (p, m, a) -> {
            if (m.getName().equals("getHullSpec")) return stock.hull;
            if (m.getName().equals("getWeaponSpec")) return weapon;
            throw new AssertionError(m.getName());
        }));
        Variant host = new Variant();
        host.weapons.put("child", "gun");
        host.builtins.put("parent", "shunt");
        host.slots.put("parent", slot("parent", WeaponAPI.WeaponSize.MEDIUM));
        host.slots.put("child", slot("child", WeaponAPI.WeaponSize.SMALL));
        host.slots.put("empty", slot("empty", WeaponAPI.WeaponSize.SMALL));
        ShipAPI ship = proxy(ShipAPI.class, (p, m, a) -> {
            if (m.getName().equals("getVariant")) return host.api;
            throw new AssertionError(m.getName());
        });
        ShipSpawn original = new ShipSpawn();
        original.hullId = "same_hull";
        original.variantTags.addAll(Arrays.asList("ehm_mount:child:ENERGY", "ehm_size:parent:SMALL"));
        EhmCombat.capture(ship, original);
        ShipSpawn decoded = ShipSpawn.decode(original.encode());
        check(decoded.fullVariant && decoded.variantId.equals("module_Hull"), "variant identity");
        check(decoded.fittedSlots.size() == 2 && decoded.slotState.size() == 3, "fitted shunt and empty slot");
        check(decoded.variantTags.equals(original.variantTags), "tags round trip");
        Variant replica = new Variant();
        replica.tags.addAll(decoded.variantTags);
        EhmCombat.stage(decoded, replica.api);
        check("shunt".equals(replica.weapons.get("parent")) && "gun".equals(replica.weapons.get("child")), "staged before slots exist");
        replica.slots.putAll(host.slots);
        Variant second = new Variant();
        second.tags.addAll(decoded.variantTags);
        EhmCombat.stage(decoded, second.api);
        second.slots.putAll(host.slots);
        second.weapons.put("child", "other_gun");
        expectFailure(() -> EhmCombat.check(decoded, second.api), "same hull with different fitting");
        check("gun".equals(replica.weapons.get("child")), "replicas keep independent fittings");
        EhmCombat.check(decoded, replica.api);
        replica.slots.put("empty", slot("empty", WeaponAPI.WeaponSize.LARGE));
        expectFailure(() -> EhmCombat.check(decoded, replica.api), "slot mismatch");
        check(stock.builtins.isEmpty() && stock.slots.isEmpty(), "shared stock hull unchanged");
        Variant other = new Variant();
        other.enabled = false;
        EhmCombat.stage(new ShipSpawn(), other.api);
        check(other.weapons.isEmpty(), "ordinary variant unchanged");
        expectFailure(() -> EhmCombat.stage(decoded, other.api), "missing hullmod");
        byte[] bytes = original.encode();
        try { ShipSpawn.decode(Arrays.copyOf(bytes, bytes.length - 1)); throw new AssertionError("truncated accepted"); }
        catch (IOException expected) { }
        ShipSpawn manyTags = new ShipSpawn();
        for (int i = 0; i < 250; i++) manyTags.variantTags.add("tag" + i);
        check(ShipSpawn.decode(manyTags.encode()).variantTags.equals(manyTags.variantTags), "all tags survive cached replay");
        decoded.fittedIds.clear();
        expectFailure(() -> decoded.encode(), "unpaired slots");
        original.variantTags.addAll(Collections.nCopies(1024, "tag"));
        expectFailure(() -> original.encode(), "oversized tags");
        System.out.println("EhmCombatTest passed");
    }

    private static WeaponSlotAPI slot(String id, WeaponAPI.WeaponSize size) {
        return proxy(WeaponSlotAPI.class, (p, m, a) -> {
            switch (m.getName()) {
                case "getId": return id;
                case "getWeaponType": return WeaponAPI.WeaponType.ENERGY;
                case "getSlotSize": return size;
                case "getLocation": return new Vector2f(1, 2);
                case "getArc": return 90f;
                case "getAngle": return 0f;
                default: if (m.getName().startsWith("is")) return false; throw new AssertionError(m.getName());
            }
        });
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void expectFailure(Runnable action, String message) {
        try { action.run(); throw new AssertionError(message); } catch (IllegalStateException expected) { }
    }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, InvocationHandler h) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, h);
    }
}

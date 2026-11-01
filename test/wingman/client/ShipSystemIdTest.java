package wingman.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipSystemSpecAPI;

public final class ShipSystemIdTest {
    public static void main(String[] args) throws Exception {
        ShipSystemSpecAPI system = (ShipSystemSpecAPI) Proxy.newProxyInstance(ShipSystemSpecAPI.class.getClassLoader(), new Class<?>[] { ShipSystemSpecAPI.class },
            (p, m, a) -> m.getName().equals("getName") ? "Changed System" : m.getName().equals("getId") ? "changed" : null);
        ShipHullSpecAPI hull = (ShipHullSpecAPI) Proxy.newProxyInstance(ShipHullSpecAPI.class.getClassLoader(), new Class<?>[] { ShipHullSpecAPI.class },
            (p, m, a) -> m.getName().equals("getManufacturer") ? "Shipbuilder" : null);
        Global.setSettings((SettingsAPI) Proxy.newProxyInstance(SettingsAPI.class.getClassLoader(), new Class<?>[] { SettingsAPI.class },
            (p, m, a) -> m.getName().equals("getShipSystemSpec") ? system : m.getName().equals("getHullSpec") ? hull : null));
        try {
            Method tip = Tips.class.getDeclaredMethod("tip", String.class, boolean.class, String.class);
            tip.setAccessible(true);
            Object actual = tip.invoke(null, "S:hull\000changed", false, "");
            Field title = actual.getClass().getDeclaredField("title"), head = actual.getClass().getDeclaredField("head");
            title.setAccessible(true);
            head.setAccessible(true);
            if (!"Changed System".equals(title.get(actual)) || !"Design type: Shipbuilder".equals(head.get(actual)))
                throw new AssertionError("transmitted system tooltip lost system name or hull details");
            if (!"None".equals(ShipSystemInfo.readSystem("").name)) throw new AssertionError("empty system should show None");
            System.out.println("ShipSystemIdTest: 2 checks passed");
        } finally {
            Global.setSettings(null);
        }
    }
}

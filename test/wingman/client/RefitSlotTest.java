package wingman.client;

import java.lang.reflect.Proxy;
import java.util.EnumSet;

import com.fs.starfarer.api.combat.WeaponAPI.WeaponSize;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import wingman.net.RefitData;

public final class RefitSlotTest {
    private static WeaponSpecAPI weapon(WeaponType type, WeaponSize size, boolean restricted) {
        return (WeaponSpecAPI) Proxy.newProxyInstance(WeaponSpecAPI.class.getClassLoader(), new Class<?>[] { WeaponSpecAPI.class },
            (proxy, method, args) -> {
                if (method.getName().equals("getMountType")) return type;
                if (method.getName().equals("getSize")) return size;
                if (method.getName().equals("isRestrictToSpecifiedMountType")) return restricted;
                if (method.getName().equals("getAIHints")) return EnumSet.noneOf(com.fs.starfarer.api.combat.WeaponAPI.AIHints.class);
                throw new AssertionError(method.getName());
            });
    }

    public static void main(String[] args) {
        RefitData.Slot data = new RefitData.Slot();
        data.type = "HYBRID";
        data.size = "MEDIUM";
        RefitSlot slot = new RefitSlot(data);
        check(slot.weaponFits(weapon(WeaponType.UNIVERSAL, WeaponSize.MEDIUM, false)), "universal weapon fits hybrid mount");
        check(!slot.weaponFits(weapon(WeaponType.UNIVERSAL, WeaponSize.MEDIUM, true)), "restricted weapon requires exact mount");
        check(!slot.weaponFits(weapon(WeaponType.UNIVERSAL, WeaponSize.SMALL, false)), "mixed mounts require equal size");
        check(slot.weaponFits(weapon(WeaponType.HYBRID, WeaponSize.SMALL, false)), "matching mounts accept one size smaller");
        check(!slot.weaponFits(weapon(WeaponType.MISSILE, WeaponSize.MEDIUM, false)), "missile weapon does not fit hybrid mount");
        System.out.println("RefitSlotTest: 5 checks passed");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}

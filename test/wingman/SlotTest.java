package wingman;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import com.fs.starfarer.api.loading.WeaponSlotAPI;

/**
 * Which slots' weapons a joiner may put in weapon groups (FriendRefit.groupable), checked against how the game
 * itself answers for each slot type: its isWeaponSlot() names the mountable types only, so a BUILT_IN slot (where
 * UAF's Super Nakiha keeps its two HVARs) must count by isBuiltIn().
 */
public class SlotTest {
    static int fails, checks;
    static void check(boolean ok, String what) { checks++; System.out.println((ok ? "ok   " : "FAIL ") + what); if (!ok) fails++; }

    /** A slot answering as the game's own slot class does for this type and mount. */
    static WeaponSlotAPI slot(final String type, final boolean hiddenMount) {
        return (WeaponSlotAPI) Proxy.newProxyInstance(SlotTest.class.getClassLoader(), new Class<?>[] { WeaponSlotAPI.class }, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                String n = m.getName();
                if (n.equals("isWeaponSlot")) return java.util.Arrays.asList("BALLISTIC", "ENERGY", "UNIVERSAL", "HYBRID", "SYNERGY", "COMPOSITE", "MISSILE").contains(type);
                if (n.equals("isBuiltIn")) return type.equals("BUILT_IN");
                if (n.equals("isDecorative")) return type.equals("DECORATIVE");
                if (n.equals("isSystemSlot")) return type.equals("SYSTEM");
                if (n.equals("isStationModule")) return type.equals("STATION_MODULE");
                if (n.equals("isHidden")) return hiddenMount;
                if (m.getReturnType() == boolean.class) return false;
                return null;
            }
        });
    }

    public static void main(String[] a) {
        check(FriendRefit.groupable(slot("UNIVERSAL", false)), "an ordinary slot: groupable");
        check(FriendRefit.groupable(slot("ENERGY", false)), "a built-in gun in an ordinary slot (a hull mod, the variant): groupable");
        check(FriendRefit.groupable(slot("BUILT_IN", true)), "a BUILT_IN slot with a hidden mount (Super Nakiha's HVARs): groupable");
        check(FriendRefit.groupable(slot("BUILT_IN", false)), "a BUILT_IN slot: groupable");
        check(!FriendRefit.groupable(slot("DECORATIVE", false)), "a decorative slot (blinkers): not");
        check(!FriendRefit.groupable(slot("SYSTEM", true)), "the ship system's slot: not");
        check(!FriendRefit.groupable(slot("STATION_MODULE", false)), "a module's place: not");
        check(!FriendRefit.groupable(slot("LAUNCH_BAY", true)), "a launch bay: not");
        check(!FriendRefit.groupable(null), "no slot: not");
        System.out.println(fails == 0 ? "SLOTS PASSED (" + checks + " checks)" : "SLOTS FAILED (" + fails + " of " + checks + ")");
        if (fails > 0) System.exit(1);
    }
}

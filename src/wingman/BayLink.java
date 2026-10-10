package wingman;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.DeployedFleetMemberAPI;
import com.fs.starfarer.api.combat.FighterLaunchBayAPI;
import com.fs.starfarer.api.combat.FighterWingAPI;
import com.fs.starfarer.api.combat.ShipAPI;

/**
 * Makes the host's fighters, as shown on a joiner's PC, belong to the hangar (launch bay) of the joiner's copy of
 * their carrier.
 *
 * A copy of a carrier never launches anything itself: the fighters on screen are the host's, made here as
 * wings of their own. So the copy's hangars looked empty: the carrier panel was greyed out, and scripts that ask
 * a carrier for its fighters (ShipAPI.getAllWings(), which goes bay by bay) found none, such as UAF's
 * Rafflesya Control and Isenoria Manual Override. Here each host wing is put in the right hangar: the wing is
 * told its carrier and bay (public API), and the bay is pointed at the wing's deployed-member record, which is
 * what bay.getWing() reads. That record is a private field of the game's bay class
 * (com.fs.starfarer.combat.entities.ship.A.C, the one field of type CombatFleetManager$O0), set through
 * java.lang.invoke, which the script sandbox allows. Nothing else about the bay changes; its own launches stay
 * held back as before. If the field can't be reached, carriers look as they did, with one log line.
 */
final class BayLink {
    private BayLink() {}

    /** The bay class's field holding its wing's deployed-member record (an obfuscated name: Ò ô ô o 0 0). */
    private static final String FIELD = "Òôôo00";
    private static boolean broken;
    private static MethodHandle setter;
    private static int linked;

    /**
     * Put this host wing in hangar number bayIndex of the carrier copy. The fighter is any member of the wing, used
     * to find its deployed-member record.
     */
    static void attach(ShipAPI carrier, int bayIndex, FighterWingAPI wing, ShipAPI fighter, CombatFleetManagerAPI fm) {
        if (broken || carrier == null || wing == null || bayIndex < 0) return;
        try {
            List<FighterLaunchBayAPI> bays = carrier.getLaunchBaysCopy();
            if (bays == null || bayIndex >= bays.size()) return;
            FighterLaunchBayAPI bay = bays.get(bayIndex);
            if (bay.getWing() == wing) return;
            DeployedFleetMemberAPI dfm = fm.getDeployedFleetMember(fighter);
            if (dfm == null) return;
            if (setter == null) {
                Class<?> bayClass = bay.getClass();
                Class<?> dfmClass = Class.forName("com.fs.starfarer.combat.CombatFleetManager$O0", false, bayClass.getClassLoader());
                MethodHandle pli = MethodHandles.lookup().findStatic(MethodHandles.class, "privateLookupIn",
                        MethodType.methodType(MethodHandles.Lookup.class, Class.class, MethodHandles.Lookup.class));
                MethodHandles.Lookup l = (MethodHandles.Lookup) pli.invoke(bayClass, MethodHandles.lookup());
                setter = l.findSetter(bayClass, FIELD, dfmClass);
            }
            wing.setSourceShip(carrier);
            wing.setSourceBay(bay);
            setter.invoke(bay, dfm);
            if (linked++ == 0) Dbg.info(BayLink.class, "Wingman fighters: the host's fighters are put in their carriers' hangars here");
        } catch (Throwable t) {
            broken = true;
            Global.getLogger(BayLink.class).warn("Wingman: fighters can't be put in their carriers' hangars on this PC; "
                    + "carrier panels may look empty and carrier scripts may not find their fighters", t);
        }
    }
}

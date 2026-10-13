package shipmastery.util;

/** Test stand-in: counts the ships introduced to it. */
public class VariantLookup {
    private static VariantLookup instance;
    public static int added;
    public VariantLookup() { instance = this; }
    public static VariantLookup getInstance() { return instance; }
    public static void addVariantInfo(com.fs.starfarer.api.combat.ShipVariantAPI v, com.fs.starfarer.api.combat.ShipVariantAPI root,
                                      com.fs.starfarer.api.fleet.FleetMemberAPI m) { added++; }
}
